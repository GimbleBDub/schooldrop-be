package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.team.TeamDtos;
import com.gimble.bus_stop.dto.requests.*;
import com.gimble.bus_stop.dto.responses.*;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.BookingStatus;
import com.gimble.bus_stop.enums.DocumentType;
import com.gimble.bus_stop.services.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Driver onboarding and day-to-day work: profile, vehicles, documents, offers, pickups, trips, earnings. */
@RestController
@RequestMapping("/v1/driver")
@RequiredArgsConstructor
public class DriverController {

    private final DriverService driverService;
    private final OfferService offerService;
    private final BookingService bookingService;
    private final AdHocService adHocService;
    private final TripService tripService;
    private final PayoutService payoutService;
    private final SubscriptionService subscriptionService;
    private final AdvertService advertService;
    private final TeamService teamService;

    // ─── Onboarding ──────────────────────────────────────────────────────────

    @GetMapping("/profile")
    public DriverProfileResponse profile(@AuthenticationPrincipal User user) {
        return driverService.getMyProfile(user);
    }

    @PutMapping("/profile")
    public DriverProfileResponse updateProfile(@AuthenticationPrincipal User user,
                                               @Valid @RequestBody DriverProfileRequest request) {
        return driverService.updateMyProfile(user, request);
    }

    @PutMapping("/schools")
    public DriverProfileResponse setSchools(@AuthenticationPrincipal User user,
                                            @Valid @RequestBody DriverSchoolsRequest request) {
        return driverService.setMySchools(user, request.schoolIds());
    }

    @PostMapping("/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleResponse addVehicle(@AuthenticationPrincipal User user, @Valid @RequestBody VehicleRequest request) {
        return driverService.addVehicle(user, request);
    }

    @PutMapping("/vehicles/{vehicleId}")
    public VehicleResponse updateVehicle(@AuthenticationPrincipal User user, @PathVariable UUID vehicleId,
                                         @Valid @RequestBody VehicleRequest request) {
        return driverService.updateVehicle(user, vehicleId, request);
    }

    @PostMapping("/vehicles/{vehicleId}/deactivate")
    public VehicleResponse deactivateVehicle(@AuthenticationPrincipal User user, @PathVariable UUID vehicleId) {
        return driverService.deactivateVehicle(user, vehicleId);
    }

    @PostMapping(value = "/documents", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse uploadDocument(
            @AuthenticationPrincipal User user,
            @RequestParam DocumentType type,
            @RequestParam(required = false) UUID vehicleId,
            @RequestParam(required = false) String referenceNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedOn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn,
            @RequestPart("file") MultipartFile file) {
        return driverService.uploadDocument(user, type, vehicleId, file, referenceNumber, issuedOn, expiresOn);
    }

    @GetMapping("/documents/{documentId}/file")
    public ResponseEntity<?> downloadDocument(@AuthenticationPrincipal User user, @PathVariable UUID documentId) {
        return Files.inline(driverService.downloadMyDocument(user, documentId));
    }

    @PostMapping("/submit")
    public DriverProfileResponse submit(@AuthenticationPrincipal User user) {
        return driverService.submitForReview(user);
    }

    // ─── Team / transport company ────────────────────────────────────────────

    @GetMapping("/team")
    public TeamDtos.TeamResponse team(@AuthenticationPrincipal User user) {
        return teamService.get(user);
    }

    /** A driver on their own starts a team / company; their vehicles become its fleet. */
    @PostMapping("/team")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamDtos.TeamResponse createTeam(@AuthenticationPrincipal User user, @Valid @RequestBody TeamDtos.CreateTeamRequest request) {
        return teamService.create(user, request);
    }

    @PutMapping("/team")
    public TeamDtos.TeamResponse updateTeam(@AuthenticationPrincipal User user, @Valid @RequestBody TeamDtos.UpdateTeamRequest request) {
        return teamService.update(user, request);
    }

    @PostMapping("/team/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse inviteToTeam(@AuthenticationPrincipal User user, @Valid @RequestBody TeamDtos.InviteRequest request) {
        var issued = teamService.invite(user, request.role(), request.name());
        return InviteResponse.withCode(issued.invite(), issued.code());
    }

    @DeleteMapping("/team/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeTeamInvite(@AuthenticationPrincipal User user, @PathVariable UUID inviteId) {
        teamService.revokeInvite(user, inviteId);
    }

    /** An existing driver joins a team (their vehicles and routes come along). */
    @PostMapping("/team/join")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void joinTeam(@AuthenticationPrincipal User user, @Valid @RequestBody TeamDtos.JoinRequest request) {
        teamService.join(user, request.code());
    }

    @PutMapping("/team/members/{driverId}")
    public TeamDtos.TeamResponse updateMember(@AuthenticationPrincipal User user, @PathVariable UUID driverId,
                                              @Valid @RequestBody TeamDtos.MemberUpdateRequest request) {
        return teamService.updateMember(user, driverId, request);
    }

    @DeleteMapping("/team/members/{driverId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal User user, @PathVariable UUID driverId) {
        teamService.removeMember(user, driverId);
    }

    @PostMapping("/team/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveTeam(@AuthenticationPrincipal User user) {
        teamService.leave(user);
    }

    /** Every run of the team on a day (owner / managers). */
    @GetMapping("/team/trips")
    public List<TripResponse> teamTrips(@AuthenticationPrincipal User user,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return tripService.teamTrips(user, date);
    }

    /** Someone else covers one run (sick driver, broken-down car). */
    @PostMapping("/team/trips/{tripId}/assign")
    public TripResponse assignTrip(@AuthenticationPrincipal User user, @PathVariable UUID tripId,
                                   @Valid @RequestBody TeamDtos.AssignRequest request) {
        return tripService.assignTrip(user, tripId, request.driverId());
    }

    // ─── Offers & bookings ───────────────────────────────────────────────────

    @PostMapping("/offers")
    @ResponseStatus(HttpStatus.CREATED)
    public OfferResponse createOffer(@AuthenticationPrincipal User user, @Valid @RequestBody OfferRequest request) {
        return offerService.create(user, request);
    }

    @GetMapping("/offers")
    public List<OfferResponse> myOffers(@AuthenticationPrincipal User user) {
        return offerService.listMine(user);
    }

    @PutMapping("/offers/{offerId}")
    public OfferResponse updateOffer(@AuthenticationPrincipal User user, @PathVariable UUID offerId,
                                     @Valid @RequestBody OfferRequest request) {
        return offerService.update(user, offerId, request);
    }

    @GetMapping("/bookings")
    public List<BookingResponse> bookings(@AuthenticationPrincipal User user,
                                          @RequestParam(required = false) BookingStatus status) {
        return bookingService.listForDriver(user, status);
    }

    /** Bookings the parent pays directly: confirm the money arrived (or that it didn't). */
    @PostMapping("/bookings/{bookingId}/payment-received")
    public BookingResponse paymentReceived(@AuthenticationPrincipal User user, @PathVariable UUID bookingId) {
        return bookingService.confirmOfflinePayment(user, bookingId, true);
    }

    @PostMapping("/bookings/{bookingId}/payment-not-received")
    public BookingResponse paymentNotReceived(@AuthenticationPrincipal User user, @PathVariable UUID bookingId) {
        return bookingService.confirmOfflinePayment(user, bookingId, false);
    }

    // ─── Monthly listing fee ─────────────────────────────────────────────────

    @GetMapping("/subscription")
    public SubscriptionResponse subscription(@AuthenticationPrincipal User user) {
        return subscriptionService.status(user);
    }

    @PostMapping("/subscription/pay")
    public PaymentResponse paySubscription(@AuthenticationPrincipal User user, @Valid @RequestBody PayRequest request) {
        return subscriptionService.pay(user, request);
    }

    // ─── Adverts ─────────────────────────────────────────────────────────────

    @GetMapping("/adverts")
    public List<AdvertResponse> adverts(@AuthenticationPrincipal User user) {
        return advertService.listMine(user);
    }

    @PostMapping("/adverts")
    @ResponseStatus(HttpStatus.CREATED)
    public AdvertResponse createAdvert(@AuthenticationPrincipal User user, @Valid @RequestBody AdvertRequest request) {
        return advertService.create(user, request);
    }

    @PostMapping("/adverts/{advertId}/pay")
    public PaymentResponse payAdvert(@AuthenticationPrincipal User user, @PathVariable UUID advertId,
                                     @Valid @RequestBody PayRequest request) {
        return advertService.pay(user, advertId, request);
    }

    // ─── Once-off pickups ───────────────────────────────────────────────────

    @GetMapping("/adhoc/open")
    public List<AdHocResponse> openPickups(@AuthenticationPrincipal User user) {
        return adHocService.openForDriver(user);
    }

    @GetMapping("/adhoc")
    public List<AdHocResponse> myPickups(@AuthenticationPrincipal User user) {
        return adHocService.listForDriver(user);
    }

    @PostMapping("/adhoc/{requestId}/accept")
    public AdHocResponse accept(@AuthenticationPrincipal User user, @PathVariable UUID requestId) {
        return adHocService.accept(user, requestId);
    }

    @PostMapping("/adhoc/{requestId}/release")
    public AdHocResponse release(@AuthenticationPrincipal User user, @PathVariable UUID requestId) {
        return adHocService.release(user, requestId);
    }

    @PostMapping("/adhoc/{requestId}/cancel")
    public AdHocResponse cancel(@AuthenticationPrincipal User user, @PathVariable UUID requestId,
                                @Valid @RequestBody ReasonRequest request) {
        return adHocService.cancelByDriver(user, requestId, request.reason());
    }

    // ─── Trips ───────────────────────────────────────────────────────────────

    @GetMapping("/trips")
    public List<TripResponse> trips(@AuthenticationPrincipal User user,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return tripService.driverTrips(user, date);
    }

    @PostMapping("/trips/{tripId}/start")
    public TripResponse startTrip(@AuthenticationPrincipal User user, @PathVariable UUID tripId) {
        return tripService.start(user, tripId);
    }

    @PostMapping("/trips/{tripId}/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void location(@AuthenticationPrincipal User user, @PathVariable UUID tripId,
                         @Valid @RequestBody LocationRequest request) {
        tripService.updateLocation(user, tripId, request);
    }

    @PostMapping("/trips/{tripId}/passengers/{passengerId}/pickup")
    public TripResponse pickup(@AuthenticationPrincipal User user, @PathVariable UUID tripId,
                               @PathVariable UUID passengerId) {
        return tripService.pickup(user, tripId, passengerId);
    }

    /** handedTo: who received the child at home (required for afternoon drop-offs), e.g. "Helper (Mpho)". */
    public record DropoffRequest(@Size(max = 120) String handedTo) {
    }

    @PostMapping("/trips/{tripId}/passengers/{passengerId}/dropoff")
    public TripResponse dropoff(@AuthenticationPrincipal User user, @PathVariable UUID tripId,
                                @PathVariable UUID passengerId, @Valid @RequestBody(required = false) DropoffRequest request) {
        return tripService.dropoff(user, tripId, passengerId, request != null ? request.handedTo() : null);
    }

    @PostMapping("/trips/{tripId}/passengers/{passengerId}/absent")
    public TripResponse absent(@AuthenticationPrincipal User user, @PathVariable UUID tripId,
                               @PathVariable UUID passengerId) {
        return tripService.markAbsent(user, tripId, passengerId);
    }

    @PostMapping("/trips/{tripId}/complete")
    public TripResponse completeTrip(@AuthenticationPrincipal User user, @PathVariable UUID tripId) {
        return tripService.complete(user, tripId);
    }

    // ─── Money ───────────────────────────────────────────────────────────────

    @GetMapping("/earnings")
    public EarningsResponse earnings(@AuthenticationPrincipal User user) {
        return payoutService.driverEarnings(user);
    }
}

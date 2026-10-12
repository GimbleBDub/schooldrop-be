package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.requests.*;
import com.gimble.bus_stop.dto.responses.*;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.HouseholdRole;
import com.gimble.bus_stop.services.*;
import com.gimble.bus_stop.services.TripService.HandoverStage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The family side. Children and bookings belong to the family owner; owners and guardians manage them,
 * helpers (and everyone else in the family) see today's trips and confirm handovers.
 */
@RestController
@RequestMapping("/v1/parent")
@RequiredArgsConstructor
public class ParentController {

    private final HouseholdService households;
    private final ChildService childService;
    private final OfferService offerService;
    private final BookingService bookingService;
    private final AdHocService adHocService;
    private final PaymentService paymentService;
    private final TripService tripService;

    // ─── Family ──────────────────────────────────────────────────────────────

    public record InviteRequest(@NotNull HouseholdRole role, @Size(max = 120) String name) {
    }

    public record JoinRequest(@NotNull @Size(min = 6, max = 20) String code) {
    }

    @GetMapping("/household")
    public HouseholdResponse household(@AuthenticationPrincipal User user) {
        return households.get(user);
    }

    /** Owner only. Share the code (e.g. on WhatsApp); it works once, for 7 days. */
    @PostMapping("/household/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse invite(@AuthenticationPrincipal User user, @Valid @RequestBody InviteRequest request) {
        var issued = households.invite(user, request.role(), request.name());
        return InviteResponse.withCode(issued.invite(), issued.code());
    }

    @DeleteMapping("/household/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@AuthenticationPrincipal User user, @PathVariable UUID inviteId) {
        households.revokeInvite(user, inviteId);
    }

    @DeleteMapping("/household/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal User user, @PathVariable UUID memberId) {
        households.removeMember(user, memberId);
    }

    @PostMapping("/household/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal User user) {
        households.leave(user);
    }

    /** An existing parent account joining another family (e.g. a partner who signed up separately). */
    @PostMapping("/household/join")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void join(@AuthenticationPrincipal User user, @Valid @RequestBody JoinRequest request) {
        households.join(user, request.code());
    }

    // ─── Children ────────────────────────────────────────────────────────────

    @PostMapping("/children")
    @ResponseStatus(HttpStatus.CREATED)
    public ChildResponse addChild(@AuthenticationPrincipal User user, @Valid @RequestBody ChildRequest request) {
        return childService.create(households.requireFamilyManager(user), request);
    }

    @GetMapping("/children")
    public List<ChildResponse> listChildren(@AuthenticationPrincipal User user) {
        return childService.list(households.requireMember(user));
    }

    @GetMapping("/children/{childId}")
    public ChildResponse getChild(@AuthenticationPrincipal User user, @PathVariable UUID childId) {
        return childService.get(households.requireMember(user), childId);
    }

    @PutMapping("/children/{childId}")
    public ChildResponse updateChild(@AuthenticationPrincipal User user, @PathVariable UUID childId,
                                     @Valid @RequestBody ChildRequest request) {
        return childService.update(households.requireFamilyManager(user), childId, request);
    }

    @DeleteMapping("/children/{childId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeChild(@AuthenticationPrincipal User user, @PathVariable UUID childId) {
        childService.delete(households.requireFamilyManager(user), childId);
    }

    @PostMapping("/children/{childId}/pairing-code")
    public PairingCodeResponse createPairingCode(@AuthenticationPrincipal User user, @PathVariable UUID childId) {
        return childService.createPairingCode(households.requireFamilyManager(user), childId);
    }

    @DeleteMapping("/children/{childId}/device")
    public ChildResponse unpairDevice(@AuthenticationPrincipal User user, @PathVariable UUID childId) {
        return childService.unpairDevice(households.requireFamilyManager(user), childId);
    }

    // ─── Finding a driver ────────────────────────────────────────────────────

    @GetMapping("/children/{childId}/offers")
    public List<OfferResponse> offersForChild(@AuthenticationPrincipal User user, @PathVariable UUID childId) {
        return offerService.searchForChild(households.requireFamilyManager(user), childId);
    }

    @GetMapping("/offers/{offerId}")
    public OfferResponse getOffer(@PathVariable UUID offerId) {
        return offerService.get(offerId);
    }

    // ─── Bookings ────────────────────────────────────────────────────────────

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse book(@AuthenticationPrincipal User user, @Valid @RequestBody BookingRequest request) {
        return bookingService.create(households.requireFamilyManager(user), request);
    }

    @GetMapping("/bookings")
    public List<BookingResponse> listBookings(@AuthenticationPrincipal User user) {
        return bookingService.listMine(households.requireFamilyManager(user));
    }

    @GetMapping("/bookings/{bookingId}")
    public BookingResponse getBooking(@AuthenticationPrincipal User user, @PathVariable UUID bookingId) {
        return bookingService.getMine(households.requireFamilyManager(user), bookingId);
    }

    @PostMapping("/bookings/{bookingId}/pay")
    public PaymentResponse payBooking(@AuthenticationPrincipal User user, @PathVariable UUID bookingId,
                                      @Valid @RequestBody PayRequest request) {
        return paymentService.payBooking(households.requireFamilyManager(user), user, bookingId, request);
    }

    /** For bookings paid to the driver directly: "I've paid" — the driver then confirms. */
    @PostMapping("/bookings/{bookingId}/paid-driver")
    public BookingResponse claimPaidDriver(@AuthenticationPrincipal User user, @PathVariable UUID bookingId) {
        return bookingService.claimOfflinePaid(households.requireFamilyManager(user), bookingId);
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    public BookingResponse cancelBooking(@AuthenticationPrincipal User user, @PathVariable UUID bookingId,
                                         @RequestBody(required = false) ReasonRequest request) {
        return bookingService.cancelByParent(households.requireFamilyManager(user), bookingId,
                request != null ? request.reason() : null);
    }

    // ─── Once-off pickups ───────────────────────────────────────────────────

    @PostMapping("/adhoc")
    @ResponseStatus(HttpStatus.CREATED)
    public AdHocResponse requestPickup(@AuthenticationPrincipal User user, @Valid @RequestBody AdHocCreateRequest request) {
        return adHocService.create(households.requireFamilyManager(user), request);
    }

    @GetMapping("/adhoc")
    public List<AdHocResponse> listPickups(@AuthenticationPrincipal User user) {
        return adHocService.listMine(households.requireFamilyManager(user));
    }

    @PostMapping("/adhoc/{requestId}/pay")
    public PaymentResponse payPickup(@AuthenticationPrincipal User user, @PathVariable UUID requestId,
                                     @Valid @RequestBody PayRequest request) {
        return paymentService.payAdHoc(households.requireFamilyManager(user), user, requestId, request);
    }

    @PostMapping("/adhoc/{requestId}/cancel")
    public AdHocResponse cancelPickup(@AuthenticationPrincipal User user, @PathVariable UUID requestId,
                                      @RequestBody(required = false) ReasonRequest request) {
        return adHocService.cancelByParent(households.requireFamilyManager(user), requestId,
                request != null ? request.reason() : null);
    }

    // ─── Payments ────────────────────────────────────────────────────────────

    @GetMapping("/payments")
    public List<PaymentResponse> listPayments(@AuthenticationPrincipal User user) {
        households.requireFamilyManager(user);
        return paymentService.listMine(user);
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentResponse getPayment(@AuthenticationPrincipal User user, @PathVariable UUID paymentId) {
        return paymentService.getMine(user, paymentId);
    }

    // ─── Trips & handovers (everyone in the family) ──────────────────────────

    @GetMapping("/trips/today")
    public List<TripResponse> todaysTrips(@AuthenticationPrincipal User user) {
        return tripService.parentTrips(households.requireMember(user));
    }

    /** PICKUP = "I handed my child to the driver" (morning). DROPOFF = "My child is home" (afternoon). */
    @PostMapping("/passengers/{passengerId}/confirm")
    public TripResponse confirmHandover(@AuthenticationPrincipal User user, @PathVariable UUID passengerId,
                                       @RequestParam HandoverStage stage) {
        return tripService.familyConfirm(user, households.requireMember(user), passengerId, stage);
    }

    /** "That's not right" — alerts SchoolDrop support immediately. */
    @PostMapping("/passengers/{passengerId}/dispute")
    public TripResponse dispute(@AuthenticationPrincipal User user, @PathVariable UUID passengerId,
                                @RequestBody(required = false) ReasonRequest request) {
        return tripService.dispute(user, passengerId, request != null ? request.reason() : null,
                households.requireMember(user));
    }
}

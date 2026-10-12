package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.LocationRequest;
import com.gimble.bus_stop.dto.responses.TripResponse;
import com.gimble.bus_stop.entities.*;
import com.gimble.bus_stop.enums.*;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.exceptions.UnauthorizedException;
import com.gimble.bus_stop.repositories.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Daily runs and the pickup/drop-off handover record. Regular trips are created lazily the first time
 * the driver opens a day; ad-hoc trips are created when a once-off pickup is paid.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TripService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final TripRepository tripRepository;
    private final TripPassengerRepository passengerRepository;
    private final TransportOfferRepository offerRepository;
    private final BookingRepository bookingRepository;
    private final ChildRepository childRepository;
    private final DriverService driverService;
    private final DriverProfileRepository driverProfileRepository;
    private final SchoolService schoolService;
    private final NotificationService notificationService;
    private final HouseholdService householdService;
    private final IncidentRepository incidentRepository;
    private final AuditService auditService;
    private final Clock clock;

    // ─── Driver ──────────────────────────────────────────────────────────────

    @Transactional
    public List<TripResponse> driverTrips(User user, LocalDate date) {
        DriverProfile driver = driverService.requireProfile(user);
        LocalDate today = LocalDate.now(clock);
        LocalDate day = date != null ? date : today;
        if (day.isBefore(today.minusDays(30)) || day.isAfter(today.plusDays(1))) {
            throw BusinessException.badRequest("Trips can be viewed from 30 days back up to tomorrow");
        }
        if (!day.isBefore(today) && driver.isApproved()) {
            generateTrips(driver, day);
        }
        return tripRepository.findForDriverOn(driver.getId(), day).stream()
                .map(t -> TripResponse.from(t, p -> true)).toList();
    }

    // ─── Teams: today's runs across the team ─────────────────────────────────

    /** Owners and managers see every run of the team for a day (generating them like each driver's app would). */
    @Transactional
    public List<TripResponse> teamTrips(User user, LocalDate date) {
        DriverProfile me = requireTeamManager(user);
        LocalDate today = LocalDate.now(clock);
        LocalDate day = date != null ? date : today;
        if (day.isBefore(today.minusDays(30)) || day.isAfter(today.plusDays(1))) {
            throw BusinessException.badRequest("Runs can be viewed from 30 days back up to tomorrow");
        }
        if (!day.isBefore(today)) {
            driverProfileRepository.findTeam(me.getOperator().getId()).stream()
                    .filter(d -> d.isDrives() && d.isApproved())
                    .forEach(d -> generateTrips(d, day));
        }
        return tripRepository.findForOperatorOn(me.getOperator().getId(), day).stream()
                .map(t -> TripResponse.from(t, p -> true)).toList();
    }

    /**
     * A driver is sick or the car broke down: hand one run (not started yet) to another team driver.
     * The route itself stays with its usual driver; to move it for good, reassign the route.
     */
    @Transactional
    public TripResponse assignTrip(User user, UUID tripId, UUID driverId) {
        DriverProfile me = requireTeamManager(user);
        Trip trip = tripRepository.findById(tripId)
                .filter(t -> me.isTeamMate(t.getDriver()) || t.getDriver().getId().equals(me.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Run not found"));
        if (trip.getStatus() != TripStatus.SCHEDULED) {
            throw BusinessException.conflict("This run is already " + trip.getStatus().name().toLowerCase().replace('_', ' '));
        }
        if (trip.getTripDate().isBefore(LocalDate.now(clock))) {
            throw BusinessException.conflict("Past runs can't be reassigned");
        }
        DriverProfile to = driverService.requireDriver(driverId);
        if (!to.getId().equals(me.getId()) && !me.isTeamMate(to)) {
            throw new ResourceNotFoundException("Driver not found in your team");
        }
        if (!to.isDrives() || !to.isApproved()) {
            throw BusinessException.conflict(to.getUser().getFirstName() + " can't drive runs (not a verified driver)");
        }
        if (to.getId().equals(trip.getDriver().getId())) {
            return TripResponse.from(trip, p -> true);
        }
        boolean busyElsewhere = tripRepository.findForDriverOn(to.getId(), trip.getTripDate()).stream()
                .anyMatch(t -> t.getLeg() == trip.getLeg() && t.getStatus() != TripStatus.CANCELLED
                        && !t.getVehicle().getId().equals(trip.getVehicle().getId()));
        if (busyElsewhere) {
            throw BusinessException.conflict(to.getUser().getFirstName() + " already drives another vehicle on that run");
        }
        DriverProfile from = trip.getDriver();
        trip.setDriver(to);
        auditService.record(user, "TRIP_REASSIGNED", "Trip", trip.getId(), from.getId() + " -> " + to.getId());
        notificationService.notify(to.getUser(), "TRIP", "Extra run for you",
                "Please drive the " + trip.getSchool().getName() + " " + legName(trip) + " run on " + trip.getTripDate() + ".",
                trip.getId());
        notificationService.notify(from.getUser(), "TRIP", "Run covered",
                to.getUser().getFirstName() + " is covering your " + trip.getSchool().getName() + " " + legName(trip) + " run.",
                trip.getId());
        trip.getPassengers().stream().map(p -> p.getChild().getParent()).distinct()
                .forEach(parent -> householdService.notifyFamily(parent, "TRIP", "Different driver for this run",
                        to.getUser().getFullName() + " is driving instead of " + from.getUser().getFirstName()
                                + " (" + legName(trip) + ", " + trip.getTripDate() + ").", trip.getId()));
        return TripResponse.from(trip, p -> true);
    }

    private static String legName(Trip trip) {
        return trip.getLeg() == TripLeg.TO_SCHOOL ? "morning" : "afternoon";
    }

    private DriverProfile requireTeamManager(User user) {
        DriverProfile me = driverService.requireProfile(user);
        if (!me.isInTeam() || !me.managesRoutes()) {
            throw new UnauthorizedException("Only a team's owner or managers can do this");
        }
        return me;
    }

    @Transactional
    public TripResponse start(User user, UUID tripId) {
        Trip trip = requireDriverTrip(user, tripId);
        if (trip.getStatus() != TripStatus.SCHEDULED) {
            throw BusinessException.conflict("This trip is already " + trip.getStatus());
        }
        if (!trip.getTripDate().equals(LocalDate.now(clock))) {
            throw BusinessException.conflict("Only today's trips can be started");
        }
        trip.setStatus(TripStatus.IN_PROGRESS);
        trip.setStartedAt(Instant.now(clock));
        String message = trip.getLeg() == TripLeg.TO_SCHOOL
                ? "is on the way to collect %s for school."
                : "is heading to " + trip.getSchool().getName() + " to collect %s.";
        trip.getPassengers().stream()
                .filter(p -> p.getStatus() == PassengerStatus.EXPECTED)
                .forEach(p -> notifyFamily(p, "Trip started",
                        trip.getDriver().getUser().getFirstName() + " " + message.formatted(p.getChild().getFirstName())));
        return TripResponse.from(trip, p -> true);
    }

    @Transactional
    public void updateLocation(User user, UUID tripId, LocationRequest request) {
        Trip trip = requireDriverTrip(user, tripId);
        if (trip.getStatus() != TripStatus.IN_PROGRESS) {
            throw BusinessException.conflict("Location is only shared while a trip is in progress");
        }
        trip.setLastLatitude(request.latitude());
        trip.setLastLongitude(request.longitude());
        trip.setLastLocationAt(Instant.now(clock));
    }

    /**
     * The driver's side of the pickup. The other side confirms separately: the family (morning, at home)
     * or school staff (afternoon, at the gate).
     */
    @Transactional
    public TripResponse pickup(User user, UUID tripId, UUID passengerId) {
        Trip trip = requireDriverTrip(user, tripId);
        requireInProgress(trip);
        TripPassenger passenger = requirePassenger(trip, passengerId);
        if (passenger.getStatus() != PassengerStatus.EXPECTED) {
            throw BusinessException.conflict(passenger.getChild().getFirstName() + " is already " + passenger.getStatus());
        }
        passenger.setStatus(PassengerStatus.PICKED_UP);
        passenger.setPickedUpAt(Instant.now(clock));
        String name = passenger.getChild().getFirstName();
        if (trip.getLeg() == TripLeg.TO_SCHOOL) {
            notifyFamily(passenger, "Picked up — please confirm",
                    driverName(trip) + " says " + name + " is in the vehicle (" + nowTime() + "). Tap to confirm you handed "
                            + name + " over.");
        } else {
            boolean released = passenger.getPickupConfirmedAt() != null;
            if (!released) {
                // Not blocked (not every school uses the app at the gate), but recorded
                auditService.record(user, "PICKUP_WITHOUT_SCHOOL_RELEASE", "TripPassenger", passenger.getId(), null);
            }
            notifyFamily(passenger, "Collected from school",
                    name + " was collected from " + trip.getSchool().getName() + " by " + driverName(trip) + " at " + nowTime()
                            + (released ? "." : " (the school hasn't confirmed the release yet)."));
        }
        return TripResponse.from(trip, p -> true);
    }

    /** handedTo: who received the child at home, e.g. "Mother", "Helper (Mpho)". Required for home drop-offs. */
    @Transactional
    public TripResponse dropoff(User user, UUID tripId, UUID passengerId, String handedTo) {
        Trip trip = requireDriverTrip(user, tripId);
        requireInProgress(trip);
        TripPassenger passenger = requirePassenger(trip, passengerId);
        if (passenger.getStatus() != PassengerStatus.PICKED_UP) {
            throw BusinessException.conflict(passenger.getChild().getFirstName() + " hasn't been picked up");
        }
        boolean atHome = trip.getLeg() == TripLeg.FROM_SCHOOL;
        if (atHome && (handedTo == null || handedTo.isBlank())) {
            throw BusinessException.badRequest("Say who received " + passenger.getChild().getFirstName() + " at home");
        }
        passenger.setStatus(PassengerStatus.DROPPED_OFF);
        passenger.setDroppedOffAt(Instant.now(clock));
        passenger.setHandedTo(handedTo != null && !handedTo.isBlank() ? handedTo.trim() : null);
        String name = passenger.getChild().getFirstName();
        if (atHome) {
            notifyFamily(passenger, "Dropped off — please confirm",
                    driverName(trip) + " dropped " + name + " at home at " + nowTime() + ", handed to " + passenger.getHandedTo()
                            + ". Tap to confirm " + name + " is home.");
        } else {
            notifyFamily(passenger, "Arrived at school",
                    name + " was dropped at " + trip.getSchool().getName() + " at " + nowTime() + ".");
        }
        return TripResponse.from(trip, p -> true);
    }

    @Transactional
    public TripResponse markAbsent(User user, UUID tripId, UUID passengerId) {
        Trip trip = requireDriverTrip(user, tripId);
        TripPassenger passenger = requirePassenger(trip, passengerId);
        if (passenger.getStatus() != PassengerStatus.EXPECTED) {
            throw BusinessException.conflict(passenger.getChild().getFirstName() + " is already " + passenger.getStatus());
        }
        passenger.setStatus(PassengerStatus.ABSENT);
        notifyFamily(passenger, "Not picked up",
                trip.getDriver().getUser().getFirstName() + " marked " + passenger.getChild().getFirstName()
                        + " as absent for the " + legName(trip.getLeg()) + " trip. Call the driver if this is wrong.");
        return TripResponse.from(trip, p -> true);
    }

    @Transactional
    public TripResponse complete(User user, UUID tripId) {
        Trip trip = requireDriverTrip(user, tripId);
        requireInProgress(trip);
        List<String> stillOnBoard = trip.getPassengers().stream()
                .filter(p -> p.getStatus() == PassengerStatus.PICKED_UP)
                .map(p -> p.getChild().getFirstName()).toList();
        if (!stillOnBoard.isEmpty()) {
            throw BusinessException.conflict("Still marked as in the vehicle: " + String.join(", ", stillOnBoard));
        }
        trip.getPassengers().stream()
                .filter(p -> p.getStatus() == PassengerStatus.EXPECTED)
                .forEach(p -> p.setStatus(PassengerStatus.ABSENT));
        trip.setStatus(TripStatus.COMPLETED);
        trip.setCompletedAt(Instant.now(clock));
        if (trip.getAdHocRequest() != null) {
            trip.getAdHocRequest().setStatus(AdHocStatus.COMPLETED);
        }
        return TripResponse.from(trip, p -> true);
    }

    // ─── Parent / kid ────────────────────────────────────────────────────────

    /** Today's trips carrying the family's children (only their own children are shown). */
    @Transactional(readOnly = true)
    public List<TripResponse> parentTrips(User owner) {
        Set<UUID> childIds = childRepository.findByParentId(owner.getId()).stream()
                .map(Child::getId).collect(Collectors.toSet());
        return tripsCarrying(childIds);
    }

    @Transactional(readOnly = true)
    public List<TripResponse> kidTrips(User kid) {
        Child child = requireKidChild(kid);
        return tripsCarrying(Set.of(child.getId()));
    }

    /** The kid's own confirmation — a second, independent signal next to the driver's. */
    @Transactional
    public TripResponse kidConfirm(User kid, UUID passengerId, boolean pickup) {
        Child child = requireKidChild(kid);
        TripPassenger passenger = passengerRepository.findWithTrip(passengerId)
                .filter(p -> p.getChild().getId().equals(child.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));
        if (!passenger.getTrip().getTripDate().equals(LocalDate.now(clock))) {
            throw BusinessException.conflict("You can only confirm today's trips");
        }
        if (pickup) {
            passenger.setKidConfirmedPickupAt(Instant.now(clock));
        } else {
            passenger.setKidConfirmedDropoffAt(Instant.now(clock));
        }
        notifyFamily(passenger, pickup ? "Pickup confirmed by " + child.getFirstName() : "Arrival confirmed by " + child.getFirstName(),
                child.getFirstName() + (pickup ? " confirmed they're in the vehicle" : " confirmed they've arrived")
                        + " at " + nowTime() + ".");
        return TripResponse.from(passenger.getTrip(), p -> p.getChild().getId().equals(child.getId()));
    }

    // ─── School ──────────────────────────────────────────────────────────────

    /** Who is collecting which child today — the gate list, including authorised once-off pickups. */
    @Transactional
    public List<TripResponse> schoolTrips(User staff, LocalDate date) {
        School school = schoolService.requireStaffSchool(staff);
        if (!school.isVerified()) {
            throw new UnauthorizedException("Your school has not been verified yet");
        }
        LocalDate day = date != null ? date : LocalDate.now(clock);
        return tripRepository.findAtSchoolOn(school.getId(), day).stream()
                .map(t -> TripResponse.from(t, p -> true)).toList();
    }

    /**
     * Any staff member at the school confirms their side: releasing a child to the authorised driver
     * (afternoon pickup) or receiving a child at school (morning drop-off).
     */
    @Transactional
    public TripResponse schoolConfirm(User staff, UUID passengerId, HandoverStage stage) {
        School school = schoolService.requireStaffSchool(staff);
        TripPassenger passenger = passengerRepository.findWithTrip(passengerId)
                .filter(p -> p.getTrip().getSchool().getId().equals(school.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Passenger not found at your school"));
        Trip trip = passenger.getTrip();
        requireToday(trip);
        boolean schoolSide = (stage == HandoverStage.PICKUP) == (trip.getLeg() == TripLeg.FROM_SCHOOL);
        if (!schoolSide) {
            throw BusinessException.badRequest("The family confirms this handover, not the school");
        }
        String name = passenger.getChild().getFirstName();
        if (stage == HandoverStage.PICKUP) {
            if (record(passenger, stage, staff)) {
                auditService.record(staff, "CHILD_RELEASED", "TripPassenger", passenger.getId(), "to driver " + trip.getDriver().getId());
                notifyFamily(passenger, "Released by school",
                        school.getName() + " (" + staff.getFullName() + ") released " + name + " to " + driverName(trip)
                                + " (" + trip.getVehicle().getPlateNumber() + ").");
            }
        } else if (record(passenger, stage, staff)) {
            notifyFamily(passenger, "Arrival confirmed",
                    school.getName() + " confirmed " + name + " arrived (" + staff.getFullName() + ", " + nowTime() + ").");
        }
        return TripResponse.from(trip, p -> true);
    }

    /** A family member confirms their side: handing over in the morning, receiving at home in the afternoon. */
    @Transactional
    public TripResponse familyConfirm(User member, User owner, UUID passengerId, HandoverStage stage) {
        TripPassenger passenger = requireFamilyPassenger(owner, passengerId);
        Trip trip = passenger.getTrip();
        requireToday(trip);
        boolean familySide = (stage == HandoverStage.PICKUP) == (trip.getLeg() == TripLeg.TO_SCHOOL);
        if (!familySide) {
            throw BusinessException.badRequest("The school confirms this handover, not the family");
        }
        if (stage == HandoverStage.DROPOFF && passenger.getDroppedOffAt() == null) {
            throw BusinessException.conflict("The driver hasn't marked " + passenger.getChild().getFirstName() + " as dropped off yet");
        }
        if (record(passenger, stage, member)) {
            notificationService.notify(trip.getDriver().getUser(), "TRIP", "Handover confirmed",
                    member.getFullName() + " confirmed " + passenger.getChild().getFirstName()
                            + (stage == HandoverStage.PICKUP ? " was handed to you." : " is home."), trip.getId());
        }
        return TripResponse.from(trip, p -> p.getChild().getParent().getId().equals(owner.getId()));
    }

    /** "That's not right" from the family or the school: support is alerted straight away. */
    @Transactional
    public TripResponse dispute(User reporter, UUID passengerId, String note, User familyOwner) {
        TripPassenger passenger = familyOwner != null
                ? requireFamilyPassenger(familyOwner, passengerId)
                : passengerRepository.findWithTrip(passengerId)
                        .filter(p -> p.getTrip().getSchool().getId().equals(schoolService.requireStaffSchool(reporter).getId()))
                        .orElseThrow(() -> new ResourceNotFoundException("Passenger not found at your school"));
        Trip trip = passenger.getTrip();
        passenger.setDisputedAt(Instant.now(clock));
        passenger.setDisputedBy(reporter);
        passenger.setDisputeNote(note);
        String name = passenger.getChild().getFullName();
        incidentRepository.save(Incident.builder()
                .reporter(reporter)
                .trip(trip)
                .driver(trip.getDriver())
                .type(IncidentType.SAFETY_CONCERN)
                .description("Handover disputed for " + name + ": " + (note != null ? note : "no details"))
                .build());
        notificationService.notifyStaff(StaffPermission.SUPPORT, "SOS", "Handover disputed",
                reporter.getFullName() + " says the handover for " + name + " is wrong. Call them now.", trip.getId());
        notifyFamily(passenger, "Problem reported",
                reporter.getFullName() + " reported a problem with " + passenger.getChild().getFirstName()
                        + "'s handover. SchoolDrop support has been alerted.");
        return TripResponse.from(trip, p -> true);
    }

    /** Families who haven't confirmed a home drop-off ~20 minutes later get one reminder. */
    @Transactional
    public int remindUnconfirmedDropoffs() {
        Instant now = Instant.now(clock);
        List<TripPassenger> waiting = passengerRepository.findUnconfirmedHomeDropoffs(
                now.minus(Duration.ofMinutes(25)), now.minus(Duration.ofMinutes(20)));
        waiting.forEach(p -> notifyFamily(p, "Is " + p.getChild().getFirstName() + " home?",
                p.getTrip().getDriver().getUser().getFirstName() + " dropped " + p.getChild().getFirstName()
                        + " off 20 minutes ago (handed to " + p.getHandedTo() + "). Please confirm, or report a problem."));
        return waiting.size();
    }

    private boolean record(TripPassenger passenger, HandoverStage stage, User by) {
        if (stage == HandoverStage.PICKUP) {
            if (passenger.getPickupConfirmedAt() != null) return false; // first confirmation wins
            passenger.setPickupConfirmedAt(Instant.now(clock));
            passenger.setPickupConfirmedBy(by);
        } else {
            if (passenger.getDropoffConfirmedAt() != null) return false;
            passenger.setDropoffConfirmedAt(Instant.now(clock));
            passenger.setDropoffConfirmedBy(by);
        }
        return true;
    }

    private TripPassenger requireFamilyPassenger(User owner, UUID passengerId) {
        return passengerRepository.findWithTrip(passengerId)
                .filter(p -> p.getChild().getParent().getId().equals(owner.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));
    }

    private void requireToday(Trip trip) {
        if (!trip.getTripDate().equals(LocalDate.now(clock))) {
            throw BusinessException.conflict("Only today's handovers can be confirmed");
        }
        if (trip.getStatus() == TripStatus.CANCELLED) {
            throw BusinessException.conflict("This trip was cancelled");
        }
    }

    private static String driverName(Trip trip) {
        return trip.getDriver().getUser().getFullName();
    }

    public enum HandoverStage {
        PICKUP,
        DROPOFF
    }

    // ─── Admin ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TripResponse> liveTrips() {
        return tripRepository.findInProgress().stream().map(t -> TripResponse.from(t, p -> true)).toList();
    }

    // ─── Used by AdHocService ────────────────────────────────────────────────

    @Transactional
    public Trip createAdHocTrip(AdHocRequest adHoc) {
        Trip trip = Trip.builder()
                .adHocRequest(adHoc)
                .driver(adHoc.getDriver())
                .vehicle(adHoc.getOffer().getVehicle())
                .school(adHoc.getSchool())
                .tripDate(LocalDate.ofInstant(adHoc.getPickupAt(), clock.getZone()))
                .leg(TripLeg.FROM_SCHOOL)
                .build();
        trip.getPassengers().add(TripPassenger.builder().trip(trip).child(adHoc.getChild()).build());
        return tripRepository.save(trip);
    }

    @Transactional
    public void cancelAdHocTrip(AdHocRequest adHoc) {
        tripRepository.findByAdHocRequestId(adHoc.getId())
                .filter(t -> t.getStatus() == TripStatus.SCHEDULED)
                .ifPresent(t -> t.setStatus(TripStatus.CANCELLED));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Creates the day's trips for the driver's offers and adds anyone booked since. */
    private void generateTrips(DriverProfile driver, LocalDate day) {
        if (!BookingPricing.isSchoolDay(day)) {
            return;
        }
        for (TransportOffer offer : offerRepository.findByDriverId(driver.getId())) {
            List<Booking> riders = bookingRepository.findRidingOn(offer.getId(), day);
            if (riders.isEmpty()) {
                continue;
            }
            for (TripLeg leg : TripLeg.values()) {
                if (!offer.getCoverage().includes(leg)) {
                    continue;
                }
                Trip trip = tripRepository.findByOfferIdAndTripDateAndLeg(offer.getId(), day, leg)
                        .orElseGet(() -> tripRepository.save(Trip.builder()
                                .offer(offer)
                                .driver(driver)
                                .vehicle(offer.getVehicle())
                                .school(offer.getSchool())
                                .tripDate(day)
                                .leg(leg)
                                .build()));
                if (trip.getStatus() != TripStatus.SCHEDULED) {
                    continue;
                }
                Set<UUID> onBoard = trip.getPassengers().stream()
                        .map(p -> p.getChild().getId()).collect(Collectors.toSet());
                for (Booking booking : riders) {
                    if (!onBoard.contains(booking.getChild().getId())) {
                        trip.getPassengers().add(TripPassenger.builder()
                                .trip(trip).child(booking.getChild()).booking(booking).build());
                    }
                }
            }
        }
    }

    private List<TripResponse> tripsCarrying(Set<UUID> childIds) {
        if (childIds.isEmpty()) {
            return List.of();
        }
        return tripRepository.findCarryingChildrenOn(List.copyOf(childIds), LocalDate.now(clock)).stream()
                .map(t -> TripResponse.from(t, p -> childIds.contains(p.getChild().getId())))
                .toList();
    }

    private Trip requireDriverTrip(User user, UUID tripId) {
        DriverProfile driver = driverService.requireProfile(user);
        return tripRepository.findByIdAndDriverId(tripId, driver.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));
    }

    private static TripPassenger requirePassenger(Trip trip, UUID passengerId) {
        return trip.getPassengers().stream()
                .filter(p -> p.getId().equals(passengerId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Passenger not on this trip"));
    }

    private static void requireInProgress(Trip trip) {
        if (trip.getStatus() != TripStatus.IN_PROGRESS) {
            throw BusinessException.conflict("Start the trip first");
        }
    }

    private Child requireKidChild(User kid) {
        return childRepository.findByKidUserId(kid.getId())
                .filter(c -> c.getArchivedAt() == null)
                .orElseThrow(() -> new UnauthorizedException("This device is not paired with a child"));
    }

    private void notifyFamily(TripPassenger passenger, String title, String body) {
        householdService.notifyFamily(passenger.getChild().getParent(), "TRIP", title, body, passenger.getTrip().getId());
    }

    private String nowTime() {
        return LocalTime.now(clock).format(TIME);
    }

    private static String legName(TripLeg leg) {
        return leg == TripLeg.TO_SCHOOL ? "morning" : "afternoon";
    }
}

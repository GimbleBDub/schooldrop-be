package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.OfferRequest;
import com.gimble.bus_stop.dto.responses.OfferResponse;
import com.gimble.bus_stop.entities.Child;
import com.gimble.bus_stop.entities.DriverProfile;
import com.gimble.bus_stop.entities.TransportOffer;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.entities.Vehicle;
import com.gimble.bus_stop.enums.TripLeg;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.exceptions.UnauthorizedException;
import com.gimble.bus_stop.repositories.BookingRepository;
import com.gimble.bus_stop.repositories.TransportOfferRepository;
import com.gimble.bus_stop.repositories.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OfferService {

    private static final LocalDate FAR_FUTURE = LocalDate.of(2999, 12, 31);

    private final TransportOfferRepository offerRepository;
    private final VehicleRepository vehicleRepository;
    private final BookingRepository bookingRepository;
    private final DriverService driverService;
    private final ChildService childService;
    private final AuditService auditService;
    private final AdvertService advertService;
    private final com.gimble.bus_stop.repositories.TripRepository tripRepository;
    private final NotificationService notificationService;
    private final HouseholdService householdService;
    private final Clock clock;

    // ─── Driver ──────────────────────────────────────────────────────────────

    @Transactional
    public OfferResponse create(User user, OfferRequest request) {
        DriverProfile manager = driverService.requireApproved(user);
        requireRoutes(manager);
        DriverProfile driver = assignedDriver(manager, request.driverId(), null);
        TransportOffer offer = TransportOffer.builder().driver(driver).build();
        apply(manager, offer, request);
        offer = offerRepository.save(offer);
        auditService.record(user, "OFFER_CREATED", "TransportOffer", offer.getId(), null);
        return toResponse(offer);
    }

    @Transactional
    public OfferResponse update(User user, UUID offerId, OfferRequest request) {
        DriverProfile manager = driverService.requireProfile(user);
        requireRoutes(manager);
        TransportOffer offer = offerRepository.findById(offerId)
                .filter(o -> o.getDriver().getId().equals(manager.getId()) || manager.isTeamMate(o.getDriver()))
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found"));
        if (!Objects.equals(offer.getSchool().getId(), request.schoolId())) {
            throw BusinessException.badRequest("An offer's school can't change — create a new offer instead");
        }
        if (Boolean.TRUE.equals(request.active()) && !manager.isApproved()) {
            throw BusinessException.conflict("Your driver account must be approved to activate offers");
        }
        long held = bookingRepository.countSeatsHeld(offer.getId(), LocalDate.now(clock), FAR_FUTURE);
        if (request.capacity() < held) {
            throw BusinessException.conflict(held + " seats are already booked on this offer");
        }
        DriverProfile driver = assignedDriver(manager, request.driverId(), offer.getDriver());
        if (!driver.getId().equals(offer.getDriver().getId())) {
            reassign(user, offer, driver);
        }
        apply(manager, offer, request);
        auditService.record(user, "OFFER_UPDATED", "TransportOffer", offer.getId(), null);
        return toResponse(offer);
    }

    @Transactional(readOnly = true)
    public List<OfferResponse> listMine(User user) {
        DriverProfile driver = driverService.requireProfile(user);
        // Team owners / managers see every route of the team; drivers see the ones they drive
        List<TransportOffer> offers = driver.isInTeam() && driver.managesRoutes()
                ? offerRepository.findByOperatorId(driver.getOperator().getId())
                : offerRepository.findByDriverId(driver.getId());
        return offers.stream().map(this::toResponse).toList();
    }

    // ─── Parent ──────────────────────────────────────────────────────────────

    /** Offers at the child's school, for a child whose enrollment the school confirmed. */
    @Transactional(readOnly = true)
    public List<OfferResponse> searchForChild(User parent, UUID childId) {
        Child child = childService.requireOwned(parent, childId);
        if (child.getSchool() == null || !child.isEnrollmentConfirmed()) {
            throw BusinessException.badRequest("The school must confirm " + child.getFirstName() + "'s enrollment first");
        }
        LocalDate today = LocalDate.now(clock);
        Set<UUID> featured = advertService.featuredDriverIds(child.getSchool().getId());
        // A team's advert features all of its drivers
        return offerRepository.findBookableBySchool(child.getSchool().getId()).stream()
                // Drivers whose monthly fee lapsed are hidden from parents
                .filter(o -> o.getDriver().isSubscribedOn(today))
                .map(o -> toResponse(o, featured.contains(o.getDriver().getId())
                        || featured.contains(o.getDriver().business().getId())))
                .filter(o -> o.seatsAvailable() > 0)
                // Featured (advertising) drivers first
                .sorted(Comparator.comparing(OfferResponse::featured).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public OfferResponse get(UUID offerId) {
        return toResponse(offerRepository.findById(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found")));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void apply(DriverProfile manager, TransportOffer offer, OfferRequest request) {
        if (request.pricePerTrip() == null && request.pricePerMonth() == null && request.pricePerTerm() == null) {
            throw BusinessException.badRequest("Set at least one price: per trip, per month or per term");
        }
        // A team serves its schools together (held on the owner's profile)
        DriverProfile business = manager.business();
        boolean servesSchool = business.getSchools().stream().anyMatch(s -> s.getId().equals(request.schoolId()));
        if (!servesSchool) {
            throw BusinessException.badRequest("Add this school to the schools you serve first");
        }
        Vehicle vehicle = driverService.requireVehicle(manager, request.vehicleId());
        if (!vehicle.isActive()) {
            throw new ResourceNotFoundException("Vehicle not found");
        }
        if (!driverService.isVehicleVerified(vehicle)) {
            throw BusinessException.badRequest("This vehicle's documents haven't all been approved yet");
        }
        if (request.capacity() > vehicle.getSeatCapacity()) {
            throw BusinessException.badRequest("This vehicle only has " + vehicle.getSeatCapacity() + " seats");
        }
        boolean activating = request.active() == null || request.active();
        if (activating) {
            checkVehicleNotOverbooked(offer, vehicle, request);
        }

        offer.setVehicle(vehicle);
        offer.setSchool(business.getSchools().stream()
                .filter(s -> s.getId().equals(request.schoolId())).findFirst().orElseThrow());
        offer.setCoverage(request.coverage());
        offer.setCapacity(request.capacity());
        offer.setServiceArea(request.serviceArea().trim());
        offer.setMorningPickupTime(request.morningPickupTime());
        offer.setPricePerTrip(request.pricePerTrip());
        offer.setPricePerMonth(request.pricePerMonth());
        offer.setPricePerTerm(request.pricePerTerm());
        offer.setAdHocFare(request.adHocFare());
        offer.setActive(activating);
    }

    /**
     * A vehicle can serve several schools on one run, but the seats promised across those offers
     * on the same leg can't add up to more than the vehicle has.
     */
    private void checkVehicleNotOverbooked(TransportOffer offer, Vehicle vehicle, OfferRequest request) {
        for (TripLeg leg : TripLeg.values()) {
            if (!request.coverage().includes(leg)) {
                continue;
            }
            // Every route on this vehicle, whichever team driver runs it
            int othersOnLeg = offerRepository.findByVehicleId(vehicle.getId()).stream()
                    .filter(o -> o.isActive() && !o.getId().equals(offer.getId()))
                    .filter(o -> o.getCoverage().includes(leg))
                    .mapToInt(TransportOffer::getCapacity).sum();
            if (othersOnLeg + request.capacity() > vehicle.getSeatCapacity()) {
                throw BusinessException.badRequest("Your other offers already use " + othersOnLeg + " of this vehicle's "
                        + vehicle.getSeatCapacity() + " seats on the " + leg.name().toLowerCase().replace('_', ' ')
                        + " run");
            }
        }
    }

    /** Drivers on their own, and team owners / managers, set up routes; team drivers drive what they're given. */
    private static void requireRoutes(DriverProfile driver) {
        if (!driver.managesRoutes()) {
            throw new UnauthorizedException("Your team's owner or manager sets up routes");
        }
    }

    /** Who drives the route: someone in the team who drives and is approved (default: the manager themselves). */
    private DriverProfile assignedDriver(DriverProfile manager, UUID driverId, DriverProfile current) {
        DriverProfile driver;
        if (driverId == null) {
            driver = current != null ? current : manager;
        } else if (driverId.equals(manager.getId())) {
            driver = manager;
        } else {
            driver = driverService.requireDriver(driverId);
            if (!manager.isTeamMate(driver)) {
                throw new ResourceNotFoundException("Driver not found in your team");
            }
        }
        if (!driver.isDrives()) {
            throw BusinessException.badRequest(driver.getUser().getFirstName() + " doesn't drive — choose a driver for this route");
        }
        if (!driver.isApproved()) {
            throw BusinessException.conflict(driver.getUser().getFirstName() + " isn't verified by SchoolDrop yet");
        }
        return driver;
    }

    /**
     * Hands a route to another team driver: future runs (including today's, if not started) go to them,
     * and families see the new driver. Booked children keep their seats.
     */
    private void reassign(User actor, TransportOffer offer, DriverProfile to) {
        DriverProfile from = offer.getDriver();
        offer.setDriver(to);
        tripRepository.findScheduledForOfferFrom(offer.getId(), LocalDate.now(clock)).forEach(t -> t.setDriver(to));
        auditService.record(actor, "OFFER_REASSIGNED", "TransportOffer", offer.getId(), from.getId() + " -> " + to.getId());
        notificationService.notify(to.getUser(), "TRIP", "New route for you",
                "You now drive " + offer.getSchool().getName() + " (" + offer.getCoverage().name().toLowerCase().replace('_', ' ') + ").",
                offer.getId());
        notificationService.notify(from.getUser(), "TRIP", "Route handed over",
                to.getUser().getFirstName() + " now drives your " + offer.getSchool().getName() + " route.", offer.getId());
        bookingRepository.findCurrentForOffer(offer.getId(), LocalDate.now(clock)).stream()
                .map(b -> b.getParent()).distinct()
                .forEach(parent -> householdService.notifyFamily(parent, "TRIP", "New driver",
                        to.getUser().getFullName() + " now drives your child to " + offer.getSchool().getName()
                                + " for " + offer.getDriver().getOperator().getName() + ".", offer.getId()));
    }

    OfferResponse toResponse(TransportOffer offer) {
        return toResponse(offer, false);
    }

    OfferResponse toResponse(TransportOffer offer, boolean featured) {
        LocalDate today = LocalDate.now(clock);
        int held = (int) bookingRepository.countSeatsHeld(offer.getId(), today, today);
        return OfferResponse.from(offer, Math.max(0, offer.getCapacity() - held), featured);
    }
}

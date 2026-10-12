package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.AdHocCreateRequest;
import com.gimble.bus_stop.dto.responses.AdHocResponse;
import com.gimble.bus_stop.entities.*;
import com.gimble.bus_stop.enums.AdHocStatus;
import com.gimble.bus_stop.enums.StaffPermission;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.payments.PaymentSucceededEvent;
import com.gimble.bus_stop.repositories.AdHocRequestRepository;
import com.gimble.bus_stop.repositories.SchoolStaffRepository;
import com.gimble.bus_stop.repositories.TransportOfferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Once-off pickups from school: the parent posts a request, approved drivers serving that
 * school see it with their own fare, the first to accept gets it, and the parent pays to confirm.
 * Once confirmed, the school sees the driver as authorised to collect the child.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdHocService {

    private static final Duration MIN_NOTICE = Duration.ofMinutes(30);
    private static final Duration MAX_AHEAD = Duration.ofDays(7);
    // Cancelling a confirmed pickup closer than this to pickup time isn't refunded automatically
    private static final Duration FREE_CANCEL_BEFORE = Duration.ofHours(1);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");

    private final AdHocRequestRepository adHocRepository;
    private final TransportOfferRepository offerRepository;
    private final SchoolStaffRepository schoolStaffRepository;
    private final ChildService childService;
    private final DriverService driverService;
    private final PaymentService paymentService;
    private final TripService tripService;
    private final HouseholdService householdService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    // ─── Parent ──────────────────────────────────────────────────────────────

    @Transactional
    public AdHocResponse create(User parent, AdHocCreateRequest request) {
        Child child = childService.requireOwned(parent, request.childId());
        if (child.getSchool() == null || !child.isEnrollmentConfirmed()) {
            throw BusinessException.badRequest("The school must confirm " + child.getFirstName() + "'s enrollment first");
        }
        Instant now = Instant.now(clock);
        if (request.pickupAt().isBefore(now.plus(MIN_NOTICE))) {
            throw BusinessException.badRequest("Give drivers at least " + MIN_NOTICE.toMinutes() + " minutes' notice");
        }
        if (request.pickupAt().isAfter(now.plus(MAX_AHEAD))) {
            throw BusinessException.badRequest("Once-off pickups can be requested up to 7 days ahead");
        }
        LocalDate today = LocalDate.now(clock);
        List<TransportOffer> eligible = offerRepository.findAdHocEligibleBySchool(child.getSchool().getId()).stream()
                .filter(o -> o.getDriver().isSubscribedOn(today)).toList();
        if (eligible.isEmpty()) {
            throw BusinessException.conflict("No drivers at " + child.getSchool().getName() + " take once-off pickups yet");
        }

        AdHocRequest adHoc = adHocRepository.save(AdHocRequest.builder()
                .parent(parent)
                .child(child)
                .school(child.getSchool())
                .pickupAt(request.pickupAt())
                .dropoffAddress(request.dropoffAddress().trim())
                .dropoffLatitude(request.dropoffLatitude())
                .dropoffLongitude(request.dropoffLongitude())
                .notes(request.notes())
                .build());

        String when = format(adHoc.getPickupAt());
        eligible.stream().map(TransportOffer::getDriver).distinct().forEach(driver ->
                notificationService.notify(driver.getUser(), "ADHOC_REQUEST", "Once-off pickup request",
                        "Pickup at " + child.getSchool().getName() + ", " + when + ". Tap to accept.", adHoc.getId()));
        log.info("Ad-hoc request {} sent to {} drivers", adHoc.getId(), eligible.size());
        return AdHocResponse.from(adHoc);
    }

    @Transactional(readOnly = true)
    public List<AdHocResponse> listMine(User parent) {
        return adHocRepository.findByParentId(parent.getId()).stream().map(AdHocResponse::from).toList();
    }

    @Transactional
    public AdHocResponse cancelByParent(User parent, UUID requestId, String reason) {
        AdHocRequest adHoc = adHocRepository.findByIdAndParentId(requestId, parent.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Pickup request not found"));
        boolean refund = adHoc.getStatus() == AdHocStatus.CONFIRMED
                && Instant.now(clock).plus(FREE_CANCEL_BEFORE).isBefore(adHoc.getPickupAt());
        cancel(parent, adHoc, reason != null ? reason : "Cancelled by parent", refund);
        return AdHocResponse.from(adHoc);
    }

    // ─── Driver ──────────────────────────────────────────────────────────────

    /** Open requests at schools where this driver has an ad-hoc-enabled offer, priced at their fare. */
    @Transactional(readOnly = true)
    public List<AdHocResponse> openForDriver(User user) {
        DriverProfile driver = driverService.requireApproved(user);
        if (!driver.isAcceptsAdHoc() || !driver.isSubscribedOn(LocalDate.now(clock))) {
            return List.of();
        }
        Map<UUID, TransportOffer> offerBySchool = eligibleOffersBySchool(driver);
        if (offerBySchool.isEmpty()) {
            return List.of();
        }
        return adHocRepository.findOpenForSchools(offerBySchool.keySet(), Instant.now(clock)).stream()
                .map(r -> AdHocResponse.from(r, offerBySchool.get(r.getSchool().getId()).getAdHocFare()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdHocResponse> listForDriver(User user) {
        DriverProfile driver = driverService.requireProfile(user);
        return adHocRepository.findByDriverId(driver.getId()).stream().map(AdHocResponse::from).toList();
    }

    /** First driver to accept wins; the row lock makes concurrent accepts safe. */
    @Transactional
    public AdHocResponse accept(User user, UUID requestId) {
        DriverProfile driver = driverService.requireApproved(user);
        if (!driver.isSubscribedOn(LocalDate.now(clock))) {
            throw BusinessException.conflict("Pay your monthly fee to take once-off pickups");
        }
        AdHocRequest adHoc = adHocRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Pickup request not found"));
        if (adHoc.getStatus() != AdHocStatus.OPEN) {
            throw BusinessException.conflict("Another driver already took this pickup");
        }
        if (adHoc.getPickupAt().isBefore(Instant.now(clock))) {
            throw BusinessException.conflict("This pickup time has passed");
        }
        TransportOffer offer = Optional.ofNullable(eligibleOffersBySchool(driver).get(adHoc.getSchool().getId()))
                .orElseThrow(() -> BusinessException.badRequest("You don't take once-off pickups at this school"));

        adHoc.setStatus(AdHocStatus.ACCEPTED);
        adHoc.setDriver(driver);
        adHoc.setOffer(offer);
        adHoc.setFare(offer.getAdHocFare());
        adHoc.setAcceptedAt(Instant.now(clock));
        notificationService.notify(adHoc.getParent(), "ADHOC", "Driver found",
                driver.getUser().getFullName() + " can collect " + adHoc.getChild().getFirstName() + " for P"
                        + adHoc.getFare() + ". Pay now to confirm.", adHoc.getId());
        return AdHocResponse.from(adHoc);
    }

    /** A driver can back out until the parent has paid; the request goes back out to other drivers. */
    @Transactional
    public AdHocResponse release(User user, UUID requestId) {
        DriverProfile driver = driverService.requireProfile(user);
        AdHocRequest adHoc = adHocRepository.findByIdForUpdate(requestId)
                .filter(r -> isAssignedTo(r, driver))
                .orElseThrow(() -> new ResourceNotFoundException("Pickup request not found"));
        if (adHoc.getStatus() != AdHocStatus.ACCEPTED) {
            throw BusinessException.conflict("Once the parent has paid, cancel the pickup instead");
        }
        paymentService.cancelPendingForAdHoc(adHoc);
        adHoc.setStatus(AdHocStatus.OPEN);
        adHoc.setDriver(null);
        adHoc.setOffer(null);
        adHoc.setFare(null);
        adHoc.setAcceptedAt(null);
        notificationService.notify(adHoc.getParent(), "ADHOC", "Driver unavailable",
                "The driver can no longer do this pickup. We're looking for another driver.", adHoc.getId());
        return AdHocResponse.from(adHoc);
    }

    /** A confirmed pickup the driver can't do: the parent is refunded in full and support is told. */
    @Transactional
    public AdHocResponse cancelByDriver(User user, UUID requestId, String reason) {
        DriverProfile driver = driverService.requireProfile(user);
        AdHocRequest adHoc = adHocRepository.findByIdForUpdate(requestId)
                .filter(r -> isAssignedTo(r, driver))
                .orElseThrow(() -> new ResourceNotFoundException("Pickup request not found"));
        cancel(user, adHoc, reason, true);
        notificationService.notifyStaff(StaffPermission.SUPPORT, "ADHOC_DRIVER_CANCELLED",
                "Driver cancelled a once-off pickup",
                driver.getUser().getFullName() + " cancelled a confirmed pickup: " + reason, adHoc.getId());
        return AdHocResponse.from(adHoc);
    }

    // ─── Payment ─────────────────────────────────────────────────────────────

    @EventListener
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        Payment payment = event.payment();
        AdHocRequest adHoc = payment.getAdHocRequest();
        if (adHoc == null) {
            return;
        }
        if (adHoc.getStatus() != AdHocStatus.ACCEPTED || !isAssignedTo(adHoc, payment.getDriver())) {
            paymentService.refund(null, payment, "The pickup was no longer available when the payment arrived.");
            return;
        }
        adHoc.setStatus(AdHocStatus.CONFIRMED);
        tripService.createAdHocTrip(adHoc);

        String when = format(adHoc.getPickupAt());
        Vehicle vehicle = adHoc.getOffer().getVehicle();
        householdService.notifyFamily(adHoc.getParent(), "ADHOC", "Pickup confirmed",
                adHoc.getDriver().getUser().getFullName() + " will collect " + adHoc.getChild().getFirstName()
                        + " " + when + " in a " + vehicle.getDisplayName() + ".", adHoc.getId());
        notificationService.notify(adHoc.getDriver().getUser(), "ADHOC", "Pickup confirmed",
                "Collect " + adHoc.getChild().getFullName() + " from " + adHoc.getSchool().getName() + " " + when + ".",
                adHoc.getId());
        schoolStaffRepository.findBySchoolId(adHoc.getSchool().getId()).forEach(staff ->
                notificationService.notify(staff.getUser(), "AUTHORISED_PICKUP", "Authorised pickup",
                        adHoc.getChild().getFullName() + " will be collected " + when + " by "
                                + adHoc.getDriver().getUser().getFullName() + " (" + vehicle.getPlateNumber() + ").",
                        adHoc.getId()));
    }

    // ─── Jobs ────────────────────────────────────────────────────────────────

    @Transactional
    public int expireStale() {
        List<AdHocRequest> stale = adHocRepository.findByStatusInAndPickupAtBefore(
                List.of(AdHocStatus.OPEN, AdHocStatus.ACCEPTED), Instant.now(clock));
        for (AdHocRequest adHoc : stale) {
            adHoc.setStatus(AdHocStatus.EXPIRED);
            paymentService.cancelPendingForAdHoc(adHoc);
            notificationService.notify(adHoc.getParent(), "ADHOC", "Pickup request expired",
                    "No confirmed driver for " + adHoc.getChild().getFirstName() + "'s pickup.", adHoc.getId());
        }
        return stale.size();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void cancel(User actor, AdHocRequest adHoc, String reason, boolean refund) {
        if (adHoc.getStatus() != AdHocStatus.OPEN && adHoc.getStatus() != AdHocStatus.ACCEPTED
                && adHoc.getStatus() != AdHocStatus.CONFIRMED) {
            throw BusinessException.conflict("This pickup is already " + adHoc.getStatus());
        }
        boolean wasConfirmed = adHoc.getStatus() == AdHocStatus.CONFIRMED;
        adHoc.setStatus(AdHocStatus.CANCELLED);
        adHoc.setCancelReason(reason);
        paymentService.cancelPendingForAdHoc(adHoc);
        if (wasConfirmed) {
            tripService.cancelAdHocTrip(adHoc);
            if (refund) {
                paymentService.refundAllForAdHoc(actor, adHoc, reason);
            }
        }
        auditService.record(actor, "ADHOC_CANCELLED", "AdHocRequest", adHoc.getId(), reason);

        User driverUser = adHoc.getDriver() != null ? adHoc.getDriver().getUser() : null;
        if (driverUser != null && !driverUser.getId().equals(actor.getId())) {
            notificationService.notify(driverUser, "ADHOC", "Pickup cancelled",
                    "The pickup for " + adHoc.getChild().getFirstName() + " was cancelled.", adHoc.getId());
        }
        if (!adHoc.getParent().getId().equals(actor.getId())) {
            notificationService.notify(adHoc.getParent(), "ADHOC", "Pickup cancelled",
                    "The pickup for " + adHoc.getChild().getFirstName() + " was cancelled: " + reason
                            + (refund && wasConfirmed ? ". You will be refunded." : "."), adHoc.getId());
        }
    }

    private Map<UUID, TransportOffer> eligibleOffersBySchool(DriverProfile driver) {
        return offerRepository.findByDriverId(driver.getId()).stream()
                .filter(o -> o.isActive() && o.getAdHocFare() != null && o.getVehicle().isActive())
                // Several offers at one school: use the cheapest fare
                .collect(Collectors.toMap(o -> o.getSchool().getId(), Function.identity(),
                        (a, b) -> a.getAdHocFare().compareTo(b.getAdHocFare()) <= 0 ? a : b));
    }

    private static boolean isAssignedTo(AdHocRequest adHoc, DriverProfile driver) {
        return adHoc.getDriver() != null && adHoc.getDriver().getId().equals(driver.getId());
    }

    private String format(Instant instant) {
        return "on " + WHEN.format(instant.atZone(clock.getZone()));
    }
}

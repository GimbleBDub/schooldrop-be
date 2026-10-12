package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.BookingRequest;
import com.gimble.bus_stop.dto.responses.BookingResponse;
import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.entities.*;
import com.gimble.bus_stop.enums.BookingStatus;
import com.gimble.bus_stop.enums.PaymentMode;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.payments.PaymentSucceededEvent;
import com.gimble.bus_stop.repositories.BookingRepository;
import com.gimble.bus_stop.repositories.SchoolTermRepository;
import com.gimble.bus_stop.repositories.TransportOfferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private static final int MAX_PER_TRIP_DAYS = 31;

    private final BookingRepository bookingRepository;
    private final TransportOfferRepository offerRepository;
    private final SchoolTermRepository termRepository;
    private final ChildService childService;
    private final DriverService driverService;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;

    @Value("${app.bookings.hold-minutes}")
    private int holdMinutes;

    @Value("${app.bookings.offline-hold-hours}")
    private int offlineHoldHours;

    // ─── Parent ──────────────────────────────────────────────────────────────

    /** Reserves a seat for holdMinutes; the parent then pays to make it ACTIVE. */
    @Transactional
    public BookingResponse create(User parent, BookingRequest request) {
        Child child = childService.requireOwned(parent, request.childId());
        if (child.getSchool() == null || !child.isEnrollmentConfirmed()) {
            throw BusinessException.badRequest("The school must confirm " + child.getFirstName() + "'s enrollment first");
        }

        // Lock the offer row: seat counting below must not interleave with another booking
        TransportOffer offer = offerRepository.findByIdForUpdate(request.offerId())
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found"));
        if (!offer.isActive() || !offer.getDriver().isApproved() || !offer.getVehicle().isActive()
                || !offer.getDriver().isSubscribedOn(LocalDate.now(clock))) {
            throw BusinessException.conflict("This driver is not taking bookings right now");
        }
        if (!offer.getSchool().getId().equals(child.getSchool().getId())) {
            throw BusinessException.badRequest("This driver doesn't serve " + child.getFirstName() + "'s school");
        }
        BigDecimal price = offer.priceFor(request.plan());
        if (price == null) {
            throw BusinessException.badRequest("This driver doesn't offer " + request.plan() + " billing");
        }

        LocalDate today = LocalDate.now(clock);
        Booking booking = Booking.builder()
                .child(child)
                .parent(parent)
                .offer(offer)
                .plan(request.plan())
                .pickupAddress(request.pickupAddress().trim())
                .pickupLatitude(request.pickupLatitude())
                .pickupLongitude(request.pickupLongitude())
                .notes(request.notes())
                .paymentMode(request.paymentMode() != null ? request.paymentMode() : PaymentMode.APP)
                .build();
        booking.setHoldExpiresAt(Instant.now(clock).plus(booking.getPaymentMode() == PaymentMode.OFFLINE
                ? Duration.ofHours(offlineHoldHours) : Duration.ofMinutes(holdMinutes)));

        switch (request.plan()) {
            case MONTHLY -> {
                YearMonth month = require(request.month(), "month");
                if (month.isBefore(YearMonth.from(today))) {
                    throw BusinessException.badRequest("That month has already passed");
                }
                booking.setStartDate(max(month.atDay(1), today));
                booking.setEndDate(month.atEndOfMonth());
                booking.setAmount(price);
            }
            case TERMLY -> {
                SchoolTerm term = termRepository.findById(require(request.termId(), "termId"))
                        .orElseThrow(() -> new ResourceNotFoundException("Term not found"));
                if (term.getEndDate().isBefore(today)) {
                    throw BusinessException.badRequest("That term has already ended");
                }
                booking.setTerm(term);
                booking.setStartDate(max(term.getStartDate(), today));
                booking.setEndDate(term.getEndDate());
                booking.setAmount(price);
            }
            case PER_TRIP -> {
                LocalDate start = require(request.startDate(), "startDate");
                LocalDate end = require(request.endDate(), "endDate");
                if (start.isBefore(today) || end.isBefore(start)) {
                    throw BusinessException.badRequest("Choose dates from today onwards, ending after the start");
                }
                if (start.plusDays(MAX_PER_TRIP_DAYS).isBefore(end)) {
                    throw BusinessException.badRequest("Per-trip bookings can cover at most " + MAX_PER_TRIP_DAYS
                            + " days — choose monthly or termly for longer");
                }
                int trips = BookingPricing.tripCount(start, end, offer.getCoverage());
                if (trips == 0) {
                    throw BusinessException.badRequest("There are no school days in those dates");
                }
                booking.setStartDate(start);
                booking.setEndDate(end);
                booking.setTripCount(trips);
                booking.setAmount(BookingPricing.perTripAmount(price, trips));
            }
        }

        if (bookingRepository.existsOverlapping(child.getId(), offer.getId(), booking.getStartDate(), booking.getEndDate())) {
            throw BusinessException.conflict(child.getFirstName() + " already has a booking with this driver for those dates");
        }
        long held = bookingRepository.countSeatsHeld(offer.getId(), booking.getStartDate(), booking.getEndDate());
        if (held >= offer.getCapacity()) {
            throw BusinessException.conflict("Sorry, this driver is full for those dates");
        }

        booking = bookingRepository.save(booking);
        if (booking.getPaymentMode() == PaymentMode.OFFLINE) {
            notificationService.notify(offer.getDriver().getUser(), "BOOKING", "New booking — paying you directly",
                    parent.getFullName() + " booked " + child.getFirstName() + " (P" + booking.getAmount()
                            + ") and will pay you directly. Confirm in the app once you've received the money.",
                    booking.getId());
        }
        log.info("Booking {} created for child {} on offer {}", booking.getId(), child.getId(), offer.getId());
        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listMine(User parent) {
        return bookingRepository.findByParentId(parent.getId()).stream().map(BookingResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse getMine(User parent, UUID bookingId) {
        return BookingResponse.from(requireOwned(parent, bookingId));
    }

    /**
     * Cancelling before the service starts refunds in full. Once it has started the seat is released
     * but refunds go through support.
     */
    @Transactional
    public BookingResponse cancelByParent(User parent, UUID bookingId, String reason) {
        Booking booking = requireOwned(parent, bookingId);
        boolean beforeStart = LocalDate.now(clock).isBefore(booking.getStartDate());
        cancel(parent, booking, reason != null ? reason : "Cancelled by parent", beforeStart);
        return BookingResponse.from(booking);
    }

    /** The parent says they've paid the driver directly; the driver is asked to confirm. */
    @Transactional
    public BookingResponse claimOfflinePaid(User parent, UUID bookingId) {
        Booking booking = requireOwned(parent, bookingId);
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT || booking.getPaymentMode() != PaymentMode.OFFLINE) {
            throw BusinessException.conflict("This booking isn't waiting for a direct payment");
        }
        booking.setOfflinePaidClaimedAt(Instant.now(clock));
        notifyDriverSide(booking, "BOOKING", "Confirm payment received",
                parent.getFullName() + " says they paid you P" + booking.getAmount() + " for " + booking.getChild().getFirstName()
                        + ". Confirm it in the app to activate the booking.", booking.getId());
        return BookingResponse.from(booking);
    }

    // ─── Driver ──────────────────────────────────────────────────────────────

    /**
     * The driver confirms they received a direct (cash / EFT) payment: the booking becomes active.
     * In a team, the owner or a manager can confirm too (the money may have gone to the company).
     */
    @Transactional
    public BookingResponse confirmOfflinePayment(User user, UUID bookingId, boolean received) {
        DriverProfile driver = driverService.requireProfile(user);
        Booking booking = bookingRepository.findById(bookingId)
                .filter(b -> b.getOffer().getDriver().getId().equals(driver.getId())
                        || (driver.managesRoutes() && driver.isTeamMate(b.getOffer().getDriver())))
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT || booking.getPaymentMode() != PaymentMode.OFFLINE) {
            throw BusinessException.conflict("This booking isn't waiting for a direct payment");
        }
        if (received) {
            paymentService.recordOfflineBookingPayment(booking);
        } else {
            booking.setOfflinePaidClaimedAt(null);
            notificationService.notify(booking.getParent(), "BOOKING", "Payment not received",
                    driver.getUser().getFullName() + " hasn't received your payment for " + booking.getChild().getFirstName()
                            + " yet. Please check with them — the seat is held until " + booking.getHoldExpiresAt() + ".",
                    booking.getId());
        }
        return BookingResponse.from(booking);
    }


    @Transactional(readOnly = true)
    public List<BookingResponse> listForDriver(User user, BookingStatus status) {
        DriverProfile driver = driverService.requireProfile(user);
        // Team owners / managers see the whole team's passengers
        List<Booking> bookings = driver.isInTeam() && driver.managesRoutes()
                ? bookingRepository.findByOperatorId(driver.getOperator().getId(), status)
                : bookingRepository.findByDriverId(driver.getId(), status);
        return bookings.stream().map(BookingResponse::from).toList();
    }

    /** The driver, and — in a team — the owner who runs the business. */
    private void notifyDriverSide(Booking booking, String type, String title, String body, UUID referenceId) {
        DriverProfile driver = booking.getOffer().getDriver();
        notificationService.notify(driver.getUser(), type, title, body, referenceId);
        DriverProfile business = driver.business();
        if (!business.getId().equals(driver.getId())) {
            notificationService.notify(business.getUser(), type, title + " · " + driver.getUser().getFirstName(), body, referenceId);
        }
    }

    // ─── Admin ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> adminSearch(BookingStatus status, Pageable pageable) {
        return PageResponse.from(bookingRepository.adminSearch(status, pageable).map(BookingResponse::from));
    }

    @Transactional
    public BookingResponse adminCancel(User admin, UUID bookingId, String reason, boolean refund) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        cancel(admin, booking, reason, refund);
        return BookingResponse.from(booking);
    }

    // ─── Payment ─────────────────────────────────────────────────────────────

    @EventListener
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        Payment payment = event.payment();
        Booking booking = payment.getBooking();
        if (booking == null) {
            return;
        }
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            // Seat was released (expired/cancelled) before the money arrived
            paymentService.refund(null, payment, "The booking was no longer open when the payment arrived.");
            return;
        }
        booking.setStatus(BookingStatus.ACTIVE);
        booking.setHoldExpiresAt(null);
        notificationService.notify(booking.getParent(), "BOOKING", "Booking confirmed",
                booking.getChild().getFirstName() + "'s transport to " + booking.getOffer().getSchool().getName()
                        + " is confirmed from " + booking.getStartDate() + ".", booking.getId());
        notifyDriverSide(booking, "BOOKING", "New passenger",
                booking.getChild().getFullName() + " booked your " + booking.getOffer().getSchool().getName()
                        + " run from " + booking.getStartDate() + ".", booking.getId());
    }

    // ─── Jobs ────────────────────────────────────────────────────────────────

    /** Releases seats held by bookings that weren't paid in time. */
    @Transactional
    public int expireUnpaidHolds() {
        List<Booking> expired = bookingRepository.findByStatusAndHoldExpiresAtBefore(
                BookingStatus.PENDING_PAYMENT, Instant.now(clock));
        for (Booking booking : expired) {
            booking.setStatus(BookingStatus.EXPIRED);
            paymentService.cancelPendingForBooking(booking);
            notificationService.notify(booking.getParent(), "BOOKING", "Booking expired",
                    "Your booking for " + booking.getChild().getFirstName() + " expired because it wasn't paid in time.",
                    booking.getId());
        }
        return expired.size();
    }

    @Transactional
    public int completeFinished() {
        List<Booking> finished = bookingRepository.findByStatusAndEndDateBefore(BookingStatus.ACTIVE, LocalDate.now(clock));
        finished.forEach(b -> b.setStatus(BookingStatus.COMPLETED));
        return finished.size();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void cancel(User actor, Booking booking, String reason, boolean refund) {
        if (!booking.getStatus().holdsSeat()) {
            throw BusinessException.conflict("This booking is already " + booking.getStatus());
        }
        boolean wasActive = booking.getStatus() == BookingStatus.ACTIVE;
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelReason(reason);
        booking.setCancelledAt(Instant.now(clock));
        paymentService.cancelPendingForBooking(booking);
        if (refund) {
            paymentService.refundAllForBooking(actor, booking, reason);
        }
        auditService.record(actor, "BOOKING_CANCELLED", "Booking", booking.getId(),
                reason + (refund ? " (refunded)" : ""));

        if (wasActive) {
            notifyDriverSide(booking, "BOOKING", "Booking cancelled",
                    booking.getChild().getFirstName() + "'s booking was cancelled.", booking.getId());
        }
        if (!actor.getId().equals(booking.getParent().getId())) {
            notificationService.notify(booking.getParent(), "BOOKING", "Booking cancelled",
                    "Your booking for " + booking.getChild().getFirstName() + " was cancelled: " + reason
                            + (refund ? ". You will be refunded." : "."), booking.getId());
        }
    }

    private Booking requireOwned(User parent, UUID bookingId) {
        return bookingRepository.findByIdAndParentId(bookingId, parent.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw BusinessException.badRequest(field + " is required for this plan");
        }
        return value;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}

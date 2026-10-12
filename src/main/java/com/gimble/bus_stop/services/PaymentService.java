package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.PayRequest;
import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.dto.responses.PaymentResponse;
import com.gimble.bus_stop.entities.*;
import com.gimble.bus_stop.enums.*;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.payments.PaymentGateway;
import com.gimble.bus_stop.payments.PaymentSucceededEvent;
import com.gimble.bus_stop.repositories.AdHocRequestRepository;
import com.gimble.bus_stop.repositories.BookingRepository;
import com.gimble.bus_stop.repositories.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    // A gateway that never reports back shouldn't leave payments PENDING forever
    private static final Duration STALE_PENDING_AFTER = Duration.ofHours(2);

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final AdHocRequestRepository adHocRequestRepository;
    private final PaymentGateway gateway;
    private final ApplicationEventPublisher events;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final InvoiceService invoiceService;
    private final Clock clock;

    @Value("${app.platform.fee-percent}")
    private BigDecimal feePercent;

    // ─── Parent: start a payment ─────────────────────────────────────────────

    @Transactional
    public PaymentResponse payBooking(User parent, User payer, UUID bookingId, PayRequest request) {
        Booking booking = bookingRepository.findByIdAndParentId(bookingId, parent.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw BusinessException.conflict("This booking is " + booking.getStatus() + " and can't be paid");
        }
        if (booking.getHoldExpiresAt() != null && booking.getHoldExpiresAt().isBefore(Instant.now(clock))) {
            throw BusinessException.conflict("The seat hold expired. Please book again.");
        }
        cancelPendingForBooking(booking);
        Payment payment = Payment.builder()
                .payer(payer)
                .driver(booking.getOffer().getDriver())
                .payee(booking.getOffer().getDriver().business())
                .booking(booking)
                .earningAvailableOn(booking.getStartDate())
                .build();
        return start(payment, booking.getAmount(), request);
    }

    @Transactional
    public PaymentResponse payAdHoc(User parent, User payer, UUID requestId, PayRequest request) {
        AdHocRequest adHoc = adHocRequestRepository.findByIdAndParentId(requestId, parent.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Pickup request not found"));
        if (adHoc.getStatus() != AdHocStatus.ACCEPTED) {
            throw BusinessException.conflict("This pickup is " + adHoc.getStatus() + " and can't be paid");
        }
        cancelPendingForAdHoc(adHoc);
        Payment payment = Payment.builder()
                .payer(payer)
                .driver(adHoc.getDriver())
                .payee(adHoc.getDriver().business())
                .adHocRequest(adHoc)
                .purpose(PaymentPurpose.ADHOC)
                // Payable the day after the pickup, so a no-show can still be refunded before the driver is paid
                .earningAvailableOn(LocalDate.ofInstant(adHoc.getPickupAt(), clock.getZone()).plusDays(1))
                .build();
        return start(payment, adHoc.getFare(), request);
    }

    private PaymentResponse start(Payment payment, BigDecimal amount, PayRequest request) {
        return start(payment, amount, BookingPricing.platformFee(amount, feePercent), request);
    }

    /** Charges a driver for SchoolDrop's own products (monthly listing fee, adverts): it all goes to the platform. */
    @Transactional
    public PaymentResponse chargeDriver(Payment payment, BigDecimal amount, PayRequest request) {
        return start(payment, amount, amount, request);
    }

    private PaymentResponse start(Payment payment, BigDecimal amount, BigDecimal fee, PayRequest request) {
        if (request.method() == PaymentMethod.CASH) {
            throw BusinessException.badRequest("Cash is paid to the driver directly — choose \"pay the driver directly\" instead");
        }
        if (request.method() != PaymentMethod.CARD && request.payerPhone() == null) {
            throw BusinessException.badRequest("Enter the mobile money number to charge");
        }
        payment.setAmount(amount);
        payment.setPlatformFee(fee);
        payment.setDriverAmount(amount.subtract(fee));
        payment.setMethod(request.method());
        payment = paymentRepository.save(payment);

        PaymentGateway.Checkout checkout = gateway.initiate(payment, request.payerPhone());
        payment.setProviderReference(checkout.providerReference());
        payment.setCheckoutUrl(checkout.checkoutUrl());
        return PaymentResponse.from(payment, false);
    }

    /**
     * The driver confirms the parent paid them directly (cash / EFT). Recorded for the books, with no platform
     * fee, and never included in a payout — the driver already has the money.
     */
    @Transactional
    public void recordOfflineBookingPayment(Booking booking) {
        Payment payment = paymentRepository.save(Payment.builder()
                .payer(booking.getParent())
                .driver(booking.getOffer().getDriver())
                .payee(booking.getOffer().getDriver().business())
                .booking(booking)
                .purpose(PaymentPurpose.BOOKING)
                .offline(true)
                .method(PaymentMethod.CASH)
                .amount(booking.getAmount())
                .platformFee(BigDecimal.ZERO)
                .driverAmount(booking.getAmount())
                .status(PaymentStatus.SUCCEEDED)
                .earningAvailableOn(booking.getStartDate())
                .paidAt(Instant.now(clock))
                .build());
        auditService.record(booking.getOffer().getDriver().getUser(), "OFFLINE_PAYMENT_CONFIRMED", "Payment",
                payment.getId(), booking.getAmount().toPlainString());
        events.publishEvent(new PaymentSucceededEvent(payment));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getMine(User parent, UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .filter(p -> p.getPayer().getId().equals(parent.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        return PaymentResponse.from(payment, false);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listMine(User parent) {
        return paymentRepository.findByPayerId(parent.getId()).stream()
                .map(p -> PaymentResponse.from(p, false)).toList();
    }

    // ─── Gateway callbacks ───────────────────────────────────────────────────

    /** Applies a gateway result. Idempotent: gateways retry webhooks. */
    @Transactional
    public void handleResult(String providerReference, boolean success, String failureReason) {
        Payment payment = paymentRepository.findByProviderReference(providerReference)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown payment reference"));
        if (payment.getStatus() == PaymentStatus.CANCELLED && success) {
            // The parent retried (or we gave up waiting) but this attempt went through after all — give it back
            payment.setStatus(PaymentStatus.SUCCEEDED);
            payment.setPaidAt(Instant.now(clock));
            refund(null, payment, "This payment completed after it had been cancelled.");
            return;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("Ignoring repeat result for payment {} ({})", payment.getId(), payment.getStatus());
            return;
        }
        if (!success) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(failureReason);
            notificationService.notify(payment.getPayer(), "PAYMENT", "Payment failed",
                    "Your payment of P" + payment.getAmount() + " didn't go through"
                            + (failureReason != null ? ": " + failureReason : ".") + " You can try again.",
                    payment.getId());
            return;
        }
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setPaidAt(Instant.now(clock));
        auditService.recordSystem("PAYMENT_SUCCEEDED", "Payment", payment.getId(), payment.getAmount().toPlainString());
        events.publishEvent(new PaymentSucceededEvent(payment));
    }

    // ─── Cancellations & refunds ─────────────────────────────────────────────

    @Transactional
    public void cancelPendingForBooking(Booking booking) {
        paymentRepository.findByBookingId(booking.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .forEach(p -> p.setStatus(PaymentStatus.CANCELLED));
    }

    @Transactional
    public void cancelPendingForAdHoc(AdHocRequest adHoc) {
        paymentRepository.findByAdHocRequestId(adHoc.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .forEach(p -> p.setStatus(PaymentStatus.CANCELLED));
    }

    @Transactional
    public void refundAllForBooking(User actor, Booking booking, String reason) {
        paymentRepository.findByBookingId(booking.getId()).stream()
                // Money paid to the driver directly is settled between parent and driver
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && !p.isOffline())
                .forEach(p -> refund(actor, p, reason));
    }

    @Transactional
    public void refundAllForAdHoc(User actor, AdHocRequest adHoc, String reason) {
        paymentRepository.findByAdHocRequestId(adHoc.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                .forEach(p -> refund(actor, p, reason));
    }

    @Transactional
    public PaymentResponse adminRefund(User admin, UUID paymentId, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        refund(admin, payment, reason);
        return PaymentResponse.from(payment, true);
    }

    /** actor may be null for system refunds (e.g. paid after the booking expired). */
    public void refund(User actor, Payment payment, String reason) {
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw BusinessException.conflict("Only successful payments can be refunded");
        }
        if (payment.isOffline()) {
            throw BusinessException.conflict("This was paid to the driver directly — the refund is between the parent and the driver");
        }
        Payout payout = payment.getPayout();
        if (payout != null && payout.getStatus() == PayoutStatus.PAID) {
            throw BusinessException.conflict("The driver has already been paid for this — refund manually and recover from the driver");
        }
        gateway.refund(payment);
        if (payout != null) {
            // Take it back out of the not-yet-sent payout
            payout.setAmount(payout.getAmount().subtract(payment.getDriverAmount()));
            payment.setPayout(null);
        }
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(Instant.now(clock));
        auditService.record(actor, "PAYMENT_REFUNDED", "Payment", payment.getId(), reason);
        invoiceService.creditNoteFor(payment, reason);
        notificationService.notify(payment.getPayer(), "PAYMENT", "Refund issued",
                "P" + payment.getAmount() + " is being refunded to you. " + reason, payment.getId());
    }

    // ─── Admin & jobs ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> adminSearch(PaymentStatus status, Pageable pageable) {
        return PageResponse.from(paymentRepository.adminSearch(status, pageable).map(p -> PaymentResponse.from(p, true)));
    }

    @Transactional
    public int cancelStalePending() {
        List<Payment> stale = paymentRepository.findByStatusAndCreatedAtBefore(
                PaymentStatus.PENDING, Instant.now(clock).minus(STALE_PENDING_AFTER));
        stale.forEach(p -> {
            p.setStatus(PaymentStatus.CANCELLED);
            p.setFailureReason("No confirmation from the payment provider");
        });
        return stale.size();
    }
}

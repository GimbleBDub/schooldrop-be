package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.responses.EarningsResponse;
import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.dto.responses.PayoutResponse;
import com.gimble.bus_stop.entities.DriverProfile;
import com.gimble.bus_stop.entities.Payment;
import com.gimble.bus_stop.entities.Payout;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.PaymentStatus;
import com.gimble.bus_stop.enums.PayoutStatus;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.repositories.DriverProfileRepository;
import com.gimble.bus_stop.repositories.PaymentRepository;
import com.gimble.bus_stop.repositories.PayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Batches drivers' earnings into payouts. Finance sends the money (mobile money / bank) and marks each
 * payout PAID with the transfer reference. Swap in a disbursement API later without changing the ledger.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutService {

    private final PayoutRepository payoutRepository;
    private final PaymentRepository paymentRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final DriverService driverService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final InvoiceService invoiceService;
    private final Clock clock;

    /** Creates one PENDING payout per driver with earnings due (service has started). */
    @Transactional
    public int createPayouts(User actor) {
        LocalDate today = LocalDate.now(clock);
        int created = 0;
        for (UUID driverId : paymentRepository.findDriversWithPayable(today)) {
            DriverProfile driver = driverProfileRepository.findById(driverId).orElseThrow();
            if (driver.getPayoutMethod() == null || driver.getPayoutAccount() == null) {
                notificationService.notify(driver.getUser(), "PAYOUT", "Add your payout details",
                        "You have earnings waiting. Add a payout account to receive them.", driver.getId());
                continue;
            }
            List<Payment> payable = paymentRepository.findPayable(driverId, today);
            BigDecimal total = payable.stream().map(Payment::getDriverAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.signum() <= 0) {
                continue;
            }
            Payout payout = payoutRepository.save(Payout.builder()
                    .driver(driver)
                    .amount(total)
                    .method(driver.getPayoutMethod())
                    .account(driver.getPayoutAccount())
                    .build());
            payable.forEach(p -> p.setPayout(payout));
            created++;
        }
        auditService.record(actor, "PAYOUTS_CREATED", "Payout", null, created + " payouts");
        log.info("Created {} payouts", created);
        return created;
    }

    @Transactional(readOnly = true)
    public PageResponse<PayoutResponse> adminSearch(PayoutStatus status, Pageable pageable) {
        return PageResponse.from(payoutRepository.adminSearch(status, pageable).map(p -> PayoutResponse.from(p, true)));
    }

    @Transactional
    public PayoutResponse markPaid(User admin, UUID payoutId, String reference) {
        Payout payout = requirePending(payoutId);
        payout.setStatus(PayoutStatus.PAID);
        payout.setReference(reference);
        payout.setProcessedBy(admin);
        payout.setProcessedAt(Instant.now(clock));
        auditService.record(admin, "PAYOUT_PAID", "Payout", payout.getId(), reference);
        invoiceService.statementFor(payout);
        notificationService.notify(payout.getDriver().getUser(), "PAYOUT", "Payout sent",
                "P" + payout.getAmount() + " was sent to your " + payout.getMethod() + " account (ref " + reference + ").",
                payout.getId());
        return PayoutResponse.from(payout, true);
    }

    /** The transfer bounced: release the payments so the next batch picks them up again. */
    @Transactional
    public PayoutResponse markFailed(User admin, UUID payoutId, String reason) {
        Payout payout = requirePending(payoutId);
        payout.setStatus(PayoutStatus.FAILED);
        payout.setFailureReason(reason);
        payout.setProcessedBy(admin);
        payout.setProcessedAt(Instant.now(clock));
        paymentRepository.findEarningsForDriver(payout.getDriver().getId()).stream()
                .filter(p -> p.getPayout() != null && p.getPayout().getId().equals(payout.getId()))
                .forEach(p -> p.setPayout(null));
        auditService.record(admin, "PAYOUT_FAILED", "Payout", payout.getId(), reason);
        notificationService.notify(payout.getDriver().getUser(), "PAYOUT", "Payout failed",
                "We couldn't send your payout: " + reason + ". Please check your payout details.", payout.getId());
        return PayoutResponse.from(payout, true);
    }

    @Transactional(readOnly = true)
    public EarningsResponse driverEarnings(User user) {
        // A team's earnings belong to the team (its owner's profile)
        DriverProfile driver = driverService.requireProfile(user).business();
        LocalDate today = LocalDate.now(clock);
        List<Payment> payments = paymentRepository.findEarningsForDriver(driver.getId());
        BigDecimal available = BigDecimal.ZERO;
        BigDecimal upcoming = BigDecimal.ZERO;
        for (Payment p : payments) {
            // Money paid to the driver directly never goes through a payout
            if (p.getStatus() != PaymentStatus.SUCCEEDED || p.getPayout() != null || p.isOffline()) {
                continue;
            }
            if (p.getEarningAvailableOn().isAfter(today)) {
                upcoming = upcoming.add(p.getDriverAmount());
            } else {
                available = available.add(p.getDriverAmount());
            }
        }
        return new EarningsResponse(available, upcoming, "BWP",
                payments.stream().limit(50).map(EarningsResponse.Line::from).toList(),
                payoutRepository.findByDriverIdOrderByCreatedAtDesc(driver.getId()).stream()
                        .map(p -> PayoutResponse.from(p, false)).toList());
    }

    private Payout requirePending(UUID payoutId) {
        Payout payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new ResourceNotFoundException("Payout not found"));
        if (payout.getStatus() != PayoutStatus.PENDING) {
            throw BusinessException.conflict("This payout is already " + payout.getStatus());
        }
        return payout;
    }
}

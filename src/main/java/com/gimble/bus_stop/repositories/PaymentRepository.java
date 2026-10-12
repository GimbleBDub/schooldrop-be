package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Payment;
import com.gimble.bus_stop.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByProviderReference(String providerReference);

    List<Payment> findByBookingId(UUID bookingId);

    List<Payment> findByAdHocRequestId(UUID adHocRequestId);

    List<Payment> findBySubscriptionId(UUID subscriptionId);

    List<Payment> findByAdvertId(UUID advertId);

    @Query("SELECT p FROM Payment p WHERE p.payer.id = :payerId ORDER BY p.createdAt DESC")
    List<Payment> findByPayerId(UUID payerId);

    // Earnings, payouts and billing go by payee (the business: a team's owner, or the driver on their own).
    // The parameter is still called driverId: it's the payee's driver profile.

    /** Succeeded payments whose earnings are due and not yet in a payout. */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.payee.id = :driverId AND p.status = com.gimble.bus_stop.enums.PaymentStatus.SUCCEEDED
              AND p.payout IS NULL AND p.earningAvailableOn <= :asOf AND p.offline = false
              AND p.purpose IN (com.gimble.bus_stop.enums.PaymentPurpose.BOOKING, com.gimble.bus_stop.enums.PaymentPurpose.ADHOC)
            """)
    List<Payment> findPayable(UUID driverId, LocalDate asOf);

    @Query("""
            SELECT DISTINCT p.payee.id FROM Payment p
            WHERE p.status = com.gimble.bus_stop.enums.PaymentStatus.SUCCEEDED
              AND p.payout IS NULL AND p.earningAvailableOn <= :asOf AND p.offline = false
              AND p.purpose IN (com.gimble.bus_stop.enums.PaymentPurpose.BOOKING, com.gimble.bus_stop.enums.PaymentPurpose.ADHOC)
            """)
    List<UUID> findDriversWithPayable(LocalDate asOf);

    @Query("""
            SELECT COALESCE(SUM(p.driverAmount), 0) FROM Payment p
            WHERE p.payee.id = :driverId AND p.status = com.gimble.bus_stop.enums.PaymentStatus.SUCCEEDED
              AND p.payout IS NULL
            """)
    BigDecimal sumUnpaidEarnings(UUID driverId);

    @Query("""
            SELECT p FROM Payment p WHERE p.payee.id = :driverId
              AND p.purpose IN (com.gimble.bus_stop.enums.PaymentPurpose.BOOKING, com.gimble.bus_stop.enums.PaymentPurpose.ADHOC)
              AND p.status IN (com.gimble.bus_stop.enums.PaymentStatus.SUCCEEDED,
                               com.gimble.bus_stop.enums.PaymentStatus.REFUNDED)
            ORDER BY p.createdAt DESC
            """)
    List<Payment> findEarningsForDriver(UUID driverId);

    @Query(value = """
            SELECT p FROM Payment p JOIN FETCH p.payer JOIN FETCH p.driver d JOIN FETCH d.user
            WHERE (:status IS NULL OR p.status = :status) ORDER BY p.createdAt DESC
            """,
            countQuery = "SELECT COUNT(p) FROM Payment p WHERE (:status IS NULL OR p.status = :status)")
    Page<Payment> adminSearch(PaymentStatus status, Pageable pageable);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM Payment p
            WHERE p.status = com.gimble.bus_stop.enums.PaymentStatus.SUCCEEDED AND p.paidAt >= :since
              AND p.offline = false
            """)
    BigDecimal sumGrossSince(Instant since);

    @Query("""
            SELECT COALESCE(SUM(p.platformFee), 0) FROM Payment p
            WHERE p.status = com.gimble.bus_stop.enums.PaymentStatus.SUCCEEDED AND p.paidAt >= :since
            """)
    BigDecimal sumFeesSince(Instant since);

    List<Payment> findByStatusAndCreatedAtBefore(PaymentStatus status, Instant cutoff);

    @Query("SELECT p FROM Payment p WHERE p.payout.id = :payoutId ORDER BY p.paidAt")
    List<Payment> findByPayoutIdOrderByPaidAt(UUID payoutId);

    /** Money that moved but has no receipt / invoice yet. */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.status IN (com.gimble.bus_stop.enums.PaymentStatus.SUCCEEDED, com.gimble.bus_stop.enums.PaymentStatus.REFUNDED)
              AND NOT EXISTS (SELECT i FROM Invoice i WHERE i.payment = p
                              AND i.type IN (com.gimble.bus_stop.enums.InvoiceType.RECEIPT, com.gimble.bus_stop.enums.InvoiceType.INVOICE))
            ORDER BY p.paidAt
            """)
    List<Payment> findWithoutDocument();

    @Query("""
            SELECT p FROM Payment p
            WHERE p.status = com.gimble.bus_stop.enums.PaymentStatus.REFUNDED
              AND NOT EXISTS (SELECT i FROM Invoice i WHERE i.payment = p AND i.type = com.gimble.bus_stop.enums.InvoiceType.CREDIT_NOTE)
            """)
    List<Payment> findRefundedWithoutCreditNote();

    // ─── Billing ─────────────────────────────────────────────────────────────

    /** Everything a family paid (any member), newest first. */
    @Query("""
            SELECT p FROM Payment p
            LEFT JOIN p.booking b LEFT JOIN p.adHocRequest a
            WHERE (b.parent.id = :ownerId OR a.parent.id = :ownerId)
              AND p.status IN (com.gimble.bus_stop.enums.PaymentStatus.SUCCEEDED, com.gimble.bus_stop.enums.PaymentStatus.REFUNDED)
            ORDER BY COALESCE(p.paidAt, p.createdAt) DESC
            """)
    List<Payment> findSettledForFamily(UUID ownerId);

    /** Money a driver received from families (in the app or directly). */
    @Query("""
            SELECT p FROM Payment p WHERE p.payee.id = :driverId
              AND p.purpose IN (com.gimble.bus_stop.enums.PaymentPurpose.BOOKING, com.gimble.bus_stop.enums.PaymentPurpose.ADHOC)
              AND p.status IN (com.gimble.bus_stop.enums.PaymentStatus.SUCCEEDED, com.gimble.bus_stop.enums.PaymentStatus.REFUNDED)
            ORDER BY COALESCE(p.paidAt, p.createdAt) DESC
            """)
    List<Payment> findIncomeForDriver(UUID driverId);

    /** What a driver paid SchoolDrop (listing fee, adverts). */
    @Query("""
            SELECT p FROM Payment p WHERE p.payee.id = :driverId
              AND p.purpose IN (com.gimble.bus_stop.enums.PaymentPurpose.SUBSCRIPTION, com.gimble.bus_stop.enums.PaymentPurpose.ADVERT)
              AND p.status IN (com.gimble.bus_stop.enums.PaymentStatus.SUCCEEDED, com.gimble.bus_stop.enums.PaymentStatus.REFUNDED)
            ORDER BY COALESCE(p.paidAt, p.createdAt) DESC
            """)
    List<Payment> findChargesForDriver(UUID driverId);

    /** Platform-wide settled payments in a window (finance report). */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.status IN (com.gimble.bus_stop.enums.PaymentStatus.SUCCEEDED, com.gimble.bus_stop.enums.PaymentStatus.REFUNDED)
              AND p.paidAt >= :from AND p.paidAt < :to
            """)
    List<Payment> findSettledBetween(Instant from, Instant to);
}

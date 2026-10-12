package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Payout;
import com.gimble.bus_stop.enums.PayoutStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    List<Payout> findByDriverIdOrderByCreatedAtDesc(UUID driverId);

    @Query(value = """
            SELECT p FROM Payout p JOIN FETCH p.driver d JOIN FETCH d.user
            WHERE (:status IS NULL OR p.status = :status) ORDER BY p.createdAt DESC
            """,
            countQuery = "SELECT COUNT(p) FROM Payout p WHERE (:status IS NULL OR p.status = :status)")
    Page<Payout> adminSearch(PayoutStatus status, Pageable pageable);

    @Query("""
            SELECT p FROM Payout p WHERE p.status = com.gimble.bus_stop.enums.PayoutStatus.PAID
              AND NOT EXISTS (SELECT i FROM Invoice i WHERE i.payout = p AND i.type = com.gimble.bus_stop.enums.InvoiceType.STATEMENT)
            """)
    List<Payout> findPaidWithoutStatement();

    @Query("""
            SELECT p FROM Payout p WHERE p.status = com.gimble.bus_stop.enums.PayoutStatus.PAID
              AND p.processedAt >= :from AND p.processedAt < :to
            """)
    List<Payout> findPaidBetween(java.time.Instant from, java.time.Instant to);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payout p WHERE p.status = :status")
    BigDecimal sumByStatus(PayoutStatus status);
}

package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.BillingPlan;
import com.gimble.bus_stop.enums.BookingStatus;
import com.gimble.bus_stop.enums.PaymentMode;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A child's seat on a transport offer for a period (a month, a term, or a range of school days). */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_id", nullable = false)
    private Child child;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_id", nullable = false)
    private User parent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offer_id", nullable = false)
    private TransportOffer offer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillingPlan plan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "term_id")
    private SchoolTerm term;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    // Number of trips billed (PER_TRIP only)
    private Integer tripCount;

    @Column(nullable = false, length = 500)
    private String pickupAddress;

    private Double pickupLatitude;
    private Double pickupLongitude;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status = BookingStatus.PENDING_PAYMENT;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentMode paymentMode = PaymentMode.APP;

    // OFFLINE: when the parent said they paid the driver (the driver still has to confirm)
    private Instant offlinePaidClaimedAt;

    // Seat is released if not paid by then
    private Instant holdExpiresAt;

    private String cancelReason;

    private Instant cancelledAt;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}

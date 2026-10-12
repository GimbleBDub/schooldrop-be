package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.PaymentMethod;
import com.gimble.bus_stop.enums.PaymentPurpose;
import com.gimble.bus_stop.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A parent's payment for a booking or an ad-hoc pickup, split into platform fee and driver earnings. */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payer_id", nullable = false)
    private User payer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private DriverProfile driver;

    // Who the money belongs to, fixed at payment time: the team owner for team drivers, else the driver
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payee_id", nullable = false)
    private DriverProfile payee;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentPurpose purpose = PaymentPurpose.BOOKING;

    // Paid to the driver directly (cash / EFT) and confirmed by them: no platform fee, never paid out
    @Builder.Default
    @Column(nullable = false)
    private boolean offline = false;

    // Exactly one of booking / adHocRequest / subscription / advert is set
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adhoc_request_id")
    private AdHocRequest adHocRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private DriverSubscription subscription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advert_id")
    private Advert advert;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal platformFee;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal driverAmount;

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "BWP";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.PENDING;

    // The gateway's id for this payment
    @Column(unique = true)
    private String providerReference;

    // Where to send the parent to finish paying (card page), or instructions for mobile money
    @Column(length = 1000)
    private String checkoutUrl;

    private String failureReason;

    // Driver earnings become payable from this date (service start), so refunds before then are clean
    @Column(nullable = false)
    private LocalDate earningAvailableOn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_id")
    private Payout payout;

    private Instant paidAt;

    private Instant refundedAt;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (payee == null && driver != null) {
            payee = driver.business();
        }
    }
}

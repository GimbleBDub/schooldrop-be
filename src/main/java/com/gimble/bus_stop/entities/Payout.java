package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.PaymentMethod;
import com.gimble.bus_stop.enums.PayoutStatus;
import com.gimble.bus_stop.security.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A batch of a driver's earnings to be sent to their mobile-money/bank account. */
@Entity
@Table(name = "payouts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private DriverProfile driver;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    // Snapshot of the account at payout time
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 512)
    private String account;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayoutStatus status = PayoutStatus.PENDING;

    // Transfer reference entered by finance (or returned by a disbursement API)
    private String reference;

    private String failureReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processed_by")
    private User processedBy;

    private Instant processedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}

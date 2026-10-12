package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.AdHocStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A once-off pickup from school (e.g. the parent is stuck at work).
 * Broadcast to approved drivers serving the school; the first to accept gets it.
 */
@Entity
@Table(name = "adhoc_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdHocRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_id", nullable = false)
    private User parent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_id", nullable = false)
    private Child child;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_id", nullable = false)
    private School school;

    // When the child should be collected from school
    @Column(nullable = false)
    private Instant pickupAt;

    @Column(nullable = false, length = 500)
    private String dropoffAddress;

    private Double dropoffLatitude;
    private Double dropoffLongitude;

    @Column(length = 500)
    private String notes;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdHocStatus status = AdHocStatus.OPEN;

    // Set when a driver accepts
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private DriverProfile driver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_id")
    private TransportOffer offer;

    @Column(precision = 10, scale = 2)
    private BigDecimal fare;

    private Instant acceptedAt;

    private String cancelReason;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}

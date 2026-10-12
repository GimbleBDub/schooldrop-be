package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.BillingPlan;
import com.gimble.bus_stop.enums.TripCoverage;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A driver's regular service to one school with one vehicle: which legs, how many seats,
 * where they collect from, and which billing plans (per trip / monthly / termly) they offer.
 */
@Entity
@Table(name = "transport_offers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransportOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private DriverProfile driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_id", nullable = false)
    private School school;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripCoverage coverage;

    // Seats on this run; never more than the vehicle's seat capacity
    @Column(nullable = false)
    private int capacity;

    // Areas the driver collects from / drops off at, e.g. "Phakalane, Block 10, Tlokweng"
    @Column(nullable = false, length = 500)
    private String serviceArea;

    // Usual pickup time for the morning leg, e.g. "06:30"
    private String morningPickupTime;

    // Null = plan not offered
    @Column(precision = 10, scale = 2)
    private BigDecimal pricePerTrip;

    @Column(precision = 10, scale = 2)
    private BigDecimal pricePerMonth;

    @Column(precision = 10, scale = 2)
    private BigDecimal pricePerTerm;

    // Fare for a once-off pickup from this school; null = no ad-hoc pickups
    @Column(precision = 10, scale = 2)
    private BigDecimal adHocFare;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public BigDecimal priceFor(BillingPlan plan) {
        return switch (plan) {
            case PER_TRIP -> pricePerTrip;
            case MONTHLY -> pricePerMonth;
            case TERMLY -> pricePerTerm;
        };
    }
}

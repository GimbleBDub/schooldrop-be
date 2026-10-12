package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.TripLeg;
import com.gimble.bus_stop.enums.TripStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One run on one day: a regular offer's leg, or an ad-hoc pickup. */
@Entity
@Table(name = "trips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Exactly one of offer / adHocRequest is set
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_id")
    private TransportOffer offer;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adhoc_request_id", unique = true)
    private AdHocRequest adHocRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private DriverProfile driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_id", nullable = false)
    private School school;

    @Column(nullable = false)
    private LocalDate tripDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripLeg leg;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripStatus status = TripStatus.SCHEDULED;

    private Instant startedAt;
    private Instant completedAt;

    // Latest driver position while IN_PROGRESS (live tracking)
    private Double lastLatitude;
    private Double lastLongitude;
    private Instant lastLocationAt;

    @Builder.Default
    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<TripPassenger> passengers = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}

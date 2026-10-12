package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.VehicleType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "vehicles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private DriverProfile driver;

    // Fleet vehicle of a team / company: any of its drivers can be assigned to it
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id")
    private Operator operator;

    @Column(nullable = false)
    private String make;

    @Column(nullable = false)
    private String model;

    private Integer year;

    private String colour;

    @Column(nullable = false, unique = true, length = 20)
    private String plateNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleType type;

    // Passenger seats available for children (excluding the driver)
    @Column(nullable = false)
    private int seatCapacity;

    private LocalDate roadworthyExpiry;

    private LocalDate insuranceExpiry;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public String getDisplayName() {
        return (colour != null ? colour + " " : "") + make + " " + model + " (" + plateNumber + ")";
    }
}

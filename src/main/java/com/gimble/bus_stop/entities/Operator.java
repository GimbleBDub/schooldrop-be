package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.OperatorKind;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A team of drivers or a transport company. The owner's driver profile acts as the business:
 * payouts, the monthly listing fee (per driver) and adverts go through it.
 */
@Entity
@Table(name = "operators")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Operator {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OperatorKind kind;

    // CIPA number, for registered companies
    @Column(length = 60)
    private String registrationNumber;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, unique = true)
    private DriverProfile owner;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}

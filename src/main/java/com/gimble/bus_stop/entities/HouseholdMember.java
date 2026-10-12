package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.HouseholdRole;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "household_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HouseholdMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    // A user belongs to at most one household
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HouseholdRole role;

    @Column(nullable = false, updatable = false)
    private Instant joinedAt;

    @PrePersist
    protected void onCreate() {
        joinedAt = Instant.now();
    }
}

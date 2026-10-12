package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.InviteKind;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A one-time code to join a household, a school or a driver team. Only the hash is stored. */
@Entity
@Table(name = "invites")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InviteKind kind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "household_id")
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id")
    private School school;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id")
    private Operator operator;

    // HouseholdRole or SchoolRole name
    @Column(nullable = false, length = 20)
    private String role;

    @Column(length = 120)
    private String inviteeName;

    @Column(nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by")
    private User acceptedBy;

    private Instant acceptedAt;

    private Instant revokedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isUsable(Instant now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }
}

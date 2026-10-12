package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.EnrollmentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A child, fully managed by their parent. Optionally paired with a KID user account
 * (the kid's own phone) which can only confirm pickups and drop-offs.
 */
@Entity
@Table(name = "children")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Child {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_id", nullable = false)
    private User parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id")
    private School school;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    private LocalDate dateOfBirth;

    private String grade;

    // Helps the school find the child when confirming enrollment
    private String studentNumber;

    // Allergies, medical conditions, anything the driver must know
    @Column(length = 1000)
    private String specialNeeds;

    @Enumerated(EnumType.STRING)
    private EnrollmentStatus enrollmentStatus;

    private String enrollmentNote;

    // The kid's own app login, if paired
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kid_user_id", unique = true)
    private User kidUser;

    // One-time code a parent generates to pair the kid's phone (stored hashed)
    @Column(unique = true, length = 64)
    private String pairingCodeHash;

    private Instant pairingCodeExpiresAt;

    // Set when the parent removes a child that has booking/trip history (kept for safety records)
    private Instant archivedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    public boolean isEnrollmentConfirmed() {
        return enrollmentStatus == EnrollmentStatus.CONFIRMED;
    }
}

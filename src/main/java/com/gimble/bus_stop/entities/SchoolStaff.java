package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.SchoolRole;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Links a SCHOOL-role user to the school they act for (admin or staff). A user belongs to at most one school. */
@Entity
@Table(name = "school_staff")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchoolStaff {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_id", nullable = false)
    private School school;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SchoolRole role = SchoolRole.ADMIN;

    // e.g. "Grade 3 teacher", "Gate"
    @Column(length = 80)
    private String title;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}

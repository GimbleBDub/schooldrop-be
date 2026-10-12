package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.SchoolStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "schools")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class School {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    // Ministry of Education registration / EMIS number, used by admins when verifying
    private String registrationNumber;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    private Double latitude;
    private Double longitude;

    private String phone;
    private String email;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SchoolStatus status = SchoolStatus.PENDING;

    private String statusReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    private Instant reviewedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isVerified() {
        return status == SchoolStatus.VERIFIED;
    }
}

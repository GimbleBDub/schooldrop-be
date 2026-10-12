package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.AdvertStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A sponsored card shown to parents and school staff. Paid for by a driver in the app, or added by
 * staff for other advertisers. Every advert is reviewed before it goes live.
 */
@Entity
@Table(name = "adverts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Advert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Set when a driver advertises themselves (their offers then show as "Featured")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private DriverProfile driver;

    @Column(nullable = false, length = 120)
    private String advertiserName;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(nullable = false, length = 280)
    private String body;

    @Column(length = 30)
    private String ctaLabel;

    @Column(length = 20)
    private String ctaPhone;

    @Column(length = 500)
    private String ctaUrl;

    // Null = every school
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_school_id")
    private School targetSchool;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdvertStatus status;

    private String reviewNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    private Instant reviewedAt;

    private Instant startsAt;
    private Instant endsAt;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    private long impressions;
    private long clicks;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isLive(Instant now) {
        return status == AdvertStatus.ACTIVE && startsAt != null && !now.isBefore(startsAt)
                && endsAt != null && now.isBefore(endsAt);
    }
}

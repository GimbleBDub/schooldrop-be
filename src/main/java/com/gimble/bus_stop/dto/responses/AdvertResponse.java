package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Advert;
import com.gimble.bus_stop.enums.AdvertStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdvertResponse(
        UUID id,
        String advertiserName,
        String title,
        String body,
        String ctaLabel,
        String ctaPhone,
        String ctaUrl,
        UUID targetSchoolId,
        String targetSchoolName,
        AdvertStatus status,
        String reviewNote,
        Instant startsAt,
        Instant endsAt,
        BigDecimal price,
        long impressions,
        long clicks,
        boolean fromDriver,
        Instant createdAt
) {
    public static AdvertResponse from(Advert a) {
        return new AdvertResponse(a.getId(), a.getAdvertiserName(), a.getTitle(), a.getBody(), a.getCtaLabel(),
                a.getCtaPhone(), a.getCtaUrl(),
                a.getTargetSchool() != null ? a.getTargetSchool().getId() : null,
                a.getTargetSchool() != null ? a.getTargetSchool().getName() : null,
                a.getStatus(), a.getReviewNote(), a.getStartsAt(), a.getEndsAt(), a.getPrice(), a.getImpressions(),
                a.getClicks(), a.getDriver() != null, a.getCreatedAt());
    }

    /** What viewers get: no stats or pricing. */
    public static AdvertResponse forViewer(Advert a) {
        return new AdvertResponse(a.getId(), a.getAdvertiserName(), a.getTitle(), a.getBody(), a.getCtaLabel(),
                a.getCtaPhone(), a.getCtaUrl(), null, null, a.getStatus(), null, null, null, null, 0, 0,
                a.getDriver() != null, null);
    }
}

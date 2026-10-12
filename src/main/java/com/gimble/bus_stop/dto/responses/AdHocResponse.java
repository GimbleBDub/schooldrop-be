package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.AdHocRequest;
import com.gimble.bus_stop.enums.AdHocStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdHocResponse(
        UUID id,
        UUID childId,
        String childName,
        String childGrade,
        UUID schoolId,
        String schoolName,
        Instant pickupAt,
        String dropoffAddress,
        Double dropoffLatitude,
        Double dropoffLongitude,
        String notes,
        AdHocStatus status,
        BigDecimal fare,
        UUID driverId,
        String driverName,
        String driverPhone,
        String vehicle,
        Instant acceptedAt,
        String cancelReason,
        Instant createdAt
) {
    /** fareOverride: the fare to show a driver browsing open requests (their own offer's fare). */
    public static AdHocResponse from(AdHocRequest r, BigDecimal fareOverride) {
        var driver = r.getDriver();
        return new AdHocResponse(r.getId(), r.getChild().getId(), r.getChild().getFullName(), r.getChild().getGrade(),
                r.getSchool().getId(), r.getSchool().getName(), r.getPickupAt(), r.getDropoffAddress(),
                r.getDropoffLatitude(), r.getDropoffLongitude(), r.getNotes(), r.getStatus(),
                fareOverride != null ? fareOverride : r.getFare(),
                driver != null ? driver.getId() : null,
                driver != null ? driver.getUser().getFullName() : null,
                driver != null ? driver.getUser().getPhone() : null,
                r.getOffer() != null ? r.getOffer().getVehicle().getDisplayName() : null,
                r.getAcceptedAt(), r.getCancelReason(), r.getCreatedAt());
    }

    public static AdHocResponse from(AdHocRequest r) {
        return from(r, null);
    }
}

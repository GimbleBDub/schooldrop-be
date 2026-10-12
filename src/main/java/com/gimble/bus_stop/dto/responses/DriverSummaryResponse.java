package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.DriverProfile;
import com.gimble.bus_stop.enums.DriverStatus;

import java.time.Instant;
import java.util.UUID;

/** Row in the admin verification queue / driver list. */
public record DriverSummaryResponse(
        UUID id,
        UUID userId,
        String fullName,
        String email,
        String phone,
        DriverStatus status,
        // e.g. "Owner · Safe Rides (company)"; null for drivers on their own
        String team,
        boolean drives,
        Instant submittedAt,
        Instant createdAt
) {
    public static DriverSummaryResponse from(DriverProfile d) {
        String team = d.getOperator() == null ? null
                : d.getOperatorRole().name().charAt(0) + d.getOperatorRole().name().substring(1).toLowerCase()
                  + " · " + d.getOperator().getName() + " (" + d.getOperator().getKind().name().toLowerCase() + ")";
        return new DriverSummaryResponse(d.getId(), d.getUser().getId(), d.getUser().getFullName(),
                d.getUser().getEmail(), d.getUser().getPhone(), d.getStatus(), team, d.isDrives(), d.getSubmittedAt(),
                d.getCreatedAt());
    }
}

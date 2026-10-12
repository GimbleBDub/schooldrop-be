package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Incident;
import com.gimble.bus_stop.enums.IncidentStatus;
import com.gimble.bus_stop.enums.IncidentType;
import com.gimble.bus_stop.enums.Role;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        IncidentType type,
        IncidentStatus status,
        String description,
        UUID reporterId,
        String reporterName,
        Role reporterRole,
        String reporterPhone,
        UUID tripId,
        UUID driverId,
        String driverName,
        Double latitude,
        Double longitude,
        String resolution,
        Instant resolvedAt,
        Instant createdAt
) {
    public static IncidentResponse from(Incident i) {
        var reporter = i.getReporter();
        return new IncidentResponse(i.getId(), i.getType(), i.getStatus(), i.getDescription(), reporter.getId(),
                reporter.getFullName(), reporter.getRole(), reporter.getPhone(),
                i.getTrip() != null ? i.getTrip().getId() : null,
                i.getDriver() != null ? i.getDriver().getId() : null,
                i.getDriver() != null ? i.getDriver().getUser().getFullName() : null,
                i.getLatitude(), i.getLongitude(), i.getResolution(), i.getResolvedAt(), i.getCreatedAt());
    }
}

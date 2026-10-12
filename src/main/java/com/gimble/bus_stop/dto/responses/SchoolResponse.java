package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.School;
import com.gimble.bus_stop.enums.SchoolStatus;

import java.time.Instant;
import java.util.UUID;

public record SchoolResponse(
        UUID id,
        String name,
        String registrationNumber,
        String address,
        String city,
        Double latitude,
        Double longitude,
        String phone,
        String email,
        SchoolStatus status,
        String statusReason,
        Instant createdAt
) {
    public static SchoolResponse from(School s) {
        return new SchoolResponse(s.getId(), s.getName(), s.getRegistrationNumber(), s.getAddress(),
                s.getCity(), s.getLatitude(), s.getLongitude(), s.getPhone(), s.getEmail(),
                s.getStatus(), s.getStatusReason(), s.getCreatedAt());
    }
}

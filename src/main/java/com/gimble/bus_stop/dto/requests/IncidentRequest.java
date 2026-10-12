package com.gimble.bus_stop.dto.requests;

import com.gimble.bus_stop.enums.IncidentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record IncidentRequest(
        @NotNull IncidentType type,
        @NotBlank @Size(max = 2000) String description,
        UUID tripId,
        UUID driverId,
        Double latitude,
        Double longitude
) {
}

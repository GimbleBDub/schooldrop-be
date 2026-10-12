package com.gimble.bus_stop.dto.requests;

import com.gimble.bus_stop.enums.IncidentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record IncidentUpdateRequest(@NotNull IncidentStatus status, @Size(max = 2000) String resolution) {
}

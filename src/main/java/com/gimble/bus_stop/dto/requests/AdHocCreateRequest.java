package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record AdHocCreateRequest(
        @NotNull UUID childId,
        @NotNull @Future Instant pickupAt,
        @NotBlank @Size(max = 500) String dropoffAddress,
        Double dropoffLatitude,
        Double dropoffLongitude,
        @Size(max = 500) String notes
) {
}

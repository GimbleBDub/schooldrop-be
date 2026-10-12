package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KidPairRequest(
        @NotBlank String code,
        @Size(max = 100) String deviceId
) {
}

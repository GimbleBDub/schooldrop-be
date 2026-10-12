package com.gimble.bus_stop.dto.requests;

import com.gimble.bus_stop.enums.VehicleType;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

public record VehicleRequest(
        @NotBlank String make,
        @NotBlank String model,
        @Min(1980) @Max(2100) Integer year,
        String colour,
        @NotBlank @Size(max = 20) String plateNumber,
        @NotNull VehicleType type,
        @NotNull @Min(1) @Max(80) Integer seatCapacity,
        LocalDate roadworthyExpiry,
        LocalDate insuranceExpiry
) {
}

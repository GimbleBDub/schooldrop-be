package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Vehicle;
import com.gimble.bus_stop.enums.VehicleType;

import java.time.LocalDate;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        String make,
        String model,
        Integer year,
        String colour,
        String plateNumber,
        VehicleType type,
        int seatCapacity,
        LocalDate roadworthyExpiry,
        LocalDate insuranceExpiry,
        boolean active,
        // All required vehicle documents approved
        boolean verified
) {
    public static VehicleResponse from(Vehicle v, boolean verified) {
        return new VehicleResponse(v.getId(), v.getMake(), v.getModel(), v.getYear(), v.getColour(),
                v.getPlateNumber(), v.getType(), v.getSeatCapacity(), v.getRoadworthyExpiry(),
                v.getInsuranceExpiry(), v.isActive(), verified);
    }
}

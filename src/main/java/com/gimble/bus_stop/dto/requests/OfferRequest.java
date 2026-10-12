package com.gimble.bus_stop.dto.requests;

import com.gimble.bus_stop.enums.TripCoverage;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

/** At least one of pricePerTrip / pricePerMonth / pricePerTerm must be set. */
public record OfferRequest(
        @NotNull UUID vehicleId,
        @NotNull UUID schoolId,
        @NotNull TripCoverage coverage,
        @NotNull @Min(1) Integer capacity,
        @NotBlank @Size(max = 500) String serviceArea,
        @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$", message = "must be HH:mm") String morningPickupTime,
        @DecimalMin("1.00") @Digits(integer = 8, fraction = 2) BigDecimal pricePerTrip,
        @DecimalMin("1.00") @Digits(integer = 8, fraction = 2) BigDecimal pricePerMonth,
        @DecimalMin("1.00") @Digits(integer = 8, fraction = 2) BigDecimal pricePerTerm,
        @DecimalMin("1.00") @Digits(integer = 8, fraction = 2) BigDecimal adHocFare,
        Boolean active,
        // Teams: which team driver drives this route (default: whoever creates it, if they drive)
        UUID driverId
) {
}

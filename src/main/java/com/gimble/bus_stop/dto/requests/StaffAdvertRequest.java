package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

/** An advert staff add for an outside advertiser who paid SchoolDrop directly. Goes live immediately. */
public record StaffAdvertRequest(
        @NotBlank @Size(max = 120) String advertiserName,
        @NotBlank @Size(max = 80) String title,
        @NotBlank @Size(max = 280) String body,
        @Size(max = 30) String ctaLabel,
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "must be a valid phone number") String ctaPhone,
        @Size(max = 500) @Pattern(regexp = "^https://.*", message = "must start with https://") String ctaUrl,
        UUID targetSchoolId,
        @NotNull @Min(1) @Max(365) Integer days,
        @NotNull @DecimalMin("0.00") BigDecimal pricePaid
) {
}

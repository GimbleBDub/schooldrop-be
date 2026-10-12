package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** A driver's advert. targetSchoolId null = all the schools the driver serves. */
public record AdvertRequest(
        @NotBlank @Size(max = 80) String title,
        @NotBlank @Size(max = 280) String body,
        @Size(max = 30) String ctaLabel,
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "must be a valid phone number") String ctaPhone,
        UUID targetSchoolId
) {
}

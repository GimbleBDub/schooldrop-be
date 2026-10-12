package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record ChildRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @Past LocalDate dateOfBirth,
        String grade,
        String studentNumber,
        @Size(max = 1000) String specialNeeds,
        // Must be a VERIFIED school; changing it sends enrollment back to PENDING
        UUID schoolId
) {
}

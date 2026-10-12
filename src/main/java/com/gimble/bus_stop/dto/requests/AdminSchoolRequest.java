package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.*;

/** SchoolDrop staff add the schools families and drivers can choose from, pinned on the map. */
public record AdminSchoolRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 255) String address,
        @NotBlank @Size(max = 100) String city,
        @Size(max = 60) String registrationNumber,
        @Pattern(regexp = "^\\+?[0-9 ]{7,18}$", message = "must be a valid phone number") String phone,
        @Email String email,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude
) {
}

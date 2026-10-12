package com.gimble.bus_stop.dto.requests;

import com.gimble.bus_stop.enums.StaffPermission;
import jakarta.validation.constraints.*;

import java.util.Set;

/**
 * Creates a platform staff account. Leave temporaryPassword empty to have one generated (returned once).
 * Share it privately; they must choose their own password before using the back office.
 */
public record StaffRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank @Email String email,
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "must be a valid phone number") String phone,
        @Size(min = 12, max = 72) String temporaryPassword,
        @NotEmpty Set<StaffPermission> permissions
) {
}

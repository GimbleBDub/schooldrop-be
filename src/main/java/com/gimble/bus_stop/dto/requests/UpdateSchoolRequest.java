package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

/** What a school's own admin can change: contact details. Name, address and location are set by SchoolDrop. */
public record UpdateSchoolRequest(
        @Pattern(regexp = "^\\+?[0-9 ]{7,18}$", message = "must be a valid phone number") String phone,
        @Email String email
) {
}

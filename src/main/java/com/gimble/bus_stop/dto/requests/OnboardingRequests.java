package com.gimble.bus_stop.dto.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** The details collected right after sign-up, one shape per account type. */
public final class OnboardingRequests {

    private OnboardingRequests() {
    }

    public record Parent(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName
    ) {
    }

    public record Driver(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Pattern(regexp = "^[0-9]{9}$", message = "must be a 9-digit Omang number") String omangNumber,
            @Past LocalDate dateOfBirth,
            @Size(max = 255) String homeArea,
            // Running a team / transport company? (leave empty to drive on your own)
            @Valid com.gimble.bus_stop.dto.team.TeamDtos.CreateTeamRequest team
    ) {
    }

    /**
     * Joining with an invite code instead: a parent account joins a family (as guardian or helper),
     * a school account joins an existing school (as admin or staff).
     */
    public record Join(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(max = 20) String code,
            // e.g. "Grade 3 teacher" — school staff only
            @Size(max = 80) String title
    ) {
    }

    /** firstName/lastName are the contact person registering the school. */
    public record School(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank String schoolName,
            @NotBlank String address,
            @NotBlank String city,
            String registrationNumber,
            @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "must be a valid phone number") String schoolPhone,
            @Email String schoolEmail,
            Double latitude,
            Double longitude
    ) {
    }
}

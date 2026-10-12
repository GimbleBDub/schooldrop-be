package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Child;
import com.gimble.bus_stop.enums.EnrollmentStatus;

import java.time.LocalDate;
import java.util.UUID;

/** A child as their parent sees it. */
public record ChildResponse(
        UUID id,
        String firstName,
        String lastName,
        String fullName,
        LocalDate dateOfBirth,
        String grade,
        String studentNumber,
        String specialNeeds,
        UUID schoolId,
        String schoolName,
        EnrollmentStatus enrollmentStatus,
        String enrollmentNote,
        boolean devicePaired
) {
    public static ChildResponse from(Child c) {
        return new ChildResponse(c.getId(), c.getFirstName(), c.getLastName(), c.getFullName(),
                c.getDateOfBirth(), c.getGrade(), c.getStudentNumber(), c.getSpecialNeeds(),
                c.getSchool() != null ? c.getSchool().getId() : null,
                c.getSchool() != null ? c.getSchool().getName() : null,
                c.getEnrollmentStatus(), c.getEnrollmentNote(),
                c.getKidUser() != null && c.getKidUser().isEnabled());
    }
}

package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Child;
import com.gimble.bus_stop.enums.EnrollmentStatus;

import java.time.LocalDate;
import java.util.UUID;

/** A child as their school sees it (no handover code). */
public record SchoolChildResponse(
        UUID id,
        String fullName,
        LocalDate dateOfBirth,
        String grade,
        String studentNumber,
        EnrollmentStatus enrollmentStatus,
        String parentName,
        String parentPhone,
        String parentEmail
) {
    public static SchoolChildResponse from(Child c) {
        return new SchoolChildResponse(c.getId(), c.getFullName(), c.getDateOfBirth(), c.getGrade(),
                c.getStudentNumber(), c.getEnrollmentStatus(), c.getParent().getFullName(),
                c.getParent().getPhone(), c.getParent().getEmail());
    }
}

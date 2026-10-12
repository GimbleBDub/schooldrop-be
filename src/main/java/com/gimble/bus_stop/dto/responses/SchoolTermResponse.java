package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.SchoolTerm;

import java.time.LocalDate;
import java.util.UUID;

public record SchoolTermResponse(UUID id, String name, LocalDate startDate, LocalDate endDate) {
    public static SchoolTermResponse from(SchoolTerm t) {
        return new SchoolTermResponse(t.getId(), t.getName(), t.getStartDate(), t.getEndDate());
    }
}

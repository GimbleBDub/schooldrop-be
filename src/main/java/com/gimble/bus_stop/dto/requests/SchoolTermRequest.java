package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record SchoolTermRequest(@NotBlank String name, @NotNull LocalDate startDate, @NotNull LocalDate endDate) {
}

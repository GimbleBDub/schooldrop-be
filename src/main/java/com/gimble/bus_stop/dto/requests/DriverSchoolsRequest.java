package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record DriverSchoolsRequest(@NotNull @Size(max = 20) Set<UUID> schoolIds) {
}

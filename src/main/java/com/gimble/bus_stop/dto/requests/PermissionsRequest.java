package com.gimble.bus_stop.dto.requests;

import com.gimble.bus_stop.enums.StaffPermission;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record PermissionsRequest(@NotEmpty Set<StaffPermission> permissions) {
}

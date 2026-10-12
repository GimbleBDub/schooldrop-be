package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.Role;
import com.gimble.bus_stop.enums.StaffPermission;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        Role role,
        boolean enabled,
        boolean locked,
        boolean emailVerified,
        Set<StaffPermission> staffPermissions,
        // Staff still on a temporary password
        boolean passwordChangeRequired,
        LocalDateTime createdAt,
        // Only in the response that created or reset it — share it privately, it's never shown again
        String temporaryPassword
) {
    public static AdminUserResponse from(User u) {
        return withTemporaryPassword(u, null);
    }

    public static AdminUserResponse withTemporaryPassword(User u, String temporaryPassword) {
        return new AdminUserResponse(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole(),
                u.isEnabled(), !u.isAccountNonLocked(), u.isEmailVerified(), Set.copyOf(u.getStaffPermissions()),
                u.isPasswordChangeRequired(), u.getCreatedAt(), temporaryPassword);
    }
}

package com.gimble.bus_stop.dto.requests;

import com.gimble.bus_stop.dto.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(@NotBlank String currentPassword, @StrongPassword String newPassword) {
}

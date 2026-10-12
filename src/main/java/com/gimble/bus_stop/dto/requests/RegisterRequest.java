package com.gimble.bus_stop.dto.requests;

import com.gimble.bus_stop.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.gimble.bus_stop.dto.validation.StrongPassword;
import lombok.Data;

/**
 * Sign-up asks only for what's needed to create the account. Names and role details
 * (school, Omang, ...) are collected afterwards in onboarding (/v1/onboarding).
 */
@Data
public class RegisterRequest {

    // PARENT, DRIVER or SCHOOL — other roles can't self-register
    @NotNull
    private Role accountType;

    @NotBlank
    @Email
    private String email;

    // E.164-ish, e.g. +26771234567
    @NotBlank
    @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "must be a valid phone number")
    private String phone;

    @StrongPassword
    private String password;

    // Optional: identifies the device so each device gets its own refresh token
    @Size(max = 100)
    private String deviceId;

    // Optional: what to call this device in Settings → Signed-in devices, e.g. "Chrome on macOS"
    @Size(max = 100)
    private String deviceName;
}

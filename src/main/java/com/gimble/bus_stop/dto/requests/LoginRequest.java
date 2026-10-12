package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;

    // Optional: identifies the device so each device gets its own refresh token
    @Size(max = 100)
    private String deviceId;

    // Optional: what to call this device in Settings → Signed-in devices, e.g. "Chrome on macOS"
    @Size(max = 100)
    private String deviceName;
}

package com.gimble.bus_stop.dto.requests;

import com.gimble.bus_stop.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** payerPhone is the mobile-money number to prompt (Orange Money, MyZaka, Smega). */
public record PayRequest(
        @NotNull PaymentMethod method,
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "must be a valid phone number") String payerPhone
) {
}

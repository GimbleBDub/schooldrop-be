package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for reject/suspend/cancel actions that must say why. */
public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
}

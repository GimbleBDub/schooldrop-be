package com.gimble.bus_stop.dto.requests;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A verifier's decision on one document. A note is required when rejecting. */
public record DocumentReviewRequest(@NotNull Boolean approve, @Size(max = 255) String note) {
}

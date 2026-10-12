package com.gimble.bus_stop.dto.responses;

import java.time.Instant;

public record PairingCodeResponse(String code, Instant expiresAt) {
}

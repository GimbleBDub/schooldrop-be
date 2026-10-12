package com.gimble.bus_stop.enums;

public enum BookingStatus {
    PENDING_PAYMENT, // seat held until holdExpiresAt
    ACTIVE,
    CANCELLED,
    COMPLETED,
    EXPIRED;         // hold ran out before payment

    public boolean holdsSeat() {
        return this == PENDING_PAYMENT || this == ACTIVE;
    }
}

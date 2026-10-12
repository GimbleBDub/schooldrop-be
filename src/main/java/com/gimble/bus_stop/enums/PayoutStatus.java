package com.gimble.bus_stop.enums;

public enum PayoutStatus {
    PENDING, // created by the weekly batch, waiting for finance to send the money
    PAID,
    FAILED
}

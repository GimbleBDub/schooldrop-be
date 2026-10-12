package com.gimble.bus_stop.enums;

public enum AdHocStatus {
    OPEN,            // broadcast to eligible drivers
    ACCEPTED,        // a driver took it, waiting for the parent to pay
    CONFIRMED,       // paid; the school sees this driver as authorised
    COMPLETED,
    CANCELLED,
    EXPIRED
}

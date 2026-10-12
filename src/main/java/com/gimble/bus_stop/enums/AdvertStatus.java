package com.gimble.bus_stop.enums;

public enum AdvertStatus {
    PENDING_PAYMENT,
    PENDING_REVIEW, // paid, waiting for staff to check it is appropriate for a children's transport app
    ACTIVE,
    REJECTED,
    PAUSED,
    EXPIRED
}

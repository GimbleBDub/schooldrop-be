package com.gimble.bus_stop.enums;

public enum DriverStatus {
    PENDING_DOCUMENTS, // still filling in profile / uploading documents
    UNDER_REVIEW,      // submitted, waiting for a verifier
    APPROVED,          // visible to parents, can take bookings
    REJECTED,
    SUSPENDED
}

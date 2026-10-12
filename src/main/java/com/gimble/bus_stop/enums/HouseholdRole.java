package com.gimble.bus_stop.enums;

public enum HouseholdRole {
    OWNER,    // the parent who created the account: everything, including managing members
    GUARDIAN, // e.g. a partner: children, bookings, payments, trips — not members
    HELPER;   // e.g. a nanny: today's trips and handover confirmations only

    public boolean canManageFamily() {
        return this == OWNER || this == GUARDIAN;
    }
}

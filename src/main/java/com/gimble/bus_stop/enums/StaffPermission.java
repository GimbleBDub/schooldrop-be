package com.gimble.bus_stop.enums;

/** What a platform staff member (Role.ADMIN) may do in the admin panel. */
public enum StaffPermission {
    SUPER_ADMIN, // everything, including managing staff
    VERIFIER,    // review drivers, documents and schools
    SUPPORT,     // users, bookings, incidents
    FINANCE,     // payments, refunds, payouts, invoices, revenue
    MARKETING,   // adverts
    VIEWER;      // read-only access to every area except staff and the audit log

    public String authority() {
        return "PERM_" + name();
    }
}

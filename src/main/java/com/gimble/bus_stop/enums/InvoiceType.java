package com.gimble.bus_stop.enums;

public enum InvoiceType {
    RECEIPT,     // a family paid for transport (to SchoolDrop on the driver's behalf, or to the driver directly)
    INVOICE,     // SchoolDrop's own charges to a driver: monthly listing fee, adverts
    CREDIT_NOTE, // a refund of a receipt or invoice
    STATEMENT    // a driver's payout: what parents paid, SchoolDrop's commission, what was sent
}

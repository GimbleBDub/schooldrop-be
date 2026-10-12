package com.gimble.bus_stop.payments;

import com.gimble.bus_stop.entities.Payment;

/**
 * Published inside the transaction that marks a payment SUCCEEDED. Listeners activate whatever was paid
 * for (booking / ad-hoc pickup) — or refund it if that expired meanwhile.
 */
public record PaymentSucceededEvent(Payment payment) {
}

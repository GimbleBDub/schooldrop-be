package com.gimble.bus_stop.payments;

import com.gimble.bus_stop.entities.Payment;

/**
 * A payment provider (Orange Money, MyZaka, Smega, card acquirer, or an aggregator in front of them).
 * Results arrive asynchronously through the signed webhook, which calls PaymentService.handleResult.
 */
public interface PaymentGateway {

    /** Starts collection; returns the provider's reference and, for card payments, a page to redirect to. */
    Checkout initiate(Payment payment, String payerPhone);

    /** Returns the money to the payer. Throws if the provider refuses. */
    void refund(Payment payment);

    record Checkout(String providerReference, String checkoutUrl) {
    }
}

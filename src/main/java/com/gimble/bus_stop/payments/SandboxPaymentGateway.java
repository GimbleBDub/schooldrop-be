package com.gimble.bus_stop.payments;

import com.gimble.bus_stop.entities.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Development gateway: never moves money. Complete a payment with
 * POST /v1/payments/sandbox/{reference}/complete?success=true.
 */
@Component
@ConditionalOnProperty(name = "app.payments.gateway", havingValue = "sandbox")
@Slf4j
public class SandboxPaymentGateway implements PaymentGateway {

    @Override
    public Checkout initiate(Payment payment, String payerPhone) {
        String reference = "SBX-" + UUID.randomUUID();
        log.info("[sandbox] payment {} of {} {} via {} started", reference, payment.getAmount(),
                payment.getCurrency(), payment.getMethod());
        return new Checkout(reference, "/api/v1/payments/sandbox/" + reference + "/complete");
    }

    @Override
    public void refund(Payment payment) {
        log.info("[sandbox] refunded {} {}", payment.getProviderReference(), payment.getAmount());
    }
}

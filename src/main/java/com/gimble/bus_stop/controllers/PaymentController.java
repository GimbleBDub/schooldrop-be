package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.requests.WebhookPayload;
import com.gimble.bus_stop.dto.responses.PaymentResponse;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.exceptions.InvalidTokenException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.payments.WebhookSignature;
import com.gimble.bus_stop.repositories.PaymentRepository;
import com.gimble.bus_stop.services.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.payments.webhook-secret}")
    private String webhookSecret;

    @Value("${app.payments.gateway}")
    private String gateway;

    /**
     * Gateway callback (public). The body must be signed: X-Signature = hex(HMAC-SHA256(secret, raw body)).
     * Status is SUCCEEDED or FAILED.
     */
    @PostMapping("/webhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void webhook(@RequestHeader(name = "X-Signature", required = false) String signature,
                        @RequestBody byte[] body) {
        if (!WebhookSignature.verify(webhookSecret, body, signature)) {
            log.warn("Rejected payment webhook with a bad signature");
            throw new InvalidTokenException("Invalid signature");
        }
        WebhookPayload payload = objectMapper.readValue(body, WebhookPayload.class);
        paymentService.handleResult(payload.reference(), "SUCCEEDED".equalsIgnoreCase(payload.status()),
                payload.failureReason());
    }

    /** Status of a payment you made (any role) — the app polls this while the provider processes it. */
    @GetMapping("/{paymentId}")
    public PaymentResponse get(@AuthenticationPrincipal User user, @PathVariable java.util.UUID paymentId) {
        return paymentService.getMine(user, paymentId);
    }

    /** Development only: simulate the parent completing (or failing) a sandbox payment. */
    @PostMapping("/sandbox/{reference}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completeSandbox(@AuthenticationPrincipal User user, @PathVariable String reference,
                                @RequestParam(defaultValue = "true") boolean success) {
        if (!"sandbox".equals(gateway)) {
            throw new ResourceNotFoundException("Not found");
        }
        paymentRepository.findByProviderReference(reference)
                .filter(p -> p.getPayer().getId().equals(user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        paymentService.handleResult(reference, success, success ? null : "Declined in sandbox");
    }
}

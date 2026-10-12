package com.gimble.bus_stop.dto.requests;

/** Normalised gateway callback: our provider adapters/aggregator post this shape, HMAC-signed. */
public record WebhookPayload(String reference, String status, String failureReason) {
}

package com.gimble.bus_stop.payments;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private static final byte[] BODY = "{\"reference\":\"SBX-1\",\"status\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsCorrectSignatureInAnyCase() {
        String signature = WebhookSignature.sign("secret", BODY);
        assertThat(WebhookSignature.verify("secret", BODY, signature)).isTrue();
        assertThat(WebhookSignature.verify("secret", BODY, signature.toUpperCase())).isTrue();
    }

    @Test
    void rejectsWrongSecretTamperedBodyOrMissingSignature() {
        String signature = WebhookSignature.sign("secret", BODY);
        assertThat(WebhookSignature.verify("other", BODY, signature)).isFalse();
        assertThat(WebhookSignature.verify("secret", "{}".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(WebhookSignature.verify("secret", BODY, null)).isFalse();
    }
}

package com.gimble.bus_stop.payments;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** HMAC-SHA256 over the raw webhook body, hex-encoded, compared in constant time. */
public final class WebhookSignature {

    private WebhookSignature() {
    }

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static boolean verify(String secret, byte[] body, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(secret, body).getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}

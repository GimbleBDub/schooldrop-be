package com.gimble.bus_stop.billing;

import com.gimble.bus_stop.dto.billing.BillingDtos.DownloadLink;
import com.gimble.bus_stop.exceptions.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Short-lived, signed PDF links. Browsers and phone share sheets can't send the app's bearer token,
 * so the app asks for a link (authenticated) and opens it; the signature is scoped to one document
 * and expires after app.billing.link-minutes.
 */
@Component
public class InvoiceLinks {

    private final byte[] key;
    private final long minutes;
    private final Clock clock;

    public InvoiceLinks(@Value("${app.jwt.secret}") String secret,
                        @Value("${app.billing.link-minutes:10}") long minutes,
                        Clock clock) {
        // Derived key: a link signature can never be confused with a JWT signature
        this.key = hmac(secret.getBytes(StandardCharsets.UTF_8), "schooldrop-invoice-links");
        this.minutes = minutes;
        this.clock = clock;
    }

    public DownloadLink create(UUID invoiceId) {
        Instant expiresAt = Instant.now(clock).plusSeconds(minutes * 60);
        long expires = expiresAt.getEpochSecond();
        // Relative to the API's /v1 base, like every other path the app calls
        String path = "/billing/invoices/" + invoiceId + "/pdf?expires=" + expires + "&sig=" + sign(invoiceId, expires);
        return new DownloadLink(path, expiresAt);
    }

    public void verify(UUID invoiceId, long expires, String signature) {
        boolean valid = signature != null && MessageDigest.isEqual(
                sign(invoiceId, expires).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "This download link isn't valid");
        }
        if (Instant.now(clock).getEpochSecond() > expires) {
            throw new BusinessException(HttpStatus.GONE, "This download link has expired. Open the document in SchoolDrop again.");
        }
    }

    private String sign(UUID invoiceId, long expires) {
        return HexFormat.of().formatHex(hmac(key, invoiceId + ":" + expires));
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}

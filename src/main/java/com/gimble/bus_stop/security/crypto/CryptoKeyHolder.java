package com.gimble.bus_stop.security.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * JPA instantiates converters itself, so the key is handed over through a static holder
 * populated by Spring at startup. Fails fast if the key is missing or the wrong size.
 */
@Component
public class CryptoKeyHolder {

    private static volatile byte[] key;

    public CryptoKeyHolder(@Value("${app.crypto.key}") String base64Key) {
        byte[] decoded = Base64.getDecoder().decode(base64Key);
        if (decoded.length != 32) {
            throw new IllegalStateException("app.crypto.key must be 32 bytes, base64-encoded (openssl rand -base64 32)");
        }
        key = decoded;
    }

    static byte[] key() {
        if (key == null) {
            throw new IllegalStateException("Encryption key not initialised");
        }
        return key;
    }
}

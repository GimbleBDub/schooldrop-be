package com.gimble.bus_stop.security.crypto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptedStringConverterTest {

    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    @BeforeAll
    static void initKey() {
        new CryptoKeyHolder(Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        String stored = converter.convertToDatabaseColumn("123456789");
        assertThat(stored).doesNotContain("123456789");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("123456789");
    }

    @Test
    void sameValueEncryptsDifferentlyEachTime() {
        assertThat(converter.convertToDatabaseColumn("x")).isNotEqualTo(converter.convertToDatabaseColumn("x"));
    }

    @Test
    void nullsPassThrough() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void rejectsWrongSizedKey() {
        assertThatThrownBy(() -> new CryptoKeyHolder(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}

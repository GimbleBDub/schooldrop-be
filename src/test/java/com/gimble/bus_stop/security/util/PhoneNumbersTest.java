package com.gimble.bus_stop.security.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumbersTest {

    @Test
    void oneSpellingPerBotswanaNumber() {
        assertThat(PhoneNumbers.normalize("71 234 567")).isEqualTo("+26771234567");
        assertThat(PhoneNumbers.normalize("+267 7123-4567")).isEqualTo("+26771234567");
        assertThat(PhoneNumbers.normalize("26771234567")).isEqualTo("+26771234567");
        assertThat(PhoneNumbers.normalize("0026771234567")).isEqualTo("+26771234567");
    }

    @Test
    void foreignNumbersKeepTheirCode() {
        assertThat(PhoneNumbers.normalize("+27 82 123 4567")).isEqualTo("+27821234567");
        assertThat(PhoneNumbers.normalize(" ")).isNull();
        assertThat(PhoneNumbers.normalize(null)).isNull();
    }
}

package com.gimble.bus_stop.dto.responses;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DriverProfileResponseTest {

    @Test
    void masksAllButLastThree() {
        assertThat(DriverProfileResponse.mask("123456789")).isEqualTo("******789");
        assertThat(DriverProfileResponse.mask("12")).isEqualTo("12");
        assertThat(DriverProfileResponse.mask(null)).isNull();
    }
}

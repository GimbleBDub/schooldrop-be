package com.gimble.bus_stop.billing;

import com.gimble.bus_stop.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceLinksTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-123";
    private static final UUID ID = UUID.randomUUID();

    private static InvoiceLinks at(Instant now) {
        return new InvoiceLinks(SECRET, 10, Clock.fixed(now, ZoneId.of("Africa/Gaborone")));
    }

    @Test
    void signedLinkWorksUntilItExpires() {
        Instant now = Instant.parse("2026-10-08T10:00:00Z");
        String path = at(now).create(ID).path();
        long expires = Long.parseLong(path.replaceAll(".*expires=(\\d+).*", "$1"));
        String sig = path.replaceAll(".*sig=", "");

        at(now.plusSeconds(599)).verify(ID, expires, sig);
        assertThatThrownBy(() -> at(now.plusSeconds(601)).verify(ID, expires, sig))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.GONE);
    }

    @Test
    void signatureIsScopedToOneDocumentAndExpiry() {
        Instant now = Instant.parse("2026-10-08T10:00:00Z");
        String path = at(now).create(ID).path();
        long expires = Long.parseLong(path.replaceAll(".*expires=(\\d+).*", "$1"));
        String sig = path.replaceAll(".*sig=", "");

        assertThatThrownBy(() -> at(now).verify(UUID.randomUUID(), expires, sig)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> at(now).verify(ID, expires + 3600, sig)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> at(now).verify(ID, expires, null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void vatOnlyOnceRegistered() {
        BillingSettings settings = new BillingSettings();
        ReflectionTestUtils.setField(settings, "vatPercent", new BigDecimal("14"));
        ReflectionTestUtils.setField(settings, "vatNumber", "");
        assertThat(settings.vatIncludedIn(new BigDecimal("114.00"))).isEqualByComparingTo("0");

        ReflectionTestUtils.setField(settings, "vatNumber", "P01234567");
        // Prices include VAT: P114 = P100 + P14 VAT
        assertThat(settings.vatIncludedIn(new BigDecimal("114.00"))).isEqualByComparingTo("14.00");
        assertThat(settings.vatIncludedIn(new BigDecimal("50.00"))).isEqualByComparingTo("6.14");
    }
}

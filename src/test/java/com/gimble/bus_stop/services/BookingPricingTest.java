package com.gimble.bus_stop.services;

import com.gimble.bus_stop.enums.TripCoverage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BookingPricingTest {

    // 2026-10-05 is a Monday
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    @Test
    void countsWeekdaysOnly() {
        assertThat(BookingPricing.schoolDays(MONDAY, MONDAY.plusDays(6))).isEqualTo(5);
        assertThat(BookingPricing.schoolDays(MONDAY.plusDays(5), MONDAY.plusDays(6))).isZero();
        assertThat(BookingPricing.schoolDays(MONDAY, MONDAY)).isEqualTo(1);
    }

    @Test
    void bothLegsDoubleTheTrips() {
        assertThat(BookingPricing.tripCount(MONDAY, MONDAY.plusDays(4), TripCoverage.BOTH)).isEqualTo(10);
        assertThat(BookingPricing.tripCount(MONDAY, MONDAY.plusDays(4), TripCoverage.MORNING)).isEqualTo(5);
    }

    @Test
    void perTripAmountIsRoundedToThebe() {
        assertThat(BookingPricing.perTripAmount(new BigDecimal("12.345"), 3)).isEqualByComparingTo("37.04");
    }

    @Test
    void feeAndDriverShareAlwaysAddUp() {
        BigDecimal amount = new BigDecimal("333.33");
        BigDecimal fee = BookingPricing.platformFee(amount, new BigDecimal("10"));
        assertThat(fee).isEqualByComparingTo("33.33");
        assertThat(fee.add(amount.subtract(fee))).isEqualByComparingTo(amount);
    }
}

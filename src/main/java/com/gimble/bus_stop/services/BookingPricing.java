package com.gimble.bus_stop.services;

import com.gimble.bus_stop.enums.TripCoverage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;

/** Pure pricing/period arithmetic, kept separate so it is easy to unit test. */
public final class BookingPricing {

    private BookingPricing() {
    }

    /** Monday–Friday in [start, end]. Public holidays are not excluded yet. */
    public static int schoolDays(LocalDate start, LocalDate end) {
        int days = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (isSchoolDay(d)) {
                days++;
            }
        }
        return days;
    }

    public static boolean isSchoolDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    public static int tripCount(LocalDate start, LocalDate end, TripCoverage coverage) {
        return schoolDays(start, end) * coverage.legsPerDay();
    }

    public static BigDecimal perTripAmount(BigDecimal pricePerTrip, int trips) {
        return pricePerTrip.multiply(BigDecimal.valueOf(trips)).setScale(2, RoundingMode.HALF_UP);
    }

    /** SchoolDrop's cut, rounded to the thebe. The driver gets amount - fee, so the two always add up. */
    public static BigDecimal platformFee(BigDecimal amount, BigDecimal feePercent) {
        return amount.multiply(feePercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}

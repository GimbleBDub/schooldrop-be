package com.gimble.bus_stop.dto.requests;

import com.gimble.bus_stop.enums.BillingPlan;
import com.gimble.bus_stop.enums.PaymentMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Which period is booked depends on the plan:
 * MONTHLY needs month (e.g. "2026-11"), TERMLY needs termId, PER_TRIP needs startDate and endDate.
 */
public record BookingRequest(
        @NotNull UUID offerId,
        @NotNull UUID childId,
        @NotNull BillingPlan plan,
        YearMonth month,
        UUID termId,
        LocalDate startDate,
        LocalDate endDate,
        @NotBlank @Size(max = 500) String pickupAddress,
        Double pickupLatitude,
        Double pickupLongitude,
        @Size(max = 500) String notes,
        // APP (default): pay through SchoolDrop. OFFLINE: pay the driver directly; the driver confirms receipt.
        PaymentMode paymentMode
) {
}

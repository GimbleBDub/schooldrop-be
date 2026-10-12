package com.gimble.bus_stop.dto.responses;

import java.math.BigDecimal;

public record DashboardResponse(
        long parents,
        long drivers,
        long schoolStaff,
        long kidsPaired,
        long driversAwaitingReview,
        long schoolsAwaitingReview,
        long activeBookings,
        long tripsInProgress,
        long openIncidents,
        BigDecimal grossLast30Days,
        BigDecimal feesLast30Days,
        BigDecimal pendingPayouts
) {
}

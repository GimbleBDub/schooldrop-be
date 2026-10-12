package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.DriverSubscription;
import com.gimble.bus_stop.enums.SubscriptionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The driver's monthly listing fee: whether they're live, until when, and what they've paid. */
public record SubscriptionResponse(
        // What the next month costs: the per-driver fee × drivers covered
        BigDecimal monthlyFee,
        BigDecimal feePerDriver,
        int drivers,
        // Team drivers see their team's status; only the owner (or a driver on their own) pays
        boolean canPay,
        String teamName,
        boolean active,
        LocalDate paidUntil,
        long daysLeft,
        List<Period> history
) {
    public record Period(UUID id, LocalDate periodStart, LocalDate periodEnd, BigDecimal amount, int drivers,
                         SubscriptionStatus status, Instant paidAt) {
        public static Period from(DriverSubscription s) {
            return new Period(s.getId(), s.getPeriodStart(), s.getPeriodEnd(), s.getAmount(), s.getDrivers(), s.getStatus(),
                    s.getPaidAt());
        }
    }
}

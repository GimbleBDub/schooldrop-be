package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Payment;
import com.gimble.bus_stop.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EarningsResponse(
        // Earned and due in the next payout
        BigDecimal availableBalance,
        // Paid by parents for service that hasn't started yet
        BigDecimal upcomingBalance,
        String currency,
        List<Line> recent,
        List<PayoutResponse> payouts
) {
    public record Line(UUID paymentId, String description, BigDecimal amount, PaymentStatus status,
                       LocalDate availableOn, boolean paidOut, Instant paidAt) {
        public static Line from(Payment p) {
            String description = p.getBooking() != null
                    ? p.getBooking().getChild().getFirstName() + " — " + p.getBooking().getPlan()
                    : p.getAdHocRequest().getChild().getFirstName() + " — once-off pickup";
            return new Line(p.getId(), description, p.getDriverAmount(), p.getStatus(), p.getEarningAvailableOn(),
                    p.getPayout() != null, p.getPaidAt());
        }
    }
}

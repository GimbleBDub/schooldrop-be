package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Payout;
import com.gimble.bus_stop.enums.PaymentMethod;
import com.gimble.bus_stop.enums.PayoutStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PayoutResponse(
        UUID id,
        UUID driverId,
        String driverName,
        BigDecimal amount,
        PaymentMethod method,
        // Full account for finance staff, masked for the driver
        String account,
        PayoutStatus status,
        String reference,
        String failureReason,
        Instant processedAt,
        Instant createdAt
) {
    public static PayoutResponse from(Payout p, boolean revealAccount) {
        String account = p.getAccount();
        if (!revealAccount && account != null && account.length() > 3) {
            account = "*".repeat(account.length() - 3) + account.substring(account.length() - 3);
        }
        return new PayoutResponse(p.getId(), p.getDriver().getId(), p.getDriver().getUser().getFullName(),
                p.getAmount(), p.getMethod(), account, p.getStatus(), p.getReference(), p.getFailureReason(),
                p.getProcessedAt(), p.getCreatedAt());
    }
}

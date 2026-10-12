package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Payment;
import com.gimble.bus_stop.enums.PaymentMethod;
import com.gimble.bus_stop.enums.PaymentPurpose;
import com.gimble.bus_stop.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID bookingId,
        UUID adHocRequestId,
        PaymentPurpose purpose,
        // Paid to the driver directly (cash / EFT), confirmed by the driver
        boolean offline,
        BigDecimal amount,
        // Fee split is only included for admins
        BigDecimal platformFee,
        BigDecimal driverAmount,
        String currency,
        PaymentMethod method,
        PaymentStatus status,
        String providerReference,
        String checkoutUrl,
        String failureReason,
        String payerName,
        String driverName,
        Instant paidAt,
        Instant refundedAt,
        Instant createdAt
) {
    public static PaymentResponse from(Payment p, boolean includeSplit) {
        return new PaymentResponse(p.getId(),
                p.getBooking() != null ? p.getBooking().getId() : null,
                p.getAdHocRequest() != null ? p.getAdHocRequest().getId() : null,
                p.getPurpose(), p.isOffline(),
                p.getAmount(),
                includeSplit ? p.getPlatformFee() : null,
                includeSplit ? p.getDriverAmount() : null,
                p.getCurrency(), p.getMethod(), p.getStatus(), p.getProviderReference(), p.getCheckoutUrl(),
                p.getFailureReason(), p.getPayer().getFullName(), p.getDriver().getUser().getFullName(),
                p.getPaidAt(), p.getRefundedAt(), p.getCreatedAt());
    }
}

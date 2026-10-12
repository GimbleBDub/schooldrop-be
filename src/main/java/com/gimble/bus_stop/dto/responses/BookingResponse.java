package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Booking;
import com.gimble.bus_stop.enums.BillingPlan;
import com.gimble.bus_stop.enums.BookingStatus;
import com.gimble.bus_stop.enums.PaymentMode;
import com.gimble.bus_stop.enums.TripCoverage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID childId,
        String childName,
        UUID offerId,
        String schoolName,
        String driverName,
        String driverPhone,
        String vehicle,
        TripCoverage coverage,
        BillingPlan plan,
        String termName,
        LocalDate startDate,
        LocalDate endDate,
        Integer tripCount,
        String pickupAddress,
        Double pickupLatitude,
        Double pickupLongitude,
        String notes,
        BigDecimal amount,
        BookingStatus status,
        PaymentMode paymentMode,
        Instant offlinePaidClaimedAt,
        Instant holdExpiresAt,
        String cancelReason,
        String parentName,
        String parentPhone,
        Instant createdAt
) {
    public static BookingResponse from(Booking b) {
        var offer = b.getOffer();
        var driverUser = offer.getDriver().getUser();
        return new BookingResponse(b.getId(), b.getChild().getId(), b.getChild().getFullName(), offer.getId(),
                offer.getSchool().getName(), driverUser.getFullName(), driverUser.getPhone(),
                offer.getVehicle().getDisplayName(), offer.getCoverage(), b.getPlan(),
                b.getTerm() != null ? b.getTerm().getName() : null, b.getStartDate(), b.getEndDate(),
                b.getTripCount(), b.getPickupAddress(), b.getPickupLatitude(), b.getPickupLongitude(), b.getNotes(),
                b.getAmount(), b.getStatus(), b.getPaymentMode(), b.getOfflinePaidClaimedAt(), b.getHoldExpiresAt(), b.getCancelReason(),
                b.getParent().getFullName(), b.getParent().getPhone(), b.getCreatedAt());
    }
}

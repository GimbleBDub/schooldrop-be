package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.VerificationDocument;
import com.gimble.bus_stop.enums.DocumentStatus;
import com.gimble.bus_stop.enums.DocumentType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        DocumentType type,
        UUID vehicleId,
        String vehiclePlate,
        DocumentStatus status,
        String referenceNumber,
        LocalDate issuedOn,
        LocalDate expiresOn,
        String reviewNote,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Instant uploadedAt,
        Instant reviewedAt
) {
    public static DocumentResponse from(VerificationDocument d) {
        return new DocumentResponse(d.getId(), d.getType(),
                d.getVehicle() != null ? d.getVehicle().getId() : null,
                d.getVehicle() != null ? d.getVehicle().getPlateNumber() : null,
                d.getStatus(), d.getReferenceNumber(), d.getIssuedOn(), d.getExpiresOn(), d.getReviewNote(),
                d.getOriginalFilename(), d.getContentType(), d.getSizeBytes(), d.getUploadedAt(), d.getReviewedAt());
    }
}

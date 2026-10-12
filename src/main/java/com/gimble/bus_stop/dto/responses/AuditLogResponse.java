package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.AuditLog;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(UUID id, UUID actorId, String actorName, String action, String targetType,
                               UUID targetId, String details, Instant createdAt) {
    public static AuditLogResponse from(AuditLog a) {
        return new AuditLogResponse(a.getId(),
                a.getActor() != null ? a.getActor().getId() : null,
                a.getActor() != null ? a.getActor().getFullName() : "System",
                a.getAction(), a.getTargetType(), a.getTargetId(), a.getDetails(), a.getCreatedAt());
    }
}

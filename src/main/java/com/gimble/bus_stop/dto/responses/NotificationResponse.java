package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String type,
        String title,
        String body,
        UUID referenceId,
        boolean read,
        Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(),
                n.getReferenceId(), n.getReadAt() != null, n.getCreatedAt());
    }
}

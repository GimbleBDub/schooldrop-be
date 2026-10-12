package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Invite;

import java.time.Instant;
import java.util.UUID;

/** code is only present right after creation; afterwards only its hash exists. */
public record InviteResponse(UUID id, String role, String inviteeName, String code, Instant expiresAt) {
    public static InviteResponse from(Invite i) {
        return new InviteResponse(i.getId(), i.getRole(), i.getInviteeName(), null, i.getExpiresAt());
    }

    public static InviteResponse withCode(Invite i, String code) {
        return new InviteResponse(i.getId(), i.getRole(), i.getInviteeName(), code, i.getExpiresAt());
    }
}

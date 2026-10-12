package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Household;
import com.gimble.bus_stop.entities.HouseholdMember;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.HouseholdRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HouseholdResponse(
        UUID id,
        String name,
        HouseholdRole myRole,
        List<Member> members,
        List<InviteResponse> pendingInvites
) {
    public record Member(UUID id, UUID userId, String name, String phone, HouseholdRole role, boolean you, Instant joinedAt) {
    }

    public static HouseholdResponse from(Household h, List<HouseholdMember> members, User viewer, HouseholdRole myRole,
                                         List<InviteResponse> invites) {
        return new HouseholdResponse(h.getId(), h.getName(), myRole,
                members.stream().map(m -> new Member(m.getId(), m.getUser().getId(), m.getUser().getFullName(),
                        m.getUser().getPhone(), m.getRole(), m.getUser().getId().equals(viewer.getId()), m.getJoinedAt()))
                        .toList(),
                invites);
    }
}

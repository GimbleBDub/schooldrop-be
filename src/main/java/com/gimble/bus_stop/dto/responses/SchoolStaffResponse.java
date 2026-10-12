package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.SchoolStaff;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.SchoolRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SchoolStaffResponse(SchoolRole myRole, List<Member> staff, List<InviteResponse> pendingInvites) {

    public record Member(UUID id, UUID userId, String name, String email, String phone, SchoolRole role, String title,
                         boolean you, Instant joinedAt) {
    }

    public static SchoolStaffResponse from(List<SchoolStaff> staff, User viewer, SchoolRole myRole, List<InviteResponse> invites) {
        return new SchoolStaffResponse(myRole, staff.stream().map(s -> new Member(s.getId(), s.getUser().getId(),
                s.getUser().getFullName(), s.getUser().getEmail(), s.getUser().getPhone(), s.getRole(), s.getTitle(),
                s.getUser().getId().equals(viewer.getId()), s.getCreatedAt())).toList(), invites);
    }
}

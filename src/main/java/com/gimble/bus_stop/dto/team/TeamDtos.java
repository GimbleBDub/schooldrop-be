package com.gimble.bus_stop.dto.team;

import com.gimble.bus_stop.dto.responses.InviteResponse;
import com.gimble.bus_stop.entities.DriverProfile;
import com.gimble.bus_stop.entities.Operator;
import com.gimble.bus_stop.enums.DriverStatus;
import com.gimble.bus_stop.enums.OperatorKind;
import com.gimble.bus_stop.enums.OperatorRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Driver teams and transport companies (/v1/driver/team). */
public final class TeamDtos {

    private TeamDtos() {
    }

    /** Start a team / company. The caller becomes its owner. */
    public record CreateTeamRequest(@NotBlank @Size(max = 160) String name,
                                    @NotNull OperatorKind kind,
                                    @Size(max = 60) String registrationNumber,
                                    // false: the owner only runs the business (no licence needed)
                                    Boolean ownerDrives) {
    }

    public record UpdateTeamRequest(@NotBlank @Size(max = 160) String name, @Size(max = 60) String registrationNumber) {
    }

    public record InviteRequest(@NotNull OperatorRole role, @Size(max = 120) String name) {
    }

    public record MemberUpdateRequest(@NotNull OperatorRole role, @NotNull Boolean drives) {
    }

    public record JoinRequest(@NotBlank @Size(min = 6, max = 20) String code) {
    }

    public record AssignRequest(@NotNull UUID driverId) {
    }

    public record Member(UUID driverId, UUID userId, String name, String phone, OperatorRole role, boolean drives,
                         DriverStatus status, boolean you, int activeRoutes, Instant since) {
        public static Member of(DriverProfile d, boolean you, int activeRoutes) {
            return new Member(d.getId(), d.getUser().getId(), d.getUser().getFullName(), d.getUser().getPhone(),
                    d.getOperatorRole(), d.isDrives(), d.getStatus(), you, activeRoutes, d.getCreatedAt());
        }
    }

    public record TeamResponse(UUID id, String name, OperatorKind kind, String registrationNumber,
                               OperatorRole myRole, String ownerName, DriverStatus ownerStatus,
                               List<Member> members, List<InviteResponse> invites,
                               int vehicles, int drivers,
                               // Listing fee: per driver, paid by the owner
                               BigDecimal monthlyFee, LocalDate listedUntil) {
    }

    /** What every team member's profile carries about their team. */
    public record TeamCard(UUID id, String name, OperatorKind kind, OperatorRole role, String ownerName) {
        public static TeamCard of(DriverProfile d) {
            Operator o = d.getOperator();
            return o == null ? null
                    : new TeamCard(o.getId(), o.getName(), o.getKind(), d.getOperatorRole(), o.getOwner().getUser().getFullName());
        }
    }
}

package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.responses.InviteResponse;
import com.gimble.bus_stop.dto.team.TeamDtos.*;
import com.gimble.bus_stop.entities.*;
import com.gimble.bus_stop.enums.*;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.exceptions.UnauthorizedException;
import com.gimble.bus_stop.repositories.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Teams of drivers and transport companies. The owner's profile is the business (payouts, listing fee
 * per driver, adverts); owners and managers run vehicles and routes; drivers drive what they're assigned.
 * Everyone is vetted as an individual. Money earned while in a team belongs to the team (fixed per payment).
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private final OperatorRepository operatorRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final VehicleRepository vehicleRepository;
    private final TransportOfferRepository offerRepository;
    private final TripRepository tripRepository;
    private final InviteRepository inviteRepository;
    private final InviteService inviteService;
    private final DriverService driverService;
    private final SubscriptionService subscriptionService;
    private final RefreshTokenService refreshTokenService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    // ─── Starting and joining ────────────────────────────────────────────────

    /** Turns a driver into the owner of a new team / company. Their vehicles become the fleet. */
    @Transactional
    public TeamResponse create(User user, CreateTeamRequest request) {
        DriverProfile owner = driverService.requireProfile(user);
        if (owner.isInTeam()) {
            throw BusinessException.conflict("You're already part of " + owner.getOperator().getName());
        }
        Operator operator = createFor(owner, request);
        auditService.record(user, "TEAM_CREATED", "Operator", operator.getId(), operator.getName());
        return get(user);
    }

    @Transactional
    public Operator createFor(DriverProfile owner, CreateTeamRequest request) {
        String name = request.name().trim();
        if (operatorRepository.existsByNameIgnoreCase(name)) {
            throw BusinessException.conflict("A team called \"" + name + "\" already exists on SchoolDrop");
        }
        if (request.kind() == OperatorKind.COMPANY && (request.registrationNumber() == null || request.registrationNumber().isBlank())) {
            throw BusinessException.badRequest("Enter the company's CIPA registration number");
        }
        Operator operator = operatorRepository.save(Operator.builder()
                .name(name)
                .kind(request.kind())
                .registrationNumber(blankToNull(request.registrationNumber()))
                .owner(owner)
                .build());
        owner.setOperator(operator);
        owner.setOperatorRole(OperatorRole.OWNER);
        owner.setDrives(request.ownerDrives() == null || request.ownerDrives());
        vehicleRepository.findByDriverIdOrderByCreatedAt(owner.getId()).forEach(v -> v.setOperator(operator));
        return operator;
    }

    /**
     * Joins a team with an invite code. A driver who already worked on their own brings their vehicles and
     * routes along; earnings already made stay theirs, new ones go to the team.
     */
    @Transactional
    public void join(User user, String code) {
        DriverProfile driver = driverService.requireProfile(user);
        if (driver.isInTeam()) {
            throw BusinessException.conflict("Leave " + driver.getOperator().getName() + " before joining another team");
        }
        Invite invite = inviteService.redeem(code, InviteKind.TEAM, user);
        Operator operator = invite.getOperator();
        DriverProfile owner = operator.getOwner();
        driver.setOperator(operator);
        driver.setOperatorRole(OperatorRole.valueOf(invite.getRole()));
        driver.setDrives(true);
        // Vehicles and routes come along; their schools become team schools so the routes stay valid
        vehicleRepository.findByDriverIdOrderByCreatedAt(driver.getId()).forEach(v -> v.setOperator(operator));
        driver.getSchools().forEach(s -> owner.getSchools().add(s));
        auditService.record(user, "TEAM_JOINED", "Operator", operator.getId(), invite.getRole());
        notificationService.notify(owner.getUser(), "TEAM", user.getFullName() + " joined " + operator.getName(),
                "They still need to be verified by SchoolDrop before they can drive your routes.", driver.getId());
    }

    // ─── Viewing ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TeamResponse get(User user) {
        DriverProfile me = requireMember(user);
        Operator operator = me.getOperator();
        List<DriverProfile> team = driverProfileRepository.findTeam(operator.getId());
        List<TransportOffer> routes = offerRepository.findByOperatorId(operator.getId());
        List<Member> members = team.stream()
                .sorted((a, b) -> Integer.compare(a.getOperatorRole().ordinal(), b.getOperatorRole().ordinal()))
                .map(d -> Member.of(d, d.getId().equals(me.getId()),
                        (int) routes.stream().filter(o -> o.isActive() && o.getDriver().getId().equals(d.getId())).count()))
                .toList();
        List<InviteResponse> invites = me.managesRoutes()
                ? inviteRepository.findOpenForOperator(operator.getId(), Instant.now(clock)).stream().map(InviteResponse::from).toList()
                : List.of();
        DriverProfile owner = operator.getOwner();
        return new TeamResponse(operator.getId(), operator.getName(), operator.getKind(), operator.getRegistrationNumber(),
                me.getOperatorRole(), owner.getUser().getFullName(), owner.getStatus(), members, invites,
                vehicleRepository.findByOperatorIdAndActiveTrue(operator.getId()).size(),
                drivingMembers(operator), subscriptionService.feeFor(owner), owner.getSubscriptionPaidUntil());
    }

    // ─── Managing (owner / managers) ─────────────────────────────────────────

    @Transactional
    public TeamResponse update(User user, UpdateTeamRequest request) {
        DriverProfile me = requireOwner(user);
        Operator operator = me.getOperator();
        String name = request.name().trim();
        if (!name.equalsIgnoreCase(operator.getName()) && operatorRepository.existsByNameIgnoreCase(name)) {
            throw BusinessException.conflict("A team called \"" + name + "\" already exists on SchoolDrop");
        }
        operator.setName(name);
        operator.setRegistrationNumber(blankToNull(request.registrationNumber()));
        return get(user);
    }

    /** Managers invite drivers; only the owner invites managers. Codes work once, for 7 days. */
    @Transactional
    public InviteService.Issued invite(User user, OperatorRole role, String name) {
        DriverProfile me = requireManager(user);
        if (role == OperatorRole.OWNER) {
            throw BusinessException.badRequest("A team has one owner");
        }
        if (role == OperatorRole.MANAGER && me.getOperatorRole() != OperatorRole.OWNER) {
            throw new UnauthorizedException("Only the owner can add managers");
        }
        return inviteService.create(InviteKind.TEAM, null, null, me.getOperator(), role.name(), name, user);
    }

    @Transactional
    public void revokeInvite(User user, UUID inviteId) {
        DriverProfile me = requireManager(user);
        Invite invite = inviteRepository.findById(inviteId)
                .filter(i -> i.getOperator() != null && i.getOperator().getId().equals(me.getOperator().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Invite not found"));
        invite.setRevokedAt(Instant.now(clock));
    }

    /** Owner only: make someone a manager or a driver, and say whether they drive. */
    @Transactional
    public TeamResponse updateMember(User user, UUID driverId, MemberUpdateRequest request) {
        DriverProfile me = requireOwner(user);
        DriverProfile member = requireTeamMate(me, driverId);
        if (request.role() == OperatorRole.OWNER || (member.getId().equals(me.getId()) && request.role() != OperatorRole.OWNER)) {
            throw BusinessException.badRequest("The owner stays the owner");
        }
        if (!request.drives() && request.role() == OperatorRole.DRIVER) {
            throw BusinessException.badRequest("A driver drives — make them a manager if they only run things");
        }
        if (!request.drives() && hasRoutes(member)) {
            throw BusinessException.conflict("Reassign " + member.getUser().getFirstName() + "'s routes first");
        }
        member.setOperatorRole(member.getId().equals(me.getId()) ? OperatorRole.OWNER : request.role());
        member.setDrives(request.drives());
        auditService.record(user, "TEAM_MEMBER_UPDATED", "DriverProfile", member.getId(), request.role() + " drives=" + request.drives());
        return get(user);
    }

    /** Owner removes anyone; managers remove drivers. Their routes must be handed to someone else first. */
    @Transactional
    public void removeMember(User user, UUID driverId) {
        DriverProfile me = requireManager(user);
        DriverProfile member = requireTeamMate(me, driverId);
        if (member.getOperatorRole() == OperatorRole.OWNER) {
            throw BusinessException.badRequest("The owner can't be removed");
        }
        if (member.getOperatorRole() == OperatorRole.MANAGER && me.getOperatorRole() != OperatorRole.OWNER) {
            throw new UnauthorizedException("Only the owner can remove managers");
        }
        detach(member);
        // Their app should stop showing the team's routes and children's addresses at once
        member.getUser().setSessionsValidFrom(Instant.now(clock));
        refreshTokenService.revokeAllTokensForUser(member.getUser());
        auditService.record(user, "TEAM_MEMBER_REMOVED", "DriverProfile", member.getId(), null);
        notificationService.notify(member.getUser(), "TEAM", "You were removed from " + me.getOperator().getName(),
                "You can keep driving on SchoolDrop on your own, or join another team.", null);
    }

    @Transactional
    public void leave(User user) {
        DriverProfile me = requireMember(user);
        if (me.getOperatorRole() == OperatorRole.OWNER) {
            throw BusinessException.badRequest("The owner can't leave their own team. Contact support to close or transfer it.");
        }
        Operator operator = me.getOperator();
        detach(me);
        auditService.record(user, "TEAM_LEFT", "Operator", operator.getId(), null);
        notificationService.notify(operator.getOwner().getUser(), "TEAM", user.getFullName() + " left " + operator.getName(), "", me.getId());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Back to working on their own. Blocked while they drive any of the team's active routes or have runs
     * scheduled. Vehicles they registered go with them unless a team route still uses them.
     */
    private void detach(DriverProfile member) {
        if (hasRoutes(member)) {
            throw BusinessException.conflict("Hand " + member.getUser().getFirstName() + "'s routes to another driver first");
        }
        boolean scheduledRuns = !tripRepository.findForDriverOn(member.getId(), LocalDate.now(clock)).stream()
                .filter(t -> t.getStatus() == TripStatus.SCHEDULED || t.getStatus() == TripStatus.IN_PROGRESS).toList().isEmpty();
        if (scheduledRuns) {
            throw BusinessException.conflict(member.getUser().getFirstName() + " has runs today — reassign them first");
        }
        for (Vehicle v : vehicleRepository.findByDriverIdOrderByCreatedAt(member.getId())) {
            boolean usedByTeam = offerRepository.findByVehicleId(v.getId()).stream().anyMatch(TransportOffer::isActive);
            if (!usedByTeam) {
                v.setOperator(null);
            }
        }
        member.setOperator(null);
        member.setOperatorRole(null);
        member.setDrives(true);
    }

    private boolean hasRoutes(DriverProfile member) {
        return offerRepository.findByDriverId(member.getId()).stream().anyMatch(TransportOffer::isActive);
    }

    int drivingMembers(Operator operator) {
        return (int) driverProfileRepository.findTeam(operator.getId()).stream().filter(DriverProfile::isDrives).count();
    }

    public DriverProfile requireMember(User user) {
        DriverProfile me = driverService.requireProfile(user);
        if (!me.isInTeam()) {
            throw BusinessException.badRequest("You're not part of a team");
        }
        return me;
    }

    public DriverProfile requireManager(User user) {
        DriverProfile me = requireMember(user);
        if (!me.getOperatorRole().canManage()) {
            throw new UnauthorizedException("Only the team owner and managers can do this");
        }
        return me;
    }

    private DriverProfile requireOwner(User user) {
        DriverProfile me = requireMember(user);
        if (me.getOperatorRole() != OperatorRole.OWNER) {
            throw new UnauthorizedException("Only the team owner can do this");
        }
        return me;
    }

    private DriverProfile requireTeamMate(DriverProfile me, UUID driverId) {
        DriverProfile member = driverProfileRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Team member not found"));
        if (!member.getId().equals(me.getId()) && !me.isTeamMate(member)) {
            throw new ResourceNotFoundException("Team member not found");
        }
        return member;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.responses.HouseholdResponse;
import com.gimble.bus_stop.dto.responses.InviteResponse;
import com.gimble.bus_stop.entities.Household;
import com.gimble.bus_stop.entities.HouseholdMember;
import com.gimble.bus_stop.entities.Invite;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.HouseholdRole;
import com.gimble.bus_stop.enums.InviteKind;
import com.gimble.bus_stop.enums.Role;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.exceptions.UnauthorizedException;
import com.gimble.bus_stop.repositories.ChildRepository;
import com.gimble.bus_stop.repositories.HouseholdMemberRepository;
import com.gimble.bus_stop.repositories.HouseholdRepository;
import com.gimble.bus_stop.repositories.InviteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A parent account is a family. Children and bookings belong to the owner; guardians (e.g. a partner)
 * act on the owner's behalf, helpers (e.g. a nanny) only see today's trips and confirm handovers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HouseholdService {

    private final HouseholdRepository householdRepository;
    private final HouseholdMemberRepository memberRepository;
    private final InviteRepository inviteRepository;
    private final ChildRepository childRepository;
    private final InviteService inviteService;
    private final RefreshTokenService refreshTokenService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    /** Who the user acts for, and with which role. */
    public record Context(Household household, User owner, User actor, HouseholdRole role) {
        public boolean canManageFamily() {
            return role.canManageFamily();
        }
    }

    // ─── Resolving the acting family ─────────────────────────────────────────

    @Transactional
    public Context resolve(User user) {
        if (user.getRole() != Role.PARENT) {
            throw new UnauthorizedException("Only parent accounts belong to a family");
        }
        HouseholdMember member = memberRepository.findByUserId(user.getId())
                .orElseGet(() -> createFor(user));
        Household household = member.getHousehold();
        return new Context(household, household.getOwner(), user, member.getRole());
    }

    /** Children, bookings, payments, once-off pickups: owner and guardians. */
    @Transactional
    public User requireFamilyManager(User user) {
        Context context = resolve(user);
        if (!context.canManageFamily()) {
            throw new UnauthorizedException("Helpers can see today's trips and confirm handovers only");
        }
        return context.owner();
    }

    /** Trips and handover confirmations: everyone in the family. */
    @Transactional
    public User requireMember(User user) {
        return resolve(user).owner();
    }

    @Transactional
    public HouseholdMember createFor(User owner) {
        String name = (owner.getLastName() != null ? owner.getLastName() : "My") + " family";
        Household household = householdRepository.save(Household.builder().name(name).owner(owner).build());
        return memberRepository.save(HouseholdMember.builder()
                .household(household).user(owner).role(HouseholdRole.OWNER).build());
    }

    // ─── Members & invites (owner) ───────────────────────────────────────────

    @Transactional
    public HouseholdResponse get(User user) {
        Context context = resolve(user);
        List<InviteResponse> invites = context.role() == HouseholdRole.OWNER
                ? inviteRepository.findOpenForHousehold(context.household().getId(), Instant.now(clock)).stream()
                        .map(InviteResponse::from).toList()
                : List.of();
        return HouseholdResponse.from(context.household(), memberRepository.findByHouseholdId(context.household().getId()),
                user, context.role(), invites);
    }

    @Transactional
    public InviteService.Issued invite(User user, HouseholdRole role, String inviteeName) {
        Context context = requireOwner(user);
        if (role == HouseholdRole.OWNER) {
            throw BusinessException.badRequest("Invite someone as a guardian or a helper");
        }
        InviteService.Issued issued = inviteService.create(InviteKind.HOUSEHOLD, context.household(), null,
                role.name(), inviteeName, user);
        auditService.record(user, "HOUSEHOLD_INVITE_CREATED", "Household", context.household().getId(), role.name());
        return issued;
    }

    @Transactional
    public void revokeInvite(User user, UUID inviteId) {
        Context context = requireOwner(user);
        Invite invite = inviteRepository.findById(inviteId)
                .filter(i -> i.getHousehold() != null && i.getHousehold().getId().equals(context.household().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Invite not found"));
        invite.setRevokedAt(Instant.now(clock));
    }

    @Transactional
    public void removeMember(User user, UUID memberId) {
        Context context = requireOwner(user);
        HouseholdMember member = memberRepository.findById(memberId)
                .filter(m -> m.getHousehold().getId().equals(context.household().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
        if (member.getRole() == HouseholdRole.OWNER) {
            throw BusinessException.badRequest("The account owner can't be removed");
        }
        detach(member, user, "removed you from");
    }

    @Transactional
    public void leave(User user) {
        HouseholdMember member = memberRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("You are not in a family"));
        if (member.getRole() == HouseholdRole.OWNER) {
            throw BusinessException.badRequest("The account owner can't leave their own family");
        }
        detach(member, user, null);
    }

    /** Joins the family behind an invite code. Called from onboarding or from an existing parent account. */
    @Transactional
    public void join(User user, String code) {
        if (user.getRole() != Role.PARENT) {
            throw BusinessException.badRequest("Family invites are for parent accounts");
        }
        Invite invite = inviteService.redeem(code, InviteKind.HOUSEHOLD, user);
        Household household = invite.getHousehold();

        memberRepository.findByUserId(user.getId()).ifPresent(existing -> {
            if (existing.getHousehold().getId().equals(household.getId())) {
                throw BusinessException.conflict("You're already in this family");
            }
            if (existing.getRole() != HouseholdRole.OWNER) {
                throw BusinessException.conflict("Leave your current family before joining another");
            }
            if (!childRepository.findByParentId(user.getId()).isEmpty()) {
                throw BusinessException.conflict(
                        "Your account already has children. Ask the other parent to join your family instead.");
            }
            // An empty family of their own: replace it with membership of the inviting family
            Household own = existing.getHousehold();
            memberRepository.delete(existing);
            memberRepository.flush();
            householdRepository.delete(own);
        });
        memberRepository.save(HouseholdMember.builder()
                .household(household).user(user).role(HouseholdRole.valueOf(invite.getRole())).build());
        auditService.record(user, "HOUSEHOLD_JOINED", "Household", household.getId(), invite.getRole());
        notificationService.notify(household.getOwner(), "FAMILY", "New family member",
                user.getFullName() + " joined " + household.getName() + " as " + invite.getRole().toLowerCase() + ".",
                household.getId());
    }

    // ─── Notifications to the whole family ───────────────────────────────────

    /** Sends to every member of the family that owns these children (falls back to the owner alone). */
    @Transactional
    public void notifyFamily(User owner, String type, String title, String body, UUID referenceId) {
        List<HouseholdMember> members = memberRepository.findByOwnerId(owner.getId());
        if (members.isEmpty()) {
            notificationService.notify(owner, type, title, body, referenceId);
            return;
        }
        members.forEach(m -> notificationService.notify(m.getUser(), type, title, body, referenceId));
    }

    /** True when the user belongs to the family that owns the child's parent account. */
    @Transactional(readOnly = true)
    public boolean isFamilyOf(User user, User owner) {
        return memberRepository.findByUserId(user.getId())
                .map(m -> m.getHousehold().getOwner().getId().equals(owner.getId()))
                .orElse(user.getId().equals(owner.getId()));
    }

    private Context requireOwner(User user) {
        Context context = resolve(user);
        if (context.role() != HouseholdRole.OWNER) {
            throw new UnauthorizedException("Only the account owner can manage family members");
        }
        return context;
    }

    private void detach(HouseholdMember member, User actor, String verb) {
        User removed = member.getUser();
        Household household = member.getHousehold();
        memberRepository.delete(member);
        // Sign them out so the next session starts with their own (empty) family
        refreshTokenService.revokeAllTokensForUser(removed);
        auditService.record(actor, "HOUSEHOLD_MEMBER_REMOVED", "Household", household.getId(), removed.getId().toString());
        if (verb != null) {
            notificationService.notify(removed, "FAMILY", "Removed from family",
                    household.getOwner().getFullName() + " " + verb + " " + household.getName() + ".", household.getId());
        }
    }
}

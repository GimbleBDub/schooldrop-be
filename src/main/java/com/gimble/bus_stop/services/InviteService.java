package com.gimble.bus_stop.services;

import com.gimble.bus_stop.entities.Household;
import com.gimble.bus_stop.entities.Invite;
import com.gimble.bus_stop.entities.Operator;
import com.gimble.bus_stop.entities.School;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.InviteKind;
import com.gimble.bus_stop.exceptions.InvalidTokenException;
import com.gimble.bus_stop.repositories.InviteRepository;
import com.gimble.bus_stop.security.util.TokenUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** One-time codes for joining a family or a school, shared by the inviter (e.g. over WhatsApp). */
@Service
@RequiredArgsConstructor
public class InviteService {

    private static final Duration TTL = Duration.ofDays(7);

    private final InviteRepository inviteRepository;
    private final Clock clock;

    public record Issued(Invite invite, String code) {
    }

    @Transactional
    public Issued create(InviteKind kind, Household household, School school, String role, String inviteeName, User creator) {
        return create(kind, household, school, null, role, inviteeName, creator);
    }

    @Transactional
    public Issued create(InviteKind kind, Household household, School school, Operator operator, String role,
                         String inviteeName, User creator) {
        String code = TokenUtils.randomCode(8);
        Invite invite = inviteRepository.save(Invite.builder()
                .kind(kind)
                .household(household)
                .school(school)
                .operator(operator)
                .role(role)
                .inviteeName(inviteeName != null && !inviteeName.isBlank() ? inviteeName.trim() : null)
                .codeHash(TokenUtils.sha256Hex(code))
                .expiresAt(Instant.now(clock).plus(TTL))
                .createdBy(creator)
                .build());
        return new Issued(invite, code);
    }

    /** Marks the invite used by this user; fails for wrong kind, expired, revoked or used codes. */
    @Transactional
    public Invite redeem(String code, InviteKind kind, User user) {
        Invite invite = inviteRepository.findByCodeHash(TokenUtils.sha256Hex(code.trim().toUpperCase()))
                .filter(i -> i.getKind() == kind && i.isUsable(Instant.now(clock)))
                .orElseThrow(() -> new InvalidTokenException("This invite code is invalid or has expired"));
        invite.setAcceptedBy(user);
        invite.setAcceptedAt(Instant.now(clock));
        return invite;
    }
}

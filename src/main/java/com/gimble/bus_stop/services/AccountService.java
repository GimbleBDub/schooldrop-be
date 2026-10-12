package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.account.AccountDtos.*;
import com.gimble.bus_stop.dto.responses.AuthResponse;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.DriverStatus;
import com.gimble.bus_stop.enums.HouseholdRole;
import com.gimble.bus_stop.enums.Role;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.EmailAlreadyExistsException;
import com.gimble.bus_stop.exceptions.PhoneAlreadyExistsException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.repositories.DriverProfileRepository;
import com.gimble.bus_stop.repositories.HouseholdMemberRepository;
import com.gimble.bus_stop.repositories.UserRepository;
import com.gimble.bus_stop.security.util.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Profile & settings for every account type: name, email, phone, password, notification settings and
 * signed-in devices. Email and phone identify an account, so they stay unique and changing them (or the
 * password) needs the current password.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private static final Set<DriverStatus> IDENTITY_CHECKED =
            EnumSet.of(DriverStatus.UNDER_REVIEW, DriverStatus.APPROVED, DriverStatus.SUSPENDED);

    private final UserRepository userRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final HouseholdMemberRepository householdMemberRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AuthService authService;
    private final ProfileAssembler profiles;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AccountResponse get(User current) {
        User user = reload(current);
        String nameLock = nameLockReason(user);
        return new AccountResponse(profiles.of(user), NotificationSettings.of(user), user.getPasswordChangedAt(),
                nameLock != null, nameLock, hasBilling(user));
    }

    // ─── Profile ─────────────────────────────────────────────────────────────

    @Transactional
    public AccountResponse updateProfile(User current, UpdateProfileRequest request) {
        User user = editable(current);
        String first = request.firstName().trim();
        String last = request.lastName().trim();
        if (first.equals(user.getFirstName()) && last.equals(user.getLastName())) {
            return get(user);
        }
        String nameLock = nameLockReason(user);
        if (nameLock != null) {
            throw BusinessException.conflict(nameLock);
        }
        String before = user.getFullName();
        user.setFirstName(first);
        user.setLastName(last);
        auditService.record(user, "PROFILE_UPDATED", "User", user.getId(), before + " → " + user.getFullName());
        return get(user);
    }

    // ─── Email, phone, password ──────────────────────────────────────────────

    @Transactional
    public AuthResponse changeEmail(User current, ChangeEmailRequest request) {
        User user = editable(current);
        checkPassword(user, request.password());
        String email = request.newEmail().toLowerCase().trim();
        if (email.equals(user.getEmail())) {
            throw BusinessException.badRequest("That's already your email address");
        }
        if (email.endsWith(".schooldrop.internal")) {
            throw BusinessException.badRequest("Use your own email address");
        }
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }
        String before = user.getEmail();
        user.setEmail(email);
        user.setEmailVerified(false);
        // Sign-in name changed: every other device signs in again; this one gets new tokens
        endOtherSessions(user);
        auditService.record(user, "EMAIL_CHANGED", "User", user.getId(), mask(before) + " → " + mask(email));
        notificationService.notify(user, "SECURITY", "Your email address was changed",
                "You now sign in with " + email + ". If this wasn't you, contact SchoolDrop support straight away.", null);
        log.info("User {} changed their email", user.getId());
        return authService.issueTokens(user, request.deviceId(), request.deviceName());
    }

    @Transactional
    public AccountResponse changePhone(User current, ChangePhoneRequest request) {
        User user = editable(current);
        checkPassword(user, request.password());
        String phone = PhoneNumbers.normalize(request.newPhone());
        if (phone.equals(user.getPhone())) {
            throw BusinessException.badRequest("That's already your phone number");
        }
        if (userRepository.existsByPhone(phone)) {
            throw new PhoneAlreadyExistsException();
        }
        String before = user.getPhone();
        user.setPhone(phone);
        auditService.record(user, "PHONE_CHANGED", "User", user.getId(), mask(before) + " → " + mask(phone));
        notificationService.notify(user, "SECURITY", "Your phone number was changed",
                "Your SchoolDrop phone number is now " + phone + ". If this wasn't you, contact support straight away.", null);
        return get(user);
    }

    @Transactional
    public AuthResponse changePassword(User current, ChangePasswordRequest request) {
        User user = editable(current);
        checkPassword(user, request.currentPassword());
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw BusinessException.badRequest("Choose a password you haven't just used");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setPasswordChangedAt(Instant.now(clock));
        user.setPasswordChangeRequired(false);
        endOtherSessions(user);
        auditService.record(user, "PASSWORD_CHANGED", "User", user.getId(), null);
        notificationService.notify(user, "SECURITY", "Your password was changed",
                "Other devices have been signed out. If this wasn't you, contact SchoolDrop support straight away.", null);
        return authService.issueTokens(user, request.deviceId(), request.deviceName());
    }

    // ─── Notifications ───────────────────────────────────────────────────────

    @Transactional
    public AccountResponse updateNotifications(User current, NotificationSettings settings) {
        User user = reload(current);
        user.setNotifyTrips(settings.trips());
        user.setNotifyBookings(settings.bookings());
        user.setNotifyPayments(settings.payments());
        user.setNotifyNews(settings.news());
        return get(user);
    }

    // ─── Signed-in devices ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SessionResponse> sessions(User user, String currentDeviceId) {
        return refreshTokenService.activeSessions(user).stream()
                .map(t -> SessionResponse.of(t, currentDeviceId)).toList();
    }

    @Transactional
    public void signOutSession(User user, UUID sessionId) {
        if (!refreshTokenService.revokeSession(user, sessionId)) {
            throw new ResourceNotFoundException("That device is already signed out");
        }
        auditService.record(user, "SESSION_REVOKED", "User", user.getId(), sessionId.toString());
    }

    /** Signs out every other device. Their access tokens stop working within the 15-minute token lifetime. */
    @Transactional
    public int signOutOtherSessions(User user, String keepDeviceId) {
        int count = refreshTokenService.revokeOtherSessions(user, keepDeviceId);
        auditService.record(user, "SESSIONS_REVOKED", "User", user.getId(), count + " devices");
        return count;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void endOtherSessions(User user) {
        // Access tokens issued before now are rejected at once, refresh tokens are revoked
        user.setSessionsValidFrom(Instant.now(clock));
        refreshTokenService.revokeAllTokensForUser(user);
    }

    private void checkPassword(User user, String password) {
        if (!passwordEncoder.matches(password, user.getPassword())) {
            // 400, not 401: a 401 would make the app think its session expired
            throw BusinessException.badRequest("Your password is incorrect");
        }
    }

    private User reload(User current) {
        return userRepository.findById(current.getId()).orElseThrow();
    }

    /** Kids' accounts are managed by their family. */
    private User editable(User current) {
        if (current.getRole() == Role.KID) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "Ask your parent to change this");
        }
        return reload(current);
    }

    private String nameLockReason(User user) {
        if (user.getRole() != Role.DRIVER) {
            return null;
        }
        return driverProfileRepository.findByUserId(user.getId())
                .filter(d -> IDENTITY_CHECKED.contains(d.getStatus()))
                .map(d -> "Your name was checked against your Omang. Contact SchoolDrop support to change it.")
                .orElse(null);
    }

    private boolean hasBilling(User user) {
        return switch (user.getRole()) {
            // Team drivers and managers: the money goes to the team's owner
            case DRIVER -> driverProfileRepository.findByUserId(user.getId()).map(d -> d.ownsBusiness()).orElse(true);
            case PARENT -> householdMemberRepository.findByUserId(user.getId())
                    .map(m -> m.getRole() != HouseholdRole.HELPER).orElse(true);
            default -> false;
        };
    }

    private static String mask(String value) {
        if (value == null || value.length() < 5) {
            return "***";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 3);
    }
}

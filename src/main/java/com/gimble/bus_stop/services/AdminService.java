package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.SchoolTermRequest;
import com.gimble.bus_stop.dto.requests.StaffRequest;
import com.gimble.bus_stop.dto.responses.*;
import com.gimble.bus_stop.entities.SchoolTerm;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.*;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.EmailAlreadyExistsException;
import com.gimble.bus_stop.exceptions.PhoneAlreadyExistsException;
import com.gimble.bus_stop.security.util.PhoneNumbers;
import com.gimble.bus_stop.security.util.TokenUtils;
import org.springframework.http.HttpStatus;
import java.util.Comparator;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.repositories.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final SchoolRepository schoolRepository;
    private final BookingRepository bookingRepository;
    private final TripRepository tripRepository;
    private final IncidentRepository incidentRepository;
    private final PaymentRepository paymentRepository;
    private final PayoutRepository payoutRepository;
    private final SchoolTermRepository termRepository;
    private final AuditLogRepository auditLogRepository;
    private final RefreshTokenService refreshTokenService;
    private final DriverService driverService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DashboardResponse dashboard() {
        Instant since = Instant.now(clock).minus(Duration.ofDays(30));
        return new DashboardResponse(
                userRepository.countByRole(Role.PARENT),
                userRepository.countByRole(Role.DRIVER),
                userRepository.countByRole(Role.SCHOOL),
                userRepository.countByRole(Role.KID),
                driverProfileRepository.countByStatus(DriverStatus.UNDER_REVIEW),
                schoolRepository.countByStatus(SchoolStatus.PENDING),
                bookingRepository.countByStatus(BookingStatus.ACTIVE),
                tripRepository.findInProgress().size(),
                incidentRepository.countByStatus(IncidentStatus.OPEN),
                paymentRepository.sumGrossSince(since),
                paymentRepository.sumFeesSince(since),
                payoutRepository.sumByStatus(PayoutStatus.PENDING));
    }

    // ─── Users ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> searchUsers(Role role, String query, Pageable pageable) {
        String q = query == null || query.isBlank() ? null : query.trim();
        return PageResponse.from(userRepository.search(role, q, pageable).map(AdminUserResponse::from));
    }

    @Transactional(readOnly = true)
    public AdminUserResponse getUser(UUID userId) {
        return AdminUserResponse.from(requireUser(userId));
    }

    /** Disabling signs the user out everywhere at once (the JWT filter re-checks on every request). */
    @Transactional
    public AdminUserResponse setEnabled(User admin, UUID userId, boolean enabled, String reason) {
        User user = requireUser(userId);
        if (user.getId().equals(admin.getId())) {
            throw BusinessException.badRequest("You can't disable your own account");
        }
        if (user.getRole() == Role.ADMIN) {
            if (!admin.getStaffPermissions().contains(StaffPermission.SUPER_ADMIN)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "Only a super admin can disable staff accounts");
            }
            if (!enabled && isLastSuperAdmin(user)) {
                throw BusinessException.conflict("This is the last super admin — give someone else super admin first");
            }
        }
        if (!enabled && (reason == null || reason.isBlank())) {
            throw BusinessException.badRequest("A reason is required");
        }
        user.setEnabled(enabled);
        if (!enabled) {
            refreshTokenService.revokeAllTokensForUser(user);
            if (user.getRole() == Role.DRIVER) {
                driverProfileRepository.findByUserId(user.getId())
                        .filter(d -> d.getStatus() != DriverStatus.SUSPENDED)
                        .ifPresent(d -> driverService.suspend(admin, d.getId(), "Account disabled: " + reason));
            }
        }
        auditService.record(admin, enabled ? "USER_ENABLED" : "USER_DISABLED", "User", user.getId(), reason);
        return AdminUserResponse.from(user);
    }

    // ─── Staff ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listStaff() {
        return userRepository.findByRole(Role.ADMIN).stream()
                .sorted(Comparator.comparing(User::isEnabled).reversed().thenComparing(User::getFullName))
                .map(AdminUserResponse::from).toList();
    }

    /** The temporary password is returned once; they must replace it before using the back office. */
    @Transactional
    public AdminUserResponse createStaff(User admin, StaffRequest request) {
        String email = request.email().toLowerCase().trim();
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }
        String phone = PhoneNumbers.normalize(request.phone());
        if (phone != null && userRepository.existsByPhone(phone)) {
            throw new PhoneAlreadyExistsException();
        }
        boolean generated = request.temporaryPassword() == null || request.temporaryPassword().isBlank();
        String temporary = generated ? temporaryPassword() : request.temporaryPassword();
        User staff = userRepository.save(User.builder()
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .email(email)
                .phone(phone)
                .password(passwordEncoder.encode(temporary))
                .passwordChangeRequired(true)
                .role(Role.ADMIN)
                .onboardedAt(Instant.now(clock))
                .emailVerified(true)
                .staffPermissions(new HashSet<>(request.permissions()))
                .build());
        auditService.record(admin, "STAFF_CREATED", "User", staff.getId(), request.permissions().toString());
        return AdminUserResponse.withTemporaryPassword(staff, generated ? temporary : null);
    }

    /** Forgot their password, or it may have leaked: sign them out everywhere and issue a new temporary one. */
    @Transactional
    public AdminUserResponse resetStaffPassword(User admin, UUID userId) {
        User staff = requireStaff(userId);
        if (staff.getId().equals(admin.getId())) {
            throw BusinessException.badRequest("Change your own password in Profile & settings");
        }
        String temporary = temporaryPassword();
        staff.setPassword(passwordEncoder.encode(temporary));
        staff.setPasswordChangeRequired(true);
        staff.setSessionsValidFrom(Instant.now(clock));
        refreshTokenService.revokeAllTokensForUser(staff);
        auditService.record(admin, "STAFF_PASSWORD_RESET", "User", staff.getId(), null);
        return AdminUserResponse.withTemporaryPassword(staff, temporary);
    }

    private User requireStaff(UUID userId) {
        User staff = requireUser(userId);
        if (staff.getRole() != Role.ADMIN) {
            throw BusinessException.badRequest("That isn't a staff account");
        }
        return staff;
    }

    private boolean isLastSuperAdmin(User user) {
        return user.getStaffPermissions().contains(StaffPermission.SUPER_ADMIN)
                && userRepository.findByRole(Role.ADMIN).stream()
                .filter(u -> u.isEnabled() && !u.getId().equals(user.getId()))
                .noneMatch(u -> u.getStaffPermissions().contains(StaffPermission.SUPER_ADMIN));
    }

    private static String temporaryPassword() {
        return TokenUtils.randomCode(4) + "-" + TokenUtils.randomCode(4) + "-" + TokenUtils.randomCode(4);
    }

    @Transactional
    public AdminUserResponse setPermissions(User admin, UUID userId, Set<StaffPermission> permissions) {
        User staff = requireStaff(userId);
        if (permissions.isEmpty()) {
            throw BusinessException.badRequest("Give at least one permission, or disable the account instead");
        }
        if (staff.getId().equals(admin.getId()) && !permissions.contains(StaffPermission.SUPER_ADMIN)) {
            throw BusinessException.badRequest("You can't remove your own super admin permission");
        }
        staff.getStaffPermissions().clear();
        staff.getStaffPermissions().addAll(permissions);
        // Permissions are read from the DB per request, but revoke so the change is felt everywhere at once
        refreshTokenService.revokeAllTokensForUser(staff);
        auditService.record(admin, "STAFF_PERMISSIONS_CHANGED", "User", staff.getId(), permissions.toString());
        return AdminUserResponse.from(staff);
    }

    // ─── School terms ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SchoolTermResponse> listTerms(boolean upcomingOnly) {
        List<SchoolTerm> terms = upcomingOnly
                ? termRepository.findByEndDateGreaterThanEqualOrderByStartDate(LocalDate.now(clock))
                : termRepository.findAllByOrderByStartDateDesc();
        return terms.stream().map(SchoolTermResponse::from).toList();
    }

    @Transactional
    public SchoolTermResponse saveTerm(User admin, UUID termId, SchoolTermRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw BusinessException.badRequest("The term must end after it starts");
        }
        SchoolTerm term = termId == null ? new SchoolTerm() : termRepository.findById(termId)
                .orElseThrow(() -> new ResourceNotFoundException("Term not found"));
        if ((termId == null || !term.getName().equals(request.name().trim())) && termRepository.existsByName(request.name().trim())) {
            throw BusinessException.conflict("A term with this name already exists");
        }
        term.setName(request.name().trim());
        term.setStartDate(request.startDate());
        term.setEndDate(request.endDate());
        term = termRepository.save(term);
        auditService.record(admin, termId == null ? "TERM_CREATED" : "TERM_UPDATED", "SchoolTerm", term.getId(),
                term.getName() + " " + term.getStartDate() + ".." + term.getEndDate());
        return SchoolTermResponse.from(term);
    }

    // ─── Audit log ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> auditLog(String targetType, UUID targetId, UUID actorId, Pageable pageable) {
        String type = targetType == null || targetType.isBlank() ? null : targetType;
        return PageResponse.from(auditLogRepository.search(type, targetId, actorId, pageable).map(AuditLogResponse::from));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}

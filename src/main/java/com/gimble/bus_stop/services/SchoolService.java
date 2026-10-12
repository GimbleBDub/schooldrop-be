package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.OnboardingRequests;
import com.gimble.bus_stop.dto.requests.AdminSchoolRequest;
import com.gimble.bus_stop.dto.requests.UpdateSchoolRequest;
import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.dto.responses.SchoolChildResponse;
import com.gimble.bus_stop.dto.responses.InviteResponse;
import com.gimble.bus_stop.dto.responses.SchoolResponse;
import com.gimble.bus_stop.dto.responses.SchoolStaffResponse;
import com.gimble.bus_stop.entities.Invite;
import com.gimble.bus_stop.entities.Child;
import com.gimble.bus_stop.entities.School;
import com.gimble.bus_stop.entities.SchoolStaff;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.EnrollmentStatus;
import com.gimble.bus_stop.enums.InviteKind;
import com.gimble.bus_stop.enums.SchoolRole;
import com.gimble.bus_stop.enums.SchoolStatus;
import com.gimble.bus_stop.enums.StaffPermission;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.exceptions.UnauthorizedException;
import com.gimble.bus_stop.repositories.ChildRepository;
import com.gimble.bus_stop.repositories.InviteRepository;
import com.gimble.bus_stop.repositories.SchoolRepository;
import com.gimble.bus_stop.repositories.SchoolStaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchoolService {

    private final SchoolRepository schoolRepository;
    private final SchoolStaffRepository schoolStaffRepository;
    private final ChildRepository childRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final InviteService inviteService;
    private final InviteRepository inviteRepository;
    private final RefreshTokenService refreshTokenService;

    // ─── Directory (any signed-in user) ──────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<SchoolResponse> listVerified(String query, Pageable pageable) {
        return PageResponse.from(schoolRepository.search(SchoolStatus.VERIFIED, blankToNull(query), pageable)
                .map(SchoolResponse::from));
    }

    @Transactional(readOnly = true)
    public School requireVerified(UUID schoolId) {
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResourceNotFoundException("School not found"));
        if (!school.isVerified()) {
            throw BusinessException.badRequest("This school is not available on SchoolDrop yet");
        }
        return school;
    }

    // ─── School staff ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public School requireStaffSchool(User staffUser) {
        return schoolStaffRepository.findByUserId(staffUser.getId())
                .map(SchoolStaff::getSchool)
                .orElseThrow(() -> new UnauthorizedException("You are not linked to a school"));
    }

    /** Profile, enrollments and staff: school admins only. */
    public School requireSchoolAdmin(User staffUser) {
        SchoolStaff staff = schoolStaffRepository.findByUserId(staffUser.getId())
                .orElseThrow(() -> new UnauthorizedException("You are not linked to a school"));
        if (staff.getRole() != SchoolRole.ADMIN) {
            throw new UnauthorizedException("Only school admins can do this");
        }
        return staff.getSchool();
    }

    private School requireVerifiedStaffSchool(User staffUser) {
        School school = requireStaffSchool(staffUser);
        if (!school.isVerified()) {
            throw new UnauthorizedException("Your school has not been verified yet");
        }
        return school;
    }

    @Transactional(readOnly = true)
    public SchoolResponse getMySchool(User staffUser) {
        return SchoolResponse.from(requireStaffSchool(staffUser));
    }

    /**
     * School admins keep their contact details current. Name, address and map location are set by
     * SchoolDrop (families and drivers rely on them), so those go through SchoolDrop support.
     */
    @Transactional
    public SchoolResponse updateMySchool(User staffUser, UpdateSchoolRequest request) {
        School school = requireSchoolAdmin(staffUser);
        school.setPhone(request.phone());
        school.setEmail(request.email());
        auditService.record(staffUser, "SCHOOL_CONTACT_UPDATED", "School", school.getId(), null);
        return SchoolResponse.from(school);
    }

    @Transactional(readOnly = true)
    public PageResponse<SchoolChildResponse> listChildren(User staffUser, EnrollmentStatus status, Pageable pageable) {
        School school = requireVerifiedStaffSchool(staffUser);
        return PageResponse.from(childRepository.findBySchool(school.getId(), status, pageable)
                .map(SchoolChildResponse::from));
    }

    @Transactional
    public SchoolChildResponse decideEnrollment(User staffUser, UUID childId, boolean confirm, String note) {
        requireSchoolAdmin(staffUser);
        School school = requireVerifiedStaffSchool(staffUser);
        Child child = childRepository.findById(childId)
                .filter(c -> c.getSchool() != null && c.getSchool().getId().equals(school.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Child not found at your school"));

        child.setEnrollmentStatus(confirm ? EnrollmentStatus.CONFIRMED : EnrollmentStatus.REJECTED);
        child.setEnrollmentNote(note);
        auditService.record(staffUser, confirm ? "ENROLLMENT_CONFIRMED" : "ENROLLMENT_REJECTED",
                "Child", child.getId(), note);
        notificationService.notify(child.getParent(), "ENROLLMENT",
                confirm ? "Enrollment confirmed" : "Enrollment not confirmed",
                confirm
                        ? school.getName() + " confirmed " + child.getFirstName() + "'s enrollment."
                        : school.getName() + " could not confirm " + child.getFirstName() + "'s enrollment"
                          + (note != null ? ": " + note : "."),
                child.getId());
        return SchoolChildResponse.from(child);
    }

    // ─── Staff (school admins) ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SchoolStaffResponse listStaff(User staffUser) {
        SchoolStaff me = schoolStaffRepository.findByUserId(staffUser.getId())
                .orElseThrow(() -> new UnauthorizedException("You are not linked to a school"));
        List<InviteResponse> invites = me.getRole() == SchoolRole.ADMIN
                ? inviteRepository.findOpenForSchool(me.getSchool().getId(), Instant.now()).stream()
                        .map(InviteResponse::from).toList()
                : List.of();
        return SchoolStaffResponse.from(schoolStaffRepository.findBySchoolId(me.getSchool().getId()), staffUser,
                me.getRole(), invites);
    }

    @Transactional
    public InviteResponse inviteStaff(User admin, SchoolRole role, String inviteeName) {
        School school = requireSchoolAdmin(admin);
        InviteService.Issued issued = inviteService.create(InviteKind.SCHOOL, null, school, role.name(), inviteeName, admin);
        auditService.record(admin, "SCHOOL_INVITE_CREATED", "School", school.getId(), role.name());
        return InviteResponse.withCode(issued.invite(), issued.code());
    }

    @Transactional
    public void revokeInvite(User admin, UUID inviteId) {
        School school = requireSchoolAdmin(admin);
        inviteRepository.findById(inviteId)
                .filter(i -> i.getSchool() != null && i.getSchool().getId().equals(school.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Invite not found"))
                .setRevokedAt(Instant.now());
    }

    @Transactional
    public void removeStaff(User admin, UUID staffId) {
        School school = requireSchoolAdmin(admin);
        SchoolStaff staff = schoolStaffRepository.findById(staffId)
                .filter(s -> s.getSchool().getId().equals(school.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Staff member not found"));
        if (staff.getRole() == SchoolRole.ADMIN && schoolStaffRepository.countBySchoolIdAndRole(school.getId(), SchoolRole.ADMIN) <= 1) {
            throw BusinessException.conflict("A school needs at least one admin");
        }
        schoolStaffRepository.delete(staff);
        // Someone who left the school must lose access straight away
        refreshTokenService.revokeAllTokensForUser(staff.getUser());
        auditService.record(admin, "SCHOOL_STAFF_REMOVED", "School", school.getId(), staff.getUser().getId().toString());
    }

    /** A SCHOOL account joining an existing school with an invite code (instead of registering a new school). */
    @Transactional
    public void joinWithInvite(User user, String code, String title) {
        if (schoolStaffRepository.findByUserId(user.getId()).isPresent()) {
            throw BusinessException.conflict("You're already linked to a school");
        }
        Invite invite = inviteService.redeem(code, InviteKind.SCHOOL, user);
        schoolStaffRepository.save(SchoolStaff.builder()
                .school(invite.getSchool())
                .user(user)
                .role(SchoolRole.valueOf(invite.getRole()))
                .title(title)
                .build());
        auditService.record(user, "SCHOOL_STAFF_JOINED", "School", invite.getSchool().getId(), invite.getRole());
        schoolStaffRepository.findBySchoolId(invite.getSchool().getId()).stream()
                .filter(s -> s.getRole() == SchoolRole.ADMIN)
                .forEach(s -> notificationService.notify(s.getUser(), "SCHOOL_STAFF", "New staff member",
                        user.getFullName() + " joined " + invite.getSchool().getName() + ".", invite.getSchool().getId()));
    }

    // ─── Admin ───────────────────────────────────────────────────────────────

    /** Schools are added by SchoolDrop (not self-registered), already verified and pinned on the map. */
    @Transactional
    public SchoolResponse adminCreate(User admin, AdminSchoolRequest request) {
        String name = request.name().trim();
        if (schoolRepository.existsByNameIgnoreCaseAndCityIgnoreCase(name, request.city().trim())) {
            throw BusinessException.conflict(name + " in " + request.city().trim() + " is already on SchoolDrop");
        }
        School school = School.builder().name(name).build();
        applyAdmin(school, request);
        school.setStatus(SchoolStatus.VERIFIED);
        school.setReviewedBy(admin);
        school.setReviewedAt(Instant.now());
        school = schoolRepository.save(school);
        auditService.record(admin, "SCHOOL_CREATED", "School", school.getId(), name);
        return SchoolResponse.from(school);
    }

    @Transactional
    public SchoolResponse adminUpdate(User admin, UUID schoolId, AdminSchoolRequest request) {
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResourceNotFoundException("School not found"));
        applyAdmin(school, request);
        auditService.record(admin, "SCHOOL_UPDATED", "School", school.getId(), null);
        return SchoolResponse.from(school);
    }

    /** A code for the school's first admin (or any staff member) to join it on SchoolDrop. */
    @Transactional
    public InviteResponse adminInvite(User admin, UUID schoolId, SchoolRole role, String inviteeName) {
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResourceNotFoundException("School not found"));
        InviteService.Issued issued = inviteService.create(InviteKind.SCHOOL, null, school, role.name(), inviteeName, admin);
        auditService.record(admin, "SCHOOL_INVITE_CREATED", "School", school.getId(), role.name());
        return InviteResponse.withCode(issued.invite(), issued.code());
    }

    private static void applyAdmin(School school, AdminSchoolRequest request) {
        school.setName(request.name().trim());
        school.setAddress(request.address().trim());
        school.setCity(request.city().trim());
        school.setRegistrationNumber(request.registrationNumber());
        school.setPhone(request.phone() != null ? request.phone().replace(" ", "") : null);
        school.setEmail(request.email());
        school.setLatitude(request.latitude());
        school.setLongitude(request.longitude());
    }

    @Transactional(readOnly = true)
    public PageResponse<SchoolResponse> adminSearch(SchoolStatus status, String query, Pageable pageable) {
        return PageResponse.from(schoolRepository.search(status, blankToNull(query), pageable)
                .map(SchoolResponse::from));
    }

    @Transactional
    public SchoolResponse adminSetStatus(User admin, UUID schoolId, SchoolStatus newStatus, String reason) {
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResourceNotFoundException("School not found"));
        if (newStatus == SchoolStatus.PENDING) {
            throw BusinessException.badRequest("A school cannot be moved back to PENDING");
        }
        if (newStatus != SchoolStatus.VERIFIED && (reason == null || reason.isBlank())) {
            throw BusinessException.badRequest("A reason is required");
        }

        school.setStatus(newStatus);
        school.setStatusReason(newStatus == SchoolStatus.VERIFIED ? null : reason);
        school.setReviewedBy(admin);
        school.setReviewedAt(Instant.now());
        auditService.record(admin, "SCHOOL_" + newStatus, "School", school.getId(), reason);

        schoolStaffRepository.findBySchoolId(school.getId()).forEach(staff ->
                notificationService.notify(staff.getUser(), "SCHOOL_STATUS",
                        "School " + newStatus.name().toLowerCase(),
                        newStatus == SchoolStatus.VERIFIED
                                ? school.getName() + " is now verified on SchoolDrop."
                                : school.getName() + " was " + newStatus.name().toLowerCase() + ": " + reason,
                        school.getId()));
        return SchoolResponse.from(school);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

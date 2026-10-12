package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.ChildRequest;
import com.gimble.bus_stop.dto.responses.ChildResponse;
import com.gimble.bus_stop.dto.responses.PairingCodeResponse;
import com.gimble.bus_stop.entities.Child;
import com.gimble.bus_stop.entities.School;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.EnrollmentStatus;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.repositories.BookingRepository;
import com.gimble.bus_stop.repositories.ChildRepository;
import com.gimble.bus_stop.security.util.TokenUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChildService {

    private static final Duration PAIRING_CODE_TTL = Duration.ofMinutes(15);

    private final ChildRepository childRepository;
    private final BookingRepository bookingRepository;
    private final SchoolService schoolService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public ChildResponse create(User parent, ChildRequest request) {
        Child child = Child.builder()
                .parent(parent)
                .build();
        apply(child, request);
        child = childRepository.save(child);
        log.info("Parent {} added child {}", parent.getId(), child.getId());
        return ChildResponse.from(child);
    }

    @Transactional(readOnly = true)
    public List<ChildResponse> list(User parent) {
        return childRepository.findByParentId(parent.getId()).stream().map(ChildResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ChildResponse get(User parent, UUID childId) {
        return ChildResponse.from(requireOwned(parent, childId));
    }

    @Transactional
    public ChildResponse update(User parent, UUID childId, ChildRequest request) {
        Child child = requireOwned(parent, childId);
        UUID oldSchoolId = child.getSchool() != null ? child.getSchool().getId() : null;
        if (!Objects.equals(oldSchoolId, request.schoolId()) && bookingRepository.existsActiveForChild(childId)) {
            throw BusinessException.conflict("Cancel this child's active bookings before changing school");
        }
        apply(child, request);
        return ChildResponse.from(child);
    }

    @Transactional
    public void delete(User parent, UUID childId) {
        Child child = requireOwned(parent, childId);
        if (bookingRepository.existsActiveForChild(childId)) {
            throw BusinessException.conflict("Cancel this child's active bookings first");
        }
        disableKidUser(child);
        if (bookingRepository.existsByChildId(childId)) {
            child.setArchivedAt(Instant.now());
        } else {
            childRepository.delete(child);
        }
        log.info("Parent {} removed child {}", parent.getId(), childId);
    }

    /** One-time code the kid types (or scans) in their app to pair their phone. */
    @Transactional
    public PairingCodeResponse createPairingCode(User parent, UUID childId) {
        Child child = requireOwned(parent, childId);
        String code = TokenUtils.randomCode(8);
        Instant expiresAt = Instant.now().plus(PAIRING_CODE_TTL);
        child.setPairingCodeHash(TokenUtils.sha256Hex(code));
        child.setPairingCodeExpiresAt(expiresAt);
        return new PairingCodeResponse(code, expiresAt);
    }

    @Transactional
    public ChildResponse unpairDevice(User parent, UUID childId) {
        Child child = requireOwned(parent, childId);
        disableKidUser(child);
        return ChildResponse.from(child);
    }

    public Child requireOwned(User parent, UUID childId) {
        return childRepository.findByIdAndParentIdAndArchivedAtIsNull(childId, parent.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Child not found"));
    }

    private void apply(Child child, ChildRequest request) {
        child.setFirstName(request.firstName().trim());
        child.setLastName(request.lastName().trim());
        child.setDateOfBirth(request.dateOfBirth());
        child.setGrade(request.grade());
        child.setStudentNumber(request.studentNumber());
        child.setSpecialNeeds(request.specialNeeds());

        UUID currentSchoolId = child.getSchool() != null ? child.getSchool().getId() : null;
        if (!Objects.equals(currentSchoolId, request.schoolId())) {
            School school = request.schoolId() != null ? schoolService.requireVerified(request.schoolId()) : null;
            child.setSchool(school);
            child.setEnrollmentStatus(school != null ? EnrollmentStatus.PENDING : null);
            child.setEnrollmentNote(null);
        }
    }

    private void disableKidUser(Child child) {
        User kidUser = child.getKidUser();
        if (kidUser != null) {
            kidUser.setEnabled(false);
            refreshTokenService.revokeAllTokensForUser(kidUser);
        }
        child.setPairingCodeHash(null);
        child.setPairingCodeExpiresAt(null);
    }
}

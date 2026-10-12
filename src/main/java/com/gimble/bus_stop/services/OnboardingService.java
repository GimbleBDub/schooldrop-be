package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.OnboardingRequests;
import com.gimble.bus_stop.dto.responses.UserProfileResponse;
import com.gimble.bus_stop.entities.DriverProfile;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.Role;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.UnauthorizedException;
import com.gimble.bus_stop.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** The step right after sign-up: who you are, plus the details your account type needs. Done once. */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingService {

    private final UserRepository userRepository;
    private final DriverService driverService;
    private final SchoolService schoolService;
    private final HouseholdService householdService;
    private final TeamService teamService;
    private final ProfileAssembler profiles;
    private final Clock clock;

    @Transactional
    public UserProfileResponse parent(User current, OnboardingRequests.Parent request) {
        User user = start(current, Role.PARENT);
        setName(user, request.firstName(), request.lastName());
        householdService.createFor(user);
        return finish(user);
    }

    /** Joins an existing family (parent accounts) or school (school accounts) with an invite code. */
    @Transactional
    public UserProfileResponse join(User current, OnboardingRequests.Join request) {
        User user = userRepository.findById(current.getId()).orElseThrow();
        if (user.isOnboarded()) {
            throw BusinessException.conflict("Your account is already set up");
        }
        setName(user, request.firstName(), request.lastName());
        switch (user.getRole()) {
            case PARENT -> householdService.join(user, request.code());
            case SCHOOL -> schoolService.joinWithInvite(user, request.code(), request.title());
            case DRIVER -> teamService.join(user, request.code());
            default -> throw BusinessException.badRequest("Invite codes are for family members, school staff and driver teams");
        }
        return finish(user);
    }

    @Transactional
    public UserProfileResponse driver(User current, OnboardingRequests.Driver request) {
        User user = start(current, Role.DRIVER);
        setName(user, request.firstName(), request.lastName());
        DriverProfile profile = driverService.requireProfile(user);
        if (request.omangNumber() != null) profile.setOmangNumber(request.omangNumber());
        if (request.dateOfBirth() != null) profile.setDateOfBirth(request.dateOfBirth());
        if (request.homeArea() != null) profile.setHomeArea(request.homeArea().trim());
        if (request.team() != null) {
            teamService.createFor(profile, request.team());
        }
        return finish(user);
    }

    /**
     * Schools are added by SchoolDrop, not registered by users: school staff join their school with the
     * invite code SchoolDrop (or their school admin) gives them — see {@link #join}.
     */
    @Transactional
    public UserProfileResponse school(User current, OnboardingRequests.School request) {
        throw BusinessException.badRequest("Schools are added by SchoolDrop. Ask your school's admin, or SchoolDrop, for an invite code.");
    }

    private User start(User current, Role expected) {
        if (current.getRole() != expected) {
            throw new UnauthorizedException("This onboarding is for " + expected.name().toLowerCase() + " accounts");
        }
        // Re-read: the principal comes from the JWT filter, outside this transaction
        User user = userRepository.findById(current.getId()).orElseThrow();
        if (user.isOnboarded()) {
            throw BusinessException.conflict("Your account is already set up");
        }
        return user;
    }

    private static void setName(User user, String firstName, String lastName) {
        user.setFirstName(firstName.trim());
        user.setLastName(lastName.trim());
    }

    private UserProfileResponse finish(User user) {
        user.setOnboardedAt(Instant.now(clock));
        log.info("{} {} finished onboarding", user.getRole(), user.getId());
        return profiles.of(user);
    }
}

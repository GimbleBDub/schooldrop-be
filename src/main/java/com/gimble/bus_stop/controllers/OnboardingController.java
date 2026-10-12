package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.requests.OnboardingRequests;
import com.gimble.bus_stop.dto.responses.UserProfileResponse;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.services.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Completes a new account. Until this is done, /v1/parent, /v1/driver and /v1/school answer 403. */
@RestController
@RequestMapping("/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @PutMapping("/parent")
    public UserProfileResponse parent(@AuthenticationPrincipal User user,
                                      @Valid @RequestBody OnboardingRequests.Parent request) {
        return onboardingService.parent(user, request);
    }

    @PutMapping("/driver")
    public UserProfileResponse driver(@AuthenticationPrincipal User user,
                                      @Valid @RequestBody OnboardingRequests.Driver request) {
        return onboardingService.driver(user, request);
    }

    @PutMapping("/join")
    public UserProfileResponse join(@AuthenticationPrincipal User user,
                                    @Valid @RequestBody OnboardingRequests.Join request) {
        return onboardingService.join(user, request);
    }

    @PutMapping("/school")
    public UserProfileResponse school(@AuthenticationPrincipal User user,
                                      @Valid @RequestBody OnboardingRequests.School request) {
        return onboardingService.school(user, request);
    }
}

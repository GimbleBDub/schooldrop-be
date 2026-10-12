package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.account.AccountDtos.*;
import com.gimble.bus_stop.dto.responses.AuthResponse;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.services.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Profile & settings — the same for every account type. */
@RestController
@RequestMapping("/v1/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping
    public AccountResponse get(@AuthenticationPrincipal User user) {
        return accountService.get(user);
    }

    @PutMapping("/profile")
    public AccountResponse updateProfile(@AuthenticationPrincipal User user, @Valid @RequestBody UpdateProfileRequest request) {
        return accountService.updateProfile(user, request);
    }

    /** Returns new tokens: every other device is signed out. */
    @PutMapping("/email")
    public AuthResponse changeEmail(@AuthenticationPrincipal User user, @Valid @RequestBody ChangeEmailRequest request) {
        return accountService.changeEmail(user, request);
    }

    @PutMapping("/phone")
    public AccountResponse changePhone(@AuthenticationPrincipal User user, @Valid @RequestBody ChangePhoneRequest request) {
        return accountService.changePhone(user, request);
    }

    /** Returns new tokens: every other device is signed out. */
    @PutMapping("/password")
    public AuthResponse changePassword(@AuthenticationPrincipal User user, @Valid @RequestBody ChangePasswordRequest request) {
        return accountService.changePassword(user, request);
    }

    @PutMapping("/notifications")
    public AccountResponse updateNotifications(@AuthenticationPrincipal User user, @RequestBody NotificationSettings settings) {
        return accountService.updateNotifications(user, settings);
    }

    /** deviceId (optional) marks which entry is "this device". */
    @GetMapping("/sessions")
    public List<SessionResponse> sessions(@AuthenticationPrincipal User user, @RequestParam(required = false) String deviceId) {
        return accountService.sessions(user, deviceId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOutSession(@AuthenticationPrincipal User user, @PathVariable UUID sessionId) {
        accountService.signOutSession(user, sessionId);
    }

    @PostMapping("/sessions/sign-out-others")
    public Map<String, Integer> signOutOthers(@AuthenticationPrincipal User user, @Valid @RequestBody DeviceRequest request) {
        return Map.of("signedOut", accountService.signOutOtherSessions(user, request.deviceId()));
    }
}

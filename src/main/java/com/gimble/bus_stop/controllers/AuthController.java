package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.requests.*;
import com.gimble.bus_stop.dto.responses.AuthResponse;
import com.gimble.bus_stop.dto.responses.UserProfileResponse;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.services.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // ─── Registration ────────────────────────────────────────────────────────

    /** Account type, email, phone and password only; details follow in /v1/onboarding. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    // ─── Login ───────────────────────────────────────────────────────────────

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    // ─── Token Refresh ───────────────────────────────────────────────────────

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refreshToken(request);
    }

    // ─── Logout ──────────────────────────────────────────────────────────────

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal User currentUser,
                       @RequestBody(required = false) RefreshTokenRequest request) {
        authService.logout(currentUser, request);
    }

    // ─── Profile ─────────────────────────────────────────────────────────────

    @GetMapping("/me")
    public UserProfileResponse getProfile(@AuthenticationPrincipal User currentUser) {
        return authService.getProfile(currentUser);
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal User currentUser,
                               @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(currentUser, request);
    }

    // ─── Kid device pairing (public — the code is the credential) ─────────────

    @PostMapping("/kid/pair")
    public AuthResponse pairKidDevice(@Valid @RequestBody KidPairRequest request) {
        return authService.pairKidDevice(request);
    }
}

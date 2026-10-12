package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.*;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.EmailAlreadyExistsException;
import com.gimble.bus_stop.exceptions.InvalidTokenException;
import com.gimble.bus_stop.exceptions.PhoneAlreadyExistsException;
import lombok.RequiredArgsConstructor;
import com.gimble.bus_stop.dto.responses.AuthResponse;
import com.gimble.bus_stop.dto.responses.UserProfileResponse;
import com.gimble.bus_stop.entities.Child;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.Role;
import com.gimble.bus_stop.repositories.ChildRepository;
import com.gimble.bus_stop.repositories.UserRepository;
import com.gimble.bus_stop.security.jwt.JwtUtil;
import com.gimble.bus_stop.security.util.PhoneNumbers;
import com.gimble.bus_stop.security.util.TokenUtils;
import com.gimble.bus_stop.services.RefreshTokenService.IssuedToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokenService;
    private final ChildRepository childRepository;
    private final DriverService driverService;
    private final ProfileAssembler profiles;

    @Value("${app.jwt.access-token-expiry-ms}")
    private long accessTokenExpiryMs;

    // ─── Registration ────────────────────────────────────────────────────────

    private static final Set<Role> SELF_REGISTERED = EnumSet.of(Role.PARENT, Role.DRIVER, Role.SCHOOL);

    /** Creates the account only; the role details come next, in onboarding. */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (!SELF_REGISTERED.contains(request.getAccountType())) {
            throw BusinessException.badRequest("accountType must be PARENT, DRIVER or SCHOOL");
        }
        User user = createUser(request, request.getAccountType());
        if (user.getRole() == Role.DRIVER) {
            driverService.createProfile(user);
        }
        return issueTokens(user, request.getDeviceId(), request.getDeviceName());
    }

    private User createUser(RegisterRequest request, Role role) {
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }

        String phone = PhoneNumbers.normalize(request.getPhone());
        if (userRepository.existsByPhone(phone)) {
            throw new PhoneAlreadyExistsException();
        }

        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .phone(phone)
                .passwordChangedAt(Instant.now())
                .role(role)
                .emailVerified(false) // set true after email verification flow
                .build();

        user = userRepository.save(user);
        log.info("New {} registered: {}", user.getRole(), user.getId());
        return user;
    }

    // ─── Login ───────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Spring Security verifies credentials and enabled/locked status, throwing on failure
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        normalizeEmail(request.getEmail()),
                        request.getPassword()
                )
        );

        User user = (User) authentication.getPrincipal();
        log.info("User logged in: {} ({})", user.getId(), user.getRole());
        return issueTokens(user, request.getDeviceId(), request.getDeviceName());
    }

    // ─── Token Refresh ───────────────────────────────────────────────────────

    @Transactional(noRollbackFor = InvalidTokenException.class)
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        IssuedToken issued = refreshTokenService.rotateRefreshToken(request.getRefreshToken());
        return toAuthResponse(issued.user(), issued);
    }

    // ─── Logout ──────────────────────────────────────────────────────────────

    /** Logs out this device if its refresh token is given, otherwise every device. */
    @Transactional
    public void logout(User currentUser, RefreshTokenRequest request) {
        if (request != null && request.getRefreshToken() != null) {
            refreshTokenService.revokeToken(request.getRefreshToken());
        } else {
            refreshTokenService.revokeAllTokensForUser(currentUser);
        }
        log.info("User logged out: {}", currentUser.getId());
    }

    // ─── Kid device pairing ──────────────────────────────────────────────────

    /**
     * Signs in a kid's phone using the one-time code their parent generated. The kid account can only
     * see its own trips and confirm pickups/drop-offs. Pairing a new phone signs out the previous one.
     */
    @Transactional
    public AuthResponse pairKidDevice(KidPairRequest request) {
        String codeHash = TokenUtils.sha256Hex(request.code().trim().toUpperCase());
        Child child = childRepository.findByPairingCodeHash(codeHash)
                .filter(c -> c.getArchivedAt() == null)
                .filter(c -> c.getPairingCodeExpiresAt() != null && c.getPairingCodeExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new InvalidTokenException("Pairing code is invalid or has expired"));

        User kid = child.getKidUser();
        if (kid == null) {
            kid = userRepository.save(User.builder()
                    .firstName(child.getFirstName())
                    .lastName(child.getLastName())
                    // Kids never sign in with a password; these only satisfy the users table constraints
                    .email("kid-" + child.getId() + "@kids.schooldrop.internal")
                    .password(passwordEncoder.encode(TokenUtils.randomUrlToken(32)))
                    .role(Role.KID)
                    .parent(child.getParent())
                    .onboardedAt(Instant.now())
                    .emailVerified(true)
                    .build());
            child.setKidUser(kid);
        } else {
            kid.setEnabled(true);
            refreshTokenService.revokeAllTokensForUser(kid);
        }

        // Single use
        child.setPairingCodeHash(null);
        child.setPairingCodeExpiresAt(null);
        log.info("Kid device paired for child {}", child.getId());
        return issueTokens(kid, request.deviceId(), child.getFirstName() + "'s phone");
    }

    // ─── Password ────────────────────────────────────────────────────────────

    /** Signs out every device afterwards; the caller logs in again with the new password. */
    @Transactional
    public void changePassword(User currentUser, ChangePasswordRequest request) {
        User user = userRepository.findById(currentUser.getId()).orElseThrow();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new InvalidTokenException("Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setPasswordChangedAt(Instant.now());
        user.setPasswordChangeRequired(false);
        user.setSessionsValidFrom(Instant.now());
        refreshTokenService.revokeAllTokensForUser(user);
        log.info("User {} changed their password", user.getId());
    }

    // ─── Profile ─────────────────────────────────────────────────────────────

    public UserProfileResponse getProfile(User currentUser) {
        return profiles.of(currentUser);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** A fresh access + refresh token pair for one device (sign-in, or after a password / email change). */
    @Transactional
    public AuthResponse issueTokens(User user, String deviceId, String deviceName) {
        return toAuthResponse(user, refreshTokenService.createRefreshToken(user, deviceId, deviceName));
    }

    private AuthResponse toAuthResponse(User user, IssuedToken refreshToken) {
        return AuthResponse.builder()
                .accessToken(jwtUtil.generateAccessToken(user))
                .refreshToken(refreshToken.rawToken())
                .tokenType(AuthResponse.BEARER)
                .expiresIn(accessTokenExpiryMs / 1000)
                .user(profiles.of(user))
                .build();
    }

    private static String normalizeEmail(String email) {
        return email.toLowerCase().trim();
    }
}

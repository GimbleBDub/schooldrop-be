package com.gimble.bus_stop.services;

import com.gimble.bus_stop.entities.Token;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.exceptions.InvalidTokenException;
import com.gimble.bus_stop.repositories.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.gimble.bus_stop.security.util.TokenUtils.randomUrlToken;
import static com.gimble.bus_stop.security.util.TokenUtils.sha256Hex;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    public static final String DEFAULT_DEVICE = "default";

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${app.jwt.refresh-token-expiry-ms}")
    private long refreshTokenExpiryMs;

    /**
     * Issues a refresh token for one device, revoking any previous token for that same device.
     * Returns the raw token — only its hash is stored.
     */
    @Transactional
    public IssuedToken createRefreshToken(User user, String deviceId) {
        return createRefreshToken(user, deviceId, null);
    }

    @Transactional
    public IssuedToken createRefreshToken(User user, String deviceId, String deviceName) {
        return issue(user, deviceId, deviceName, Instant.now());
    }

    private IssuedToken issue(User user, String deviceId, String deviceName, Instant signedInAt) {
        String device = (deviceId == null || deviceId.isBlank()) ? DEFAULT_DEVICE : deviceId;
        refreshTokenRepository.revokeUserDeviceTokens(user, device);

        String rawToken = generateRawToken();
        Token token = Token.builder()
                .tokenHash(hash(rawToken))
                .user(user)
                .deviceId(device)
                .deviceName(deviceName == null || deviceName.isBlank() ? null : deviceName.trim())
                .signedInAt(signedInAt)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusMillis(refreshTokenExpiryMs))
                .build();

        return new IssuedToken(rawToken, refreshTokenRepository.save(token));
    }

    // noRollbackFor: the reuse-detection revoke below must survive the exception we throw
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public IssuedToken rotateRefreshToken(String rawToken) {
        Token existing = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Refresh token not found"));

        if (!existing.isValid()) {
            // If token is already invalid/expired, revoke ALL tokens for this user
            // This detects token reuse — potential theft indicator
            refreshTokenRepository.revokeAllUserTokens(existing.getUser());
            throw new InvalidTokenException("Refresh token is expired or revoked. Please log in again.");
        }

        User user = existing.getUser();
        if (!user.isEnabled() || !user.isAccountNonLocked()) {
            refreshTokenRepository.revokeAllUserTokens(user);
            throw new InvalidTokenException("Account is disabled. Please contact support.");
        }

        // Rotation — each refresh token can only be used once; the device keeps its name and sign-in time
        return issue(user, existing.getDeviceId(), existing.getDeviceName(), existing.getSignedInAt());
    }

    /** Revokes a single device's token (normal logout). Unknown tokens are ignored. */
    @Transactional
    public void revokeToken(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken)).ifPresent(token -> token.setRevoked(true));
    }

    @Transactional
    public void revokeAllTokensForUser(User user) {
        refreshTokenRepository.revokeAllUserTokens(user);
    }

    /** The user's signed-in devices (one live refresh token each), most recently active first. */
    @Transactional(readOnly = true)
    public List<Token> activeSessions(User user) {
        return refreshTokenRepository.findActive(user, Instant.now());
    }

    /** Signs one device out. Returns false if it isn't one of the user's live sessions. */
    @Transactional
    public boolean revokeSession(User user, UUID sessionId) {
        return refreshTokenRepository.findById(sessionId)
                .filter(t -> t.getUser().getId().equals(user.getId()) && t.isValid())
                .map(t -> {
                    t.setRevoked(true);
                    return true;
                })
                .orElse(false);
    }

    /** Signs out every device except the one with this device id. */
    @Transactional
    public int revokeOtherSessions(User user, String keepDeviceId) {
        List<Token> others = refreshTokenRepository.findActive(user, Instant.now()).stream()
                .filter(t -> !t.getDeviceId().equals(keepDeviceId))
                .toList();
        others.forEach(t -> t.setRevoked(true));
        return others.size();
    }

    private static String generateRawToken() {
        return randomUrlToken(32);
    }

    private static String hash(String rawToken) {
        return sha256Hex(rawToken);
    }

    public record IssuedToken(String rawToken, Token token) {
        public User user() {
            return token.getUser();
        }
    }
}

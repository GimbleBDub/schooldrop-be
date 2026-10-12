package com.gimble.bus_stop.security.jwt;

import com.gimble.bus_stop.entities.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Component
@Slf4j
public class JwtUtil {

    @Value("${app.jwt.secret}")
    private String secretKey;

    @Value("${app.jwt.access-token-expiry-ms}")
    private long accessTokenExpiryMs;

    // --- Token Generation ---

    public String generateAccessToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole().name());
        claims.put("firstName", user.getFirstName());
        claims.put("lastName", user.getLastName());
        if (user.getParent() != null) {
            claims.put("parentId", user.getParent().getId().toString());
        }
        return buildToken(claims, user.getId().toString(), user.getEmail(), accessTokenExpiryMs);
    }

    private String buildToken(Map<String, Object> extraClaims,
                              String subject,
                              String email,
                              long expirationMs) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(subject)           // subject = user UUID
                .claim("email", email)
                // Millisecond issue time: "sign out everywhere" must also catch tokens from the same second
                .claim("issuedAtMs", System.currentTimeMillis())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    // --- Token Validation ---

    public boolean isTokenValid(String token, User user) {
        try {
            final String userId = extractUserId(token);
            return userId.equals(user.getId().toString()) && !isTokenExpired(token) && !issuedBeforeCutoff(token, user);
        } catch (JwtException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    /** True for tokens issued before the user's last password/email change or "sign out everywhere". */
    private boolean issuedBeforeCutoff(String token, User user) {
        if (user.getSessionsValidFrom() == null) {
            return false;
        }
        Number issuedAtMs = extractClaim(token, claims -> claims.get("issuedAtMs", Number.class));
        if (issuedAtMs != null) {
            return issuedAtMs.longValue() < user.getSessionsValidFrom().toEpochMilli();
        }
        // Older tokens: iat only has second precision
        Date issuedAt = extractClaim(token, Claims::getIssuedAt);
        return issuedAt == null
                || issuedAt.toInstant().isBefore(user.getSessionsValidFrom().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    public boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    // --- Claims Extraction ---

    public String extractUserId(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractEmail(String token) {
        return extractClaim(token, claims -> claims.get("email", String.class));
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }
}

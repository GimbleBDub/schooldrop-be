package com.gimble.bus_stop.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Token {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // SHA-256 of the token handed to the client — a DB leak doesn't leak usable tokens
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // One active refresh token per device, so logging in on the web doesn't log out the phone
    @Column(nullable = false, length = 100)
    private String deviceId;

    @Column(nullable = false)
    private Instant expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean revoked = false;

    // e.g. "Chrome on macOS", "iPhone" — shown in Settings → Signed-in devices
    @Column(length = 100)
    private String deviceName;

    // When this device first signed in; carried over on every rotation
    @Column(nullable = false)
    private Instant signedInAt;

    // When this token was issued, i.e. the device's last refresh
    @Column(nullable = false)
    private Instant createdAt;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }
}

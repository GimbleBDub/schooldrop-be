package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Token;
import com.gimble.bus_stop.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<Token, UUID> {

    Optional<Token> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE Token rt SET rt.revoked = true WHERE rt.user = :user AND rt.revoked = false")
    void revokeAllUserTokens(User user);

    @Modifying
    @Query("UPDATE Token rt SET rt.revoked = true WHERE rt.user = :user AND rt.deviceId = :deviceId AND rt.revoked = false")
    void revokeUserDeviceTokens(User user, String deviceId);

    @Modifying
    @Query("DELETE FROM Token rt WHERE rt.user = :user")
    void deleteAllByUser(User user);

    long countByUserAndRevokedFalse(User user);

    @Query("SELECT t FROM Token t WHERE t.user = :user AND t.revoked = false AND t.expiresAt > :now ORDER BY t.createdAt DESC")
    List<Token> findActive(User user, java.time.Instant now);
}

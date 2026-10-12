package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByToken(String token);

    @Query("SELECT d.token FROM DeviceToken d WHERE d.user.id = :userId")
    List<String> findTokensByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.token = :token AND d.user.id = :userId")
    int deleteByTokenAndUserId(String token, UUID userId);
}

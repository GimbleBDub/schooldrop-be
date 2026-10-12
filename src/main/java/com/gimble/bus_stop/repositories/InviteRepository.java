package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Invite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InviteRepository extends JpaRepository<Invite, UUID> {

    Optional<Invite> findByCodeHash(String codeHash);

    @Query("""
            SELECT i FROM Invite i WHERE i.household.id = :householdId
              AND i.acceptedAt IS NULL AND i.revokedAt IS NULL AND i.expiresAt > :now
            ORDER BY i.createdAt DESC
            """)
    List<Invite> findOpenForHousehold(UUID householdId, Instant now);

    @Query("""
            SELECT i FROM Invite i WHERE i.operator.id = :operatorId
              AND i.acceptedAt IS NULL AND i.revokedAt IS NULL AND i.expiresAt > :now
            ORDER BY i.createdAt DESC
            """)
    List<Invite> findOpenForOperator(UUID operatorId, Instant now);

    @Query("""
            SELECT i FROM Invite i WHERE i.school.id = :schoolId
              AND i.acceptedAt IS NULL AND i.revokedAt IS NULL AND i.expiresAt > :now
            ORDER BY i.createdAt DESC
            """)
    List<Invite> findOpenForSchool(UUID schoolId, Instant now);
}

package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.HouseholdMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, UUID> {

    @Query("""
            SELECT m FROM HouseholdMember m JOIN FETCH m.household h JOIN FETCH h.owner
            WHERE m.user.id = :userId
            """)
    Optional<HouseholdMember> findByUserId(UUID userId);

    @Query("SELECT m FROM HouseholdMember m JOIN FETCH m.user WHERE m.household.id = :householdId ORDER BY m.joinedAt")
    List<HouseholdMember> findByHouseholdId(UUID householdId);

    /** Everyone in the household of the parent who owns these children. */
    @Query("""
            SELECT m FROM HouseholdMember m JOIN FETCH m.user u
            WHERE m.household.owner.id = :ownerId AND u.enabled = true
            """)
    List<HouseholdMember> findByOwnerId(UUID ownerId);
}

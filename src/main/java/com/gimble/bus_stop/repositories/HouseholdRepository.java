package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Household;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface HouseholdRepository extends JpaRepository<Household, UUID> {

    Optional<Household> findByOwnerId(UUID ownerId);
}

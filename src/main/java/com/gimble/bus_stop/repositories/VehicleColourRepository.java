package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.VehicleColour;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleColourRepository extends JpaRepository<VehicleColour, UUID> {

    List<VehicleColour> findAllByOrderByNameAsc();

    List<VehicleColour> findByActiveTrueOrderByNameAsc();

    Optional<VehicleColour> findByNameIgnoreCase(String name);
}

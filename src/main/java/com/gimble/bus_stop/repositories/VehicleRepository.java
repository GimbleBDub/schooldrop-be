package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    List<Vehicle> findByDriverIdOrderByCreatedAt(UUID driverId);

    List<Vehicle> findByDriverIdAndActiveTrue(UUID driverId);

    Optional<Vehicle> findByIdAndDriverId(UUID id, UUID driverId);

    boolean existsByPlateNumber(String plateNumber);

    List<Vehicle> findByOperatorIdOrderByCreatedAt(UUID operatorId);

    List<Vehicle> findByOperatorIdAndActiveTrue(UUID operatorId);
}

package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.DriverSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DriverSubscriptionRepository extends JpaRepository<DriverSubscription, UUID> {

    List<DriverSubscription> findByDriverIdOrderByCreatedAtDesc(UUID driverId);
}

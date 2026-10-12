package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Operator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OperatorRepository extends JpaRepository<Operator, UUID> {

    boolean existsByNameIgnoreCase(String name);
}

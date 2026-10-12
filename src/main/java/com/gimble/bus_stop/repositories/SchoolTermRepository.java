package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.SchoolTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SchoolTermRepository extends JpaRepository<SchoolTerm, UUID> {

    List<SchoolTerm> findByEndDateGreaterThanEqualOrderByStartDate(LocalDate date);

    List<SchoolTerm> findAllByOrderByStartDateDesc();

    boolean existsByName(String name);
}

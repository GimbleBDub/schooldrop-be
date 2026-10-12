package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.SchoolStaff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SchoolStaffRepository extends JpaRepository<SchoolStaff, UUID> {

    @Query("SELECT ss FROM SchoolStaff ss JOIN FETCH ss.school WHERE ss.user.id = :userId")
    Optional<SchoolStaff> findByUserId(UUID userId);

    @Query("SELECT ss FROM SchoolStaff ss JOIN FETCH ss.user WHERE ss.school.id = :schoolId ORDER BY ss.createdAt")
    List<SchoolStaff> findBySchoolId(UUID schoolId);

    long countBySchoolIdAndRole(UUID schoolId, com.gimble.bus_stop.enums.SchoolRole role);
}

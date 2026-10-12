package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Child;
import com.gimble.bus_stop.enums.EnrollmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChildRepository extends JpaRepository<Child, UUID> {

    @Query("SELECT c FROM Child c LEFT JOIN FETCH c.school WHERE c.parent.id = :parentId AND c.archivedAt IS NULL ORDER BY c.createdAt")
    List<Child> findByParentId(UUID parentId);

    Optional<Child> findByIdAndParentIdAndArchivedAtIsNull(UUID id, UUID parentId);

    Optional<Child> findByPairingCodeHash(String pairingCodeHash);

    Optional<Child> findByKidUserId(UUID kidUserId);

    @Query("""
            SELECT c FROM Child c JOIN FETCH c.parent
            WHERE c.school.id = :schoolId AND c.archivedAt IS NULL AND (:status IS NULL OR c.enrollmentStatus = :status)
            ORDER BY c.lastName, c.firstName
            """)
    Page<Child> findBySchool(UUID schoolId, EnrollmentStatus status, Pageable pageable);
}

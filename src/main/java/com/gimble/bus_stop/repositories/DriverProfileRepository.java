package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.DriverProfile;
import com.gimble.bus_stop.enums.DriverStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverProfileRepository extends JpaRepository<DriverProfile, UUID> {

    @Query("SELECT d FROM DriverProfile d JOIN FETCH d.user WHERE d.user.id = :userId")
    Optional<DriverProfile> findByUserId(UUID userId);

    @Query(value = """
            SELECT d FROM DriverProfile d JOIN FETCH d.user u
            WHERE (:status IS NULL OR d.status = :status)
              AND (:query IS NULL
                   OR LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR u.phone LIKE CONCAT('%', CAST(:query AS string), '%'))
            ORDER BY d.submittedAt ASC NULLS LAST, d.createdAt ASC
            """,
            countQuery = """
            SELECT COUNT(d) FROM DriverProfile d JOIN d.user u
            WHERE (:status IS NULL OR d.status = :status)
              AND (:query IS NULL
                   OR LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR u.phone LIKE CONCAT('%', CAST(:query AS string), '%'))
            """)
    Page<DriverProfile> search(DriverStatus status, String query, Pageable pageable);

    long countByStatus(DriverStatus status);

    @Query("SELECT d FROM DriverProfile d JOIN FETCH d.user WHERE d.operator.id = :operatorId ORDER BY d.createdAt")
    List<DriverProfile> findTeam(UUID operatorId);

    List<DriverProfile> findByStatus(DriverStatus status);

    @Query("SELECT d FROM DriverProfile d JOIN FETCH d.user WHERE d.subscriptionPaidUntil = :date")
    List<DriverProfile> findBySubscriptionPaidUntil(java.time.LocalDate date);
}

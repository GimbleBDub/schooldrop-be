package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.AdHocRequest;
import com.gimble.bus_stop.enums.AdHocStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdHocRequestRepository extends JpaRepository<AdHocRequest, UUID> {

    @Query("""
            SELECT r FROM AdHocRequest r JOIN FETCH r.child JOIN FETCH r.school
            LEFT JOIN FETCH r.driver d LEFT JOIN FETCH d.user LEFT JOIN FETCH r.offer o LEFT JOIN FETCH o.vehicle
            WHERE r.parent.id = :parentId ORDER BY r.pickupAt DESC
            """)
    List<AdHocRequest> findByParentId(UUID parentId);

    Optional<AdHocRequest> findByIdAndParentId(UUID id, UUID parentId);

    /** Open requests at the schools a driver serves. */
    @Query("""
            SELECT r FROM AdHocRequest r JOIN FETCH r.child JOIN FETCH r.school
            WHERE r.status = com.gimble.bus_stop.enums.AdHocStatus.OPEN AND r.school.id IN :schoolIds
              AND r.pickupAt > :now
            ORDER BY r.pickupAt
            """)
    List<AdHocRequest> findOpenForSchools(Collection<UUID> schoolIds, Instant now);

    @Query("""
            SELECT r FROM AdHocRequest r JOIN FETCH r.child JOIN FETCH r.school
            WHERE r.driver.id = :driverId ORDER BY r.pickupAt DESC
            """)
    List<AdHocRequest> findByDriverId(UUID driverId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM AdHocRequest r WHERE r.id = :id")
    Optional<AdHocRequest> findByIdForUpdate(UUID id);

    List<AdHocRequest> findByStatusInAndPickupAtBefore(Collection<AdHocStatus> statuses, Instant time);

    /** Confirmed once-off pickups at a school in a time window — what the gate staff need to see. */
    @Query("""
            SELECT r FROM AdHocRequest r JOIN FETCH r.child JOIN FETCH r.driver d JOIN FETCH d.user
            JOIN FETCH r.offer o JOIN FETCH o.vehicle
            WHERE r.school.id = :schoolId AND r.status = com.gimble.bus_stop.enums.AdHocStatus.CONFIRMED
              AND r.pickupAt >= :from AND r.pickupAt < :to
            ORDER BY r.pickupAt
            """)
    List<AdHocRequest> findConfirmedAtSchool(UUID schoolId, Instant from, Instant to);
}

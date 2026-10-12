package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.TripPassenger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripPassengerRepository extends JpaRepository<TripPassenger, UUID> {

    @Query("""
            SELECT p FROM TripPassenger p JOIN FETCH p.trip t JOIN FETCH p.child c JOIN FETCH c.parent
            WHERE p.id = :id
            """)
    Optional<TripPassenger> findWithTrip(UUID id);

    /** Home drop-offs in the window that the family hasn't confirmed or disputed. */
    @Query("""
            SELECT p FROM TripPassenger p JOIN FETCH p.trip t JOIN FETCH t.driver d JOIN FETCH d.user
            JOIN FETCH p.child c JOIN FETCH c.parent
            WHERE t.leg = com.gimble.bus_stop.enums.TripLeg.FROM_SCHOOL
              AND p.droppedOffAt >= :from AND p.droppedOffAt < :to
              AND p.dropoffConfirmedAt IS NULL AND p.disputedAt IS NULL
            """)
    List<TripPassenger> findUnconfirmedHomeDropoffs(Instant from, Instant to);
}

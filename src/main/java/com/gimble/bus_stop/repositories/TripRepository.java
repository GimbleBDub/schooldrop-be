package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Trip;
import com.gimble.bus_stop.enums.TripLeg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    Optional<Trip> findByOfferIdAndTripDateAndLeg(UUID offerId, LocalDate tripDate, TripLeg leg);

    Optional<Trip> findByAdHocRequestId(UUID adHocRequestId);

    @Query("""
            SELECT DISTINCT t FROM Trip t JOIN FETCH t.school JOIN FETCH t.vehicle
            LEFT JOIN FETCH t.passengers p LEFT JOIN FETCH p.child
            WHERE t.driver.id = :driverId AND t.tripDate = :date
            ORDER BY t.leg
            """)
    List<Trip> findForDriverOn(UUID driverId, LocalDate date);

    @Query("""
            SELECT DISTINCT t FROM Trip t JOIN FETCH t.school JOIN FETCH t.vehicle JOIN FETCH t.driver d JOIN FETCH d.user
            LEFT JOIN FETCH t.passengers p LEFT JOIN FETCH p.child
            WHERE d.operator.id = :operatorId AND t.tripDate = :date
            ORDER BY t.leg
            """)
    List<Trip> findForOperatorOn(UUID operatorId, LocalDate date);

    @Query("""
            SELECT t FROM Trip t WHERE t.offer.id = :offerId AND t.tripDate >= :from
              AND t.status = com.gimble.bus_stop.enums.TripStatus.SCHEDULED
            """)
    List<Trip> findScheduledForOfferFrom(UUID offerId, LocalDate from);

    Optional<Trip> findByIdAndDriverId(UUID id, UUID driverId);

    /** Trips today that carry any of these children (for parents and kids). */
    @Query("""
            SELECT DISTINCT t FROM Trip t JOIN FETCH t.school JOIN FETCH t.vehicle JOIN FETCH t.driver d JOIN FETCH d.user
            JOIN FETCH t.passengers p JOIN FETCH p.child c
            WHERE t.tripDate = :date AND c.id IN :childIds
            """)
    List<Trip> findCarryingChildrenOn(List<UUID> childIds, LocalDate date);

    @Query("""
            SELECT DISTINCT t FROM Trip t JOIN FETCH t.vehicle JOIN FETCH t.driver d JOIN FETCH d.user
            LEFT JOIN FETCH t.passengers p LEFT JOIN FETCH p.child
            WHERE t.school.id = :schoolId AND t.tripDate = :date
            """)
    List<Trip> findAtSchoolOn(UUID schoolId, LocalDate date);

    @Query("""
            SELECT DISTINCT t FROM Trip t JOIN FETCH t.school JOIN FETCH t.vehicle JOIN FETCH t.driver d JOIN FETCH d.user
            WHERE t.status = com.gimble.bus_stop.enums.TripStatus.IN_PROGRESS
            """)
    List<Trip> findInProgress();
}

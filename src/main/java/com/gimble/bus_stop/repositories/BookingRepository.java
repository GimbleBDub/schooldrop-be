package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Booking;
import com.gimble.bus_stop.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** Seats held on the offer on any day in [start, end]. Run after locking the offer row. */
    @Query("""
            SELECT COUNT(b) FROM Booking b
            WHERE b.offer.id = :offerId
              AND b.status IN (com.gimble.bus_stop.enums.BookingStatus.PENDING_PAYMENT,
                               com.gimble.bus_stop.enums.BookingStatus.ACTIVE)
              AND b.startDate <= :end AND b.endDate >= :start
            """)
    long countSeatsHeld(UUID offerId, LocalDate start, LocalDate end);

    @Query("""
            SELECT COUNT(b) > 0 FROM Booking b
            WHERE b.child.id = :childId
              AND b.status IN (com.gimble.bus_stop.enums.BookingStatus.PENDING_PAYMENT,
                               com.gimble.bus_stop.enums.BookingStatus.ACTIVE)
            """)
    boolean existsActiveForChild(UUID childId);

    boolean existsByChildId(UUID childId);

    /** A child can't have two overlapping bookings on the same offer. */
    @Query("""
            SELECT COUNT(b) > 0 FROM Booking b
            WHERE b.child.id = :childId AND b.offer.id = :offerId
              AND b.status IN (com.gimble.bus_stop.enums.BookingStatus.PENDING_PAYMENT,
                               com.gimble.bus_stop.enums.BookingStatus.ACTIVE)
              AND b.startDate <= :end AND b.endDate >= :start
            """)
    boolean existsOverlapping(UUID childId, UUID offerId, LocalDate start, LocalDate end);

    @Query("""
            SELECT b FROM Booking b JOIN FETCH b.child JOIN FETCH b.offer o JOIN FETCH o.school
            JOIN FETCH o.vehicle JOIN FETCH o.driver d JOIN FETCH d.user
            WHERE b.parent.id = :parentId ORDER BY b.createdAt DESC
            """)
    List<Booking> findByParentId(UUID parentId);

    Optional<Booking> findByIdAndParentId(UUID id, UUID parentId);

    @Query("""
            SELECT b FROM Booking b JOIN FETCH b.child c JOIN FETCH b.parent JOIN FETCH b.offer o JOIN FETCH o.school
            WHERE o.driver.id = :driverId AND (:status IS NULL OR b.status = :status)
            ORDER BY b.startDate DESC
            """)
    List<Booking> findByDriverId(UUID driverId, BookingStatus status);

    @Query("""
            SELECT b FROM Booking b JOIN FETCH b.child c JOIN FETCH b.parent JOIN FETCH b.offer o JOIN FETCH o.school
            WHERE o.driver.operator.id = :operatorId AND (:status IS NULL OR b.status = :status)
            ORDER BY b.startDate DESC
            """)
    List<Booking> findByOperatorId(UUID operatorId, BookingStatus status);

    /** Active bookings riding this offer on the date — the passenger list for that day's trips. */
    @Query("""
            SELECT b FROM Booking b JOIN FETCH b.child
            WHERE b.offer.id = :offerId AND b.status = com.gimble.bus_stop.enums.BookingStatus.ACTIVE
              AND b.startDate <= :date AND b.endDate >= :date
            """)
    List<Booking> findRidingOn(UUID offerId, LocalDate date);

    List<Booking> findByStatusAndHoldExpiresAtBefore(BookingStatus status, Instant now);

    List<Booking> findByStatusAndEndDateBefore(BookingStatus status, LocalDate date);

    @Query("""
            SELECT b FROM Booking b
            WHERE b.offer.driver.id = :driverId AND b.status = com.gimble.bus_stop.enums.BookingStatus.ACTIVE
              AND b.endDate >= :today
            """)
    List<Booking> findCurrentForDriver(UUID driverId, LocalDate today);

    @Query("""
            SELECT b FROM Booking b JOIN FETCH b.parent
            WHERE b.offer.id = :offerId AND b.endDate >= :today
              AND b.status = com.gimble.bus_stop.enums.BookingStatus.ACTIVE
            """)
    List<Booking> findCurrentForOffer(UUID offerId, LocalDate today);

    @Query(value = """
            SELECT b FROM Booking b JOIN FETCH b.child JOIN FETCH b.parent JOIN FETCH b.offer o
            JOIN FETCH o.school JOIN FETCH o.driver d JOIN FETCH d.user
            WHERE (:status IS NULL OR b.status = :status)
            ORDER BY b.createdAt DESC
            """,
            countQuery = "SELECT COUNT(b) FROM Booking b WHERE (:status IS NULL OR b.status = :status)")
    Page<Booking> adminSearch(BookingStatus status, Pageable pageable);

    long countByStatus(BookingStatus status);
}

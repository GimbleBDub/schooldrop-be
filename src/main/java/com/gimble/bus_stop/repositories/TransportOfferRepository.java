package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.TransportOffer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransportOfferRepository extends JpaRepository<TransportOffer, UUID> {

    @Query("""
            SELECT o FROM TransportOffer o JOIN FETCH o.vehicle JOIN FETCH o.school
            WHERE o.driver.id = :driverId ORDER BY o.createdAt
            """)
    List<TransportOffer> findByDriverId(UUID driverId);

    Optional<TransportOffer> findByIdAndDriverId(UUID id, UUID driverId);

    /** Every route of a team / company, whoever drives it. */
    @Query("""
            SELECT o FROM TransportOffer o JOIN FETCH o.vehicle JOIN FETCH o.school JOIN FETCH o.driver d JOIN FETCH d.user
            WHERE d.operator.id = :operatorId ORDER BY o.createdAt
            """)
    List<TransportOffer> findByOperatorId(UUID operatorId);

    /** Every offer using a vehicle, whoever drives it (seats on a shared vehicle can't be oversold). */
    @Query("SELECT o FROM TransportOffer o WHERE o.vehicle.id = :vehicleId")
    List<TransportOffer> findByVehicleId(UUID vehicleId);

    /** Offers parents can book: active, at the school, run by an approved driver. */
    @Query("""
            SELECT o FROM TransportOffer o
            JOIN FETCH o.driver d JOIN FETCH d.user JOIN FETCH o.vehicle JOIN FETCH o.school
            LEFT JOIN d.operator op LEFT JOIN op.owner ow
            WHERE o.school.id = :schoolId AND o.active = true AND o.vehicle.active = true
              AND d.status = com.gimble.bus_stop.enums.DriverStatus.APPROVED
              AND (op IS NULL OR ow.status = com.gimble.bus_stop.enums.DriverStatus.APPROVED)
            ORDER BY o.createdAt
            """)
    List<TransportOffer> findBookableBySchool(UUID schoolId);

    /** Approved drivers at the school who take once-off pickups. */
    @Query("""
            SELECT o FROM TransportOffer o JOIN FETCH o.driver d JOIN FETCH d.user JOIN FETCH o.vehicle
            LEFT JOIN d.operator op LEFT JOIN op.owner ow
            WHERE o.school.id = :schoolId AND o.active = true AND o.vehicle.active = true
              AND o.adHocFare IS NOT NULL AND d.acceptsAdHoc = true
              AND d.status = com.gimble.bus_stop.enums.DriverStatus.APPROVED
              AND (op IS NULL OR ow.status = com.gimble.bus_stop.enums.DriverStatus.APPROVED)
            """)
    List<TransportOffer> findAdHocEligibleBySchool(UUID schoolId);

    /** Locks the offer row so concurrent bookings can't oversell the last seat. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM TransportOffer o WHERE o.id = :id")
    Optional<TransportOffer> findByIdForUpdate(UUID id);

    @Query("SELECT o FROM TransportOffer o WHERE o.active = true AND o.driver.status = com.gimble.bus_stop.enums.DriverStatus.APPROVED")
    List<TransportOffer> findAllRunning();

    @Modifying
    @Query("UPDATE TransportOffer o SET o.active = false WHERE o.driver.id = :driverId AND o.active = true")
    int deactivateAllForDriver(UUID driverId);
}

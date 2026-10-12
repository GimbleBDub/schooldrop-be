package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Advert;
import com.gimble.bus_stop.enums.AdvertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AdvertRepository extends JpaRepository<Advert, UUID> {

    /** Live adverts for viewers linked to these schools (plus untargeted ones). */
    @Query("""
            SELECT a FROM Advert a LEFT JOIN FETCH a.targetSchool
            WHERE a.status = com.gimble.bus_stop.enums.AdvertStatus.ACTIVE
              AND a.startsAt <= :now AND a.endsAt > :now
              AND (a.targetSchool IS NULL OR a.targetSchool.id IN :schoolIds)
            """)
    List<Advert> findLiveFor(Collection<UUID> schoolIds, Instant now);

    @Query("""
            SELECT a FROM Advert a LEFT JOIN FETCH a.targetSchool
            WHERE a.status = com.gimble.bus_stop.enums.AdvertStatus.ACTIVE
              AND a.startsAt <= :now AND a.endsAt > :now AND a.targetSchool IS NULL
            """)
    List<Advert> findLiveUntargeted(Instant now);

    /** Drivers with a live advert at this school — their offers show as "Featured". */
    @Query("""
            SELECT DISTINCT a.driver.id FROM Advert a
            WHERE a.driver IS NOT NULL AND a.status = com.gimble.bus_stop.enums.AdvertStatus.ACTIVE
              AND a.startsAt <= :now AND a.endsAt > :now
              AND (a.targetSchool IS NULL OR a.targetSchool.id = :schoolId)
            """)
    List<UUID> findFeaturedDriverIds(UUID schoolId, Instant now);

    @Query("SELECT a FROM Advert a LEFT JOIN FETCH a.targetSchool WHERE a.driver.id = :driverId ORDER BY a.createdAt DESC")
    List<Advert> findByDriverId(UUID driverId);

    @Query(value = """
            SELECT a FROM Advert a LEFT JOIN FETCH a.targetSchool
            WHERE (:status IS NULL OR a.status = :status) ORDER BY a.createdAt DESC
            """,
            countQuery = "SELECT COUNT(a) FROM Advert a WHERE (:status IS NULL OR a.status = :status)")
    Page<Advert> adminSearch(AdvertStatus status, Pageable pageable);

    List<Advert> findByStatusAndEndsAtBefore(AdvertStatus status, Instant now);

    @Modifying
    @Query("UPDATE Advert a SET a.impressions = a.impressions + 1 WHERE a.id = :id")
    void countImpression(UUID id);

    @Modifying
    @Query("UPDATE Advert a SET a.clicks = a.clicks + 1 WHERE a.id = :id")
    void countClick(UUID id);
}

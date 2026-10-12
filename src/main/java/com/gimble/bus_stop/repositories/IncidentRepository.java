package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Incident;
import com.gimble.bus_stop.enums.IncidentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    @Query(value = """
            SELECT i FROM Incident i JOIN FETCH i.reporter LEFT JOIN FETCH i.driver d LEFT JOIN FETCH d.user
            WHERE (:status IS NULL OR i.status = :status)
            ORDER BY CASE WHEN i.type = com.gimble.bus_stop.enums.IncidentType.SOS THEN 0 ELSE 1 END, i.createdAt DESC
            """,
            countQuery = "SELECT COUNT(i) FROM Incident i WHERE (:status IS NULL OR i.status = :status)")
    Page<Incident> adminSearch(IncidentStatus status, Pageable pageable);

    List<Incident> findByReporterIdOrderByCreatedAtDesc(UUID reporterId);

    long countByStatus(IncidentStatus status);
}

package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.School;
import com.gimble.bus_stop.enums.SchoolStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface SchoolRepository extends JpaRepository<School, UUID> {

    boolean existsByNameIgnoreCaseAndCityIgnoreCase(String name, String city);

    @Query("""
            SELECT s FROM School s
            WHERE (:status IS NULL OR s.status = :status)
              AND (:query IS NULL OR LOWER(s.name) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR LOWER(s.city) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')))
            """)
    Page<School> search(SchoolStatus status, String query, Pageable pageable);

    long countByStatus(SchoolStatus status);
}

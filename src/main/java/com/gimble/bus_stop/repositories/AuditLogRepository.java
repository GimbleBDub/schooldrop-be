package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query(value = """
            SELECT a FROM AuditLog a LEFT JOIN FETCH a.actor
            WHERE (:targetType IS NULL OR a.targetType = :targetType)
              AND (:targetId IS NULL OR a.targetId = :targetId)
              AND (:actorId IS NULL OR a.actor.id = :actorId)
            ORDER BY a.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(a) FROM AuditLog a
            WHERE (:targetType IS NULL OR a.targetType = :targetType)
              AND (:targetId IS NULL OR a.targetId = :targetId)
              AND (:actorId IS NULL OR a.actor.id = :actorId)
            """)
    Page<AuditLog> search(String targetType, UUID targetId, UUID actorId, Pageable pageable);
}

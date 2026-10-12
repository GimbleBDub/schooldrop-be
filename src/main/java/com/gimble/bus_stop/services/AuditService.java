package com.gimble.bus_stop.services;

import com.gimble.bus_stop.entities.AuditLog;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.repositories.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /** Joins the caller's transaction, so the log entry commits or rolls back with the change. */
    @Transactional
    public void record(User actor, String action, String targetType, UUID targetId, String details) {
        auditLogRepository.save(AuditLog.builder()
                .actor(actor)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .details(details)
                .build());
    }

    @Transactional
    public void recordSystem(String action, String targetType, UUID targetId, String details) {
        record(null, action, targetType, targetId, details);
    }
}

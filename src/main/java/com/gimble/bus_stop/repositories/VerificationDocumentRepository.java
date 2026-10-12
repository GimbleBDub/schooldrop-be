package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.VerificationDocument;
import com.gimble.bus_stop.enums.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface VerificationDocumentRepository extends JpaRepository<VerificationDocument, UUID> {

    @Query("SELECT d FROM VerificationDocument d LEFT JOIN FETCH d.vehicle WHERE d.driver.id = :driverId ORDER BY d.uploadedAt DESC")
    List<VerificationDocument> findByDriverId(UUID driverId);

    List<VerificationDocument> findByStatusAndExpiresOnBefore(DocumentStatus status, LocalDate date);

    List<VerificationDocument> findByStatusAndExpiresOn(DocumentStatus status, LocalDate date);
}

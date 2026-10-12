package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.DocumentStatus;
import com.gimble.bus_stop.enums.DocumentType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An uploaded identity/vehicle document awaiting or past review. The file itself is in FileStorage. */
@Entity
@Table(name = "verification_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VerificationDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private DriverProfile driver;

    // Set for vehicle documents
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DocumentType type;

    @Column(nullable = false)
    private String storageKey;

    @Column(nullable = false)
    private String contentType;

    private long sizeBytes;

    private String originalFilename;

    // Certificate / permit number printed on the document, e.g. the police clearance number
    private String referenceNumber;

    private LocalDate issuedOn;

    private LocalDate expiresOn;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.PENDING;

    private String reviewNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    private Instant reviewedAt;

    @Column(nullable = false, updatable = false)
    private Instant uploadedAt;

    @PrePersist
    protected void onCreate() {
        uploadedAt = Instant.now();
    }
}

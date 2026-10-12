package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.DriverStatus;
import com.gimble.bus_stop.enums.OperatorRole;
import com.gimble.bus_stop.enums.PaymentMethod;
import com.gimble.bus_stop.security.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "driver_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DriverStatus status = DriverStatus.PENDING_DOCUMENTS;

    private String statusReason;

    // Encrypted at rest — never log these
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 512)
    private String omangNumber;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 512)
    private String licenceNumber;

    // Botswana licence code, e.g. "B", "C1", "EB"
    @Column(length = 10)
    private String licenceCode;

    private LocalDate licenceExpiry;

    private LocalDate dateOfBirth;

    private String homeArea;

    // Whether the driver wants once-off (ad-hoc) pickup requests
    @Builder.Default
    @Column(nullable = false)
    private boolean acceptsAdHoc = false;

    // Listed (visible to parents, bookable) only while the monthly fee is paid up to this date.
    // Team drivers are covered by their team owner's fee.
    private LocalDate subscriptionPaidUntil;

    // Team / transport company this driver works in (null = works on their own)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id")
    private Operator operator;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OperatorRole operatorRole;

    // Owners / managers who only run the business don't drive: no licence, never assigned a route
    @Builder.Default
    @Column(nullable = false)
    private boolean drives = true;

    // Where payouts go
    @Enumerated(EnumType.STRING)
    private PaymentMethod payoutMethod;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 512)
    private String payoutAccount;

    // Schools the driver serves
    @Builder.Default
    @ManyToMany
    @JoinTable(name = "driver_schools",
            joinColumns = @JoinColumn(name = "driver_id"),
            inverseJoinColumns = @JoinColumn(name = "school_id"))
    private Set<School> schools = new HashSet<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    private Instant reviewedAt;

    private Instant submittedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isApproved() {
        return status == DriverStatus.APPROVED;
    }

    public boolean isSubscribedOn(LocalDate date) {
        // Getter, not the field: the owner may be a lazy proxy whose fields are empty
        LocalDate paidUntil = business().getSubscriptionPaidUntil();
        return paidUntil != null && !paidUntil.isBefore(date);
    }

    public boolean isInTeam() {
        return operator != null;
    }

    /** The profile that is the business: the team owner for team members, else this driver. */
    public DriverProfile business() {
        return operator != null && operatorRole != OperatorRole.OWNER ? operator.getOwner() : this;
    }

    /** Owners / managers of a team, and drivers working on their own, manage vehicles and routes. */
    public boolean managesRoutes() {
        return operator == null || operatorRole.canManage();
    }

    /** Payouts, listing fee, adverts, billing: the team owner, or a driver on their own. */
    public boolean ownsBusiness() {
        return operator == null || operatorRole == OperatorRole.OWNER;
    }

    public boolean isTeamMate(DriverProfile other) {
        return other != null && operator != null && other.getOperator() != null
                && operator.getId().equals(other.getOperator().getId());
    }

    /** "Kabo Molefe" or "Kabo Molefe · Safe Rides" */
    public String displayName() {
        return user.getFullName() + (operator != null ? " · " + operator.getName() : "");
    }
}

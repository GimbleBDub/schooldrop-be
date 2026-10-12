package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.DriverProfile;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.DriverStatus;
import com.gimble.bus_stop.enums.PaymentMethod;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Full driver record. The driver sees masked identity numbers; verifiers see them in full.
 * missingRequirements lists what still blocks submission/approval, in plain language.
 */
public record DriverProfileResponse(
        UUID id,
        UUID userId,
        String fullName,
        String email,
        String phone,
        String profilePictureUrl,
        DriverStatus status,
        String statusReason,
        String omangNumber,
        String licenceNumber,
        String licenceCode,
        LocalDate licenceExpiry,
        LocalDate dateOfBirth,
        String homeArea,
        boolean acceptsAdHoc,
        PaymentMethod payoutMethod,
        String payoutAccount,
        List<SchoolResponse> schools,
        List<VehicleResponse> vehicles,
        List<DocumentResponse> documents,
        List<String> missingRequirements,
        // Team / transport company (null when driving on their own) and whether this person drives
        com.gimble.bus_stop.dto.team.TeamDtos.TeamCard team,
        boolean drives,
        // What this person can do in the app: set up vehicles & routes, and handle money (payouts, fee, adverts)
        boolean managesRoutes,
        boolean ownsBusiness,
        Instant submittedAt,
        Instant reviewedAt,
        Instant createdAt
) {
    public static DriverProfileResponse from(DriverProfile d,
                                             List<VehicleResponse> vehicles,
                                             List<DocumentResponse> documents,
                                             List<String> missingRequirements,
                                             boolean revealIdentity) {
        User u = d.getUser();
        return new DriverProfileResponse(d.getId(), u.getId(), u.getFullName(), u.getEmail(), u.getPhone(),
                u.getProfilePictureUrl(), d.getStatus(), d.getStatusReason(),
                revealIdentity ? d.getOmangNumber() : mask(d.getOmangNumber()),
                revealIdentity ? d.getLicenceNumber() : mask(d.getLicenceNumber()),
                d.getLicenceCode(), d.getLicenceExpiry(), d.getDateOfBirth(), d.getHomeArea(), d.isAcceptsAdHoc(),
                d.getPayoutMethod(),
                revealIdentity ? d.getPayoutAccount() : mask(d.getPayoutAccount()),
                // A team serves its schools together (held on the owner's profile)
                d.business().getSchools().stream().sorted(Comparator.comparing(s -> s.getName()))
                        .map(SchoolResponse::from).toList(),
                vehicles, documents, missingRequirements, com.gimble.bus_stop.dto.team.TeamDtos.TeamCard.of(d), d.isDrives(),
                d.managesRoutes(), d.ownsBusiness(), d.getSubmittedAt(), d.getReviewedAt(), d.getCreatedAt());
    }

    /** Shows only the last 3 characters, e.g. "******789". */
    static String mask(String value) {
        if (value == null) {
            return null;
        }
        int visible = Math.min(3, value.length());
        return "*".repeat(value.length() - visible) + value.substring(value.length() - visible);
    }
}

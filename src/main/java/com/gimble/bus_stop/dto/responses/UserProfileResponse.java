package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.HouseholdRole;
import com.gimble.bus_stop.enums.Role;
import com.gimble.bus_stop.enums.SchoolRole;
import com.gimble.bus_stop.enums.StaffPermission;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
public class UserProfileResponse {

    private UUID id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private Role role;
    private boolean emailVerified;
    private String profilePictureUrl;
    private UUID parentId;          // non-null only for KID accounts
    private Set<StaffPermission> staffPermissions; // non-empty only for ADMIN accounts
    private boolean onboardingComplete; // false right after sign-up: the app shows onboarding
    private HouseholdRole householdRole;  // parents: OWNER, GUARDIAN or HELPER
    private SchoolRole schoolRole;        // school accounts: ADMIN or STAFF
    private com.gimble.bus_stop.enums.OperatorRole teamRole; // drivers in a team / company: OWNER, MANAGER or DRIVER
    private String teamName;
    private boolean passwordChangeRequired; // staff with a temporary password: the app asks for a new one first
    private LocalDateTime createdAt;

    public static UserProfileResponse from(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole())
                .emailVerified(user.isEmailVerified())
                .profilePictureUrl(user.getProfilePictureUrl())
                .parentId(user.getParent() != null ? user.getParent().getId() : null)
                .staffPermissions(Set.copyOf(user.getStaffPermissions()))
                .onboardingComplete(user.isOnboarded())
                .passwordChangeRequired(user.isPasswordChangeRequired())
                .createdAt(user.getCreatedAt())
                .build();
    }
}

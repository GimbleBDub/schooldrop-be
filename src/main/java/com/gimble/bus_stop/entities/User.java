package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.Role;
import com.gimble.bus_stop.enums.StaffPermission;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    // Filled in during onboarding, after sign-up
    private String firstName;

    private String lastName;

    @Column(unique = true)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // For KID accounts — the parent who owns this account
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private User parent;

    // Admin panel permissions — only used for Role.ADMIN
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "staff_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false)
    private Set<StaffPermission> staffPermissions = new HashSet<>();

    // Profile picture URL (stored in cloud storage, URL saved here)
    private String profilePictureUrl;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Builder.Default
    @Column(nullable = false)
    private boolean accountNonLocked = true;

    // Email verification
    @Builder.Default
    private boolean emailVerified = false;
    private String emailVerificationToken;

    // Set once the account's onboarding details are in; role features stay locked until then
    private Instant onboardedAt;

    // Which pushes the user wants. The inbox always gets everything; safety and security always push.
    @Builder.Default
    @Column(nullable = false)
    private boolean notifyTrips = true;

    @Builder.Default
    @Column(nullable = false)
    private boolean notifyBookings = true;

    @Builder.Default
    @Column(nullable = false)
    private boolean notifyPayments = true;

    @Builder.Default
    @Column(nullable = false)
    private boolean notifyNews = true;

    // Access tokens issued before this are rejected (password / email change, "sign out everywhere")
    private Instant sessionsValidFrom;

    private Instant passwordChangedAt;

    // Staff created or reset with a temporary password must choose their own before using the back office
    @Builder.Default
    @Column(nullable = false)
    private boolean passwordChangeRequired = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // --- UserDetails implementation ---

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        staffPermissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p.authority())));
        return authorities;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public String getFullName() {
        if (firstName == null && lastName == null) {
            return email;
        }
        return ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
    }

    public boolean isOnboarded() {
        return onboardedAt != null;
    }
}

package com.gimble.bus_stop.dto.account;

import com.gimble.bus_stop.dto.responses.UserProfileResponse;
import com.gimble.bus_stop.entities.Token;
import com.gimble.bus_stop.entities.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Requests and responses for Profile & settings (/v1/account). */
public final class AccountDtos {

    private AccountDtos() {
    }

    public record AccountResponse(
            UserProfileResponse profile,
            NotificationSettings notifications,
            Instant passwordChangedAt,
            // Verified drivers' names must match their Omang, so only support can change them
            boolean nameLocked,
            String nameLockedReason,
            // Families' payers and drivers have money to look at; school staff and kids don't
            boolean hasBilling
    ) {
    }

    public record NotificationSettings(boolean trips, boolean bookings, boolean payments, boolean news) {
        public static NotificationSettings of(User u) {
            return new NotificationSettings(u.isNotifyTrips(), u.isNotifyBookings(), u.isNotifyPayments(), u.isNotifyNews());
        }
    }

    public record UpdateProfileRequest(@NotBlank @Size(max = 60) String firstName,
                                       @NotBlank @Size(max = 60) String lastName) {
    }

    /** Email is the sign-in name, so the password is required and the session is re-issued. */
    public record ChangeEmailRequest(@NotBlank @Email @Size(max = 255) String newEmail,
                                     @NotBlank String password,
                                     @Size(max = 100) String deviceId,
                                     @Size(max = 100) String deviceName) {
    }

    public record ChangePhoneRequest(@NotBlank @Pattern(regexp = "^\\+?[0-9 ]{7,18}$", message = "must be a valid phone number") String newPhone,
                                     @NotBlank String password) {
    }

    /** Signs every other device out and returns fresh tokens for this one. */
    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @com.gimble.bus_stop.dto.validation.StrongPassword String newPassword,
                                        @Size(max = 100) String deviceId,
                                        @Size(max = 100) String deviceName) {
    }

    public record DeviceRequest(@NotBlank @Size(max = 100) String deviceId) {
    }

    public record SessionResponse(UUID id, String deviceName, Instant signedInAt, Instant lastActiveAt, boolean current) {
        public static SessionResponse of(Token t, String currentDeviceId) {
            return new SessionResponse(t.getId(), t.getDeviceName() != null ? t.getDeviceName() : "Earlier sign-in (device not named)",
                    t.getSignedInAt(), t.getCreatedAt(), t.getDeviceId().equals(currentDeviceId));
        }
    }
}

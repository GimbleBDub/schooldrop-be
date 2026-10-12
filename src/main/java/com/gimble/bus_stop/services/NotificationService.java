package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.responses.NotificationResponse;
import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.entities.DeviceToken;
import com.gimble.bus_stop.entities.Notification;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.Role;
import com.gimble.bus_stop.enums.StaffPermission;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.notifications.PushSender;
import com.gimble.bus_stop.repositories.DeviceTokenRepository;
import com.gimble.bus_stop.repositories.NotificationRepository;
import com.gimble.bus_stop.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;
    private final PushSender pushSender;

    /** Saves an inbox entry and pushes it to the user's devices once the current transaction commits. */
    @Transactional
    public void notify(User user, String type, String title, String body, UUID referenceId) {
        notificationRepository.save(Notification.builder()
                .user(user)
                .type(type)
                .title(title)
                .body(body)
                .referenceId(referenceId)
                .build());

        if (!wantsPush(user, type)) {
            return;
        }
        UUID userId = user.getId();
        Map<String, String> data = referenceId != null
                ? Map.of("type", type, "referenceId", referenceId.toString())
                : Map.of("type", type);
        afterCommit(() -> {
            try {
                pushSender.send(deviceTokenRepository.findTokensByUserId(userId), title, body, data);
            } catch (Exception e) {
                // A failed push must never fail the business action; the inbox entry is already saved
                log.warn("Push to user {} failed: {}", userId, e.getMessage());
            }
        });
    }

    /** What a notification type is about, for the user's push settings. */
    public enum Category { TRIPS, BOOKINGS, PAYMENTS, NEWS, ALWAYS }

    public static Category categoryOf(String type) {
        return switch (type) {
            case "TRIP", "AUTHORISED_PICKUP", "ADHOC", "ADHOC_REQUEST" -> Category.TRIPS;
            case "BOOKING", "ENROLLMENT" -> Category.BOOKINGS;
            case "PAYMENT", "PAYOUT", "SUBSCRIPTION", "ADVERT", "INVOICE" -> Category.PAYMENTS;
            case "NEWS" -> Category.NEWS;
            // Safety (SOS, incidents, disputes), account security, verification and family/school membership
            default -> Category.ALWAYS;
        };
    }

    private static boolean wantsPush(User user, String type) {
        return switch (categoryOf(type)) {
            case TRIPS -> user.isNotifyTrips();
            case BOOKINGS -> user.isNotifyBookings();
            case PAYMENTS -> user.isNotifyPayments();
            case NEWS -> user.isNotifyNews();
            case ALWAYS -> true;
        };
    }

    /** Notifies every staff member holding the permission (and all super admins). */
    @Transactional
    public void notifyStaff(StaffPermission permission, String type, String title, String body, UUID referenceId) {
        userRepository.findByRole(Role.ADMIN).stream()
                .filter(User::isEnabled)
                .filter(u -> u.getStaffPermissions().contains(permission)
                        || u.getStaffPermissions().contains(StaffPermission.SUPER_ADMIN))
                .forEach(u -> notify(u, type, title, body, referenceId));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(User user, Pageable pageable) {
        return PageResponse.from(notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), pageable)
                .map(NotificationResponse::from));
    }

    @Transactional(readOnly = true)
    public long unreadCount(User user) {
        return notificationRepository.countByUserIdAndReadAtIsNull(user.getId());
    }

    @Transactional
    public void markRead(User user, UUID notificationId) {
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
        }
    }

    @Transactional
    public void markAllRead(User user) {
        notificationRepository.markAllRead(user.getId(), Instant.now());
    }

    @Transactional
    public void registerDevice(User user, String token, String platform) {
        // A token moves to whoever is signed in on that device now
        DeviceToken device = deviceTokenRepository.findByToken(token)
                .orElseGet(() -> DeviceToken.builder().token(token).build());
        device.setUser(user);
        device.setPlatform(platform);
        deviceTokenRepository.save(device);
    }

    @Transactional
    public void unregisterDevice(User user, String token) {
        deviceTokenRepository.deleteByTokenAndUserId(token, user.getId());
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Thread.startVirtualThread(action);
                }
            });
        } else {
            Thread.startVirtualThread(action);
        }
    }

}

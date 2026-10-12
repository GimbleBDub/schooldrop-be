package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.responses.NotificationResponse;
import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.services.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    public record DeviceRequest(@NotBlank @Size(max = 512) String token,
                                @NotBlank @Pattern(regexp = "ANDROID|IOS|WEB") String platform) {
    }

    @GetMapping
    public PageResponse<NotificationResponse> list(@AuthenticationPrincipal User user,
                                                   @PageableDefault(size = 30) Pageable pageable) {
        return notificationService.list(user, pageable);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal User user) {
        return Map.of("count", notificationService.unreadCount(user));
    }

    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal User user, @PathVariable UUID notificationId) {
        notificationService.markRead(user, notificationId);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal User user) {
        notificationService.markAllRead(user);
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerDevice(@AuthenticationPrincipal User user, @Valid @RequestBody DeviceRequest request) {
        notificationService.registerDevice(user, request.token(), request.platform());
    }

    @DeleteMapping("/devices/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregisterDevice(@AuthenticationPrincipal User user, @PathVariable String token) {
        notificationService.unregisterDevice(user, token);
    }
}

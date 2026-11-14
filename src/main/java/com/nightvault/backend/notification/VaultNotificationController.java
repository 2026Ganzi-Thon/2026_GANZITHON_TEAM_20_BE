package com.nightvault.backend.notification;

import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
class VaultNotificationController {
    private final VaultNotifications notifications;

    VaultNotificationController(VaultNotifications notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/settings")
    ResponseEntity<VaultNotifications.Settings> settings(@AuthenticationPrincipal UUID guest) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.settings(guest));
    }

    @PutMapping("/subscription")
    ResponseEntity<Void> subscription(@AuthenticationPrincipal UUID guest, @RequestBody PushTransport.Subscription body) {
        notifications.subscribe(guest, body);
        return noContent();
    }

    @PutMapping("/publication")
    ResponseEntity<Void> publication(@AuthenticationPrincipal UUID guest, @RequestBody Consent body) {
        if (body.enabled() == null) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        notifications.consent(guest, body.enabled());
        return noContent();
    }

    @DeleteMapping("/settings")
    ResponseEntity<Void> disable(@AuthenticationPrincipal UUID guest) {
        notifications.disable(guest);
        return noContent();
    }

    @GetMapping("/reminder")
    ResponseEntity<VaultNotifications.Reminder> reminder(@AuthenticationPrincipal UUID guest) {
        var result = notifications.reminder(guest);
        return result == null ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
            : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PutMapping("/reminder")
    ResponseEntity<VaultNotifications.Reminder> reminder(@AuthenticationPrincipal UUID guest, @RequestBody Schedule body) {
        if (body.worryId() == null || body.minutes() == null) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(notifications.schedule(guest, body.worryId(), body.endpoint(), body.minutes()));
    }

    @DeleteMapping("/reminder")
    ResponseEntity<Void> cancel(@AuthenticationPrincipal UUID guest) {
        notifications.cancel(guest);
        return noContent();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    record Consent(Boolean enabled) {}
    record Schedule(UUID worryId, String endpoint, Integer minutes) {}
}

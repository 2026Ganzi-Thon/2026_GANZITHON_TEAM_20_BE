package com.nightvault.backend.activity;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ActiveActivityController {
    private final Activities activities;

    ActiveActivityController(Activities activities) {
        this.activities = activities;
    }

    @GetMapping("/api/v1/activities/current")
    ResponseEntity<Activities.View> current(@AuthenticationPrincipal UUID guest) {
        var result = activities.latestActive(guest);
        return result == null ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
            : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}

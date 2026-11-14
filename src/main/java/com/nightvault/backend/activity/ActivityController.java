package com.nightvault.backend.activity;

import java.util.List;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.ApiUuid;
import com.nightvault.backend.api.BusinessException;
import com.nightvault.backend.worry.HelpType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/worries/{worryId}/activities")
class ActivityController {
    private final Activities activities;

    ActivityController(Activities activities) {
        this.activities = activities;
    }

    @GetMapping
    ResponseEntity<List<Activities.View>> list(@AuthenticationPrincipal UUID guest, @PathVariable UUID worryId) {
        return ok(activities.list(guest, worryId));
    }

    @PostMapping
    ResponseEntity<Activities.View> start(@AuthenticationPrincipal UUID guest, @PathVariable UUID worryId,
        @RequestHeader("Idempotency-Key") String key, @RequestBody Start body) {
        if (body.helpType() == null) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        return ok(activities.start(guest, worryId, ApiUuid.parse(key), body.helpType(), Boolean.TRUE.equals(body.replaceActive())));
    }

    @PostMapping("/{activityId}/commands")
    ResponseEntity<Activities.View> command(@AuthenticationPrincipal UUID guest, @PathVariable UUID worryId,
        @PathVariable UUID activityId, @RequestBody Update body) {
        if (body.command() == null) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        return ok(activities.action(guest, worryId, activityId, body.command(), body.step(), body.checked()));
    }

    @PutMapping("/{activityId}/feedback")
    ResponseEntity<Activities.View> feedback(@AuthenticationPrincipal UUID guest, @PathVariable UUID worryId,
        @PathVariable UUID activityId, @RequestBody Feedback body) {
        if (body.answer() == null) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        return ok(activities.feedback(guest, worryId, activityId, body.answer()));
    }

    private static <T> ResponseEntity<T> ok(T result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    record Start(HelpType helpType, Boolean replaceActive) {}
    record Update(Activities.Command command, Integer step, Boolean checked) {}
    record Feedback(Activities.Feedback answer) {}
}

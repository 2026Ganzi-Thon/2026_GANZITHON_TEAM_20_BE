package com.nightvault.backend.activity;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

import com.nightvault.backend.activity.ActivityRow.HelpType;
import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.ApiUuid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 활동 API (BE2 설계 §4 #3~#9). 경로 기준은 {@code /api/v1}. */
@RestController
class ActivityController {
    private static final String ACTIVITIES = "/api/v1/activities";
    private static final Set<String> HELP_TYPES = Set.of("ACTION", "REST", "EMPATHY");
    private static final Set<String> STEP_NUMBERS = Set.of("1", "2", "3");
    private static final Set<String> FEEDBACK_ANSWERS = Set.of("HELPFUL", "UNSURE", "NOT_HELPFUL");

    private final ActivityService activities;

    ActivityController(ActivityService activities) {
        this.activities = activities;
    }

    @PostMapping(path = "/api/v1/worries/{worryId}/activities", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> start(
        @AuthenticationPrincipal UUID guestId,
        @PathVariable("worryId") String worryId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestBody JsonNode body
    ) {
        var replace = body.has("replaceOpen") ? body.get("replaceOpen") : body.get("replaceActive");
        if (!body.isObject() || !body.has("helpType") || body.size() != (replace == null ? 1 : 2)
            || !body.get("helpType").isString() || !HELP_TYPES.contains(body.get("helpType").asString())
            || (replace != null && !replace.isBoolean()) || (key == null && body.has("replaceActive"))) {
            throw new ActivityException(ApiError.INVALID_INPUT);
        }
        HelpType helpType = HelpType.valueOf(body.get("helpType").asString());
        boolean replaceOpen = replace != null && replace.asBoolean();

        var result = activities.start(guestId, ApiUuid.parse(worryId), helpType, replaceOpen,
            key == null ? null : ApiUuid.parse(key));
        if (key != null && !body.has("replaceOpen")) {
            return LegacyActivityController.ok(result.view());
        }
        var builder = result.created()
            ? ResponseEntity.created(URI.create(ACTIVITIES + "/" + result.view().activityId()))
            : ResponseEntity.ok();
        return builder.cacheControl(CacheControl.noStore()).body(result.view());
    }

    @GetMapping(ACTIVITIES + "/current")
    ResponseEntity<CurrentActivity> current(@AuthenticationPrincipal UUID guestId) {
        return activities.current(guestId)
            .map(current -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(current))
            .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    @GetMapping(ACTIVITIES + "/{activityId}")
    ResponseEntity<ActivityView> get(@AuthenticationPrincipal UUID guestId, @PathVariable("activityId") String activityId) {
        return ok(activities.get(guestId, ApiUuid.parse(activityId)));
    }

    @PutMapping(path = ACTIVITIES + "/{activityId}/steps/{stepNo}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ActivityView> step(
        @AuthenticationPrincipal UUID guestId,
        @PathVariable("activityId") String activityId,
        @PathVariable("stepNo") String stepNo,
        @RequestBody JsonNode body
    ) {
        if (!STEP_NUMBERS.contains(stepNo) || !body.isObject() || body.size() != 1
            || !body.has("done") || !body.get("done").isBoolean()) {
            throw new ActivityException(ApiError.INVALID_INPUT);
        }
        return ok(activities.setStep(guestId, ApiUuid.parse(activityId), Integer.parseInt(stepNo), body.get("done").asBoolean()));
    }

    @PostMapping(ACTIVITIES + "/{activityId}/pause")
    ResponseEntity<ActivityView> pause(@AuthenticationPrincipal UUID guestId, @PathVariable("activityId") String activityId) {
        return ok(activities.pause(guestId, ApiUuid.parse(activityId)));
    }

    @PostMapping(ACTIVITIES + "/{activityId}/resume")
    ResponseEntity<ActivityView> resume(@AuthenticationPrincipal UUID guestId, @PathVariable("activityId") String activityId) {
        return ok(activities.resume(guestId, ApiUuid.parse(activityId)));
    }

    @PostMapping(ACTIVITIES + "/{activityId}/stop")
    ResponseEntity<ActivityView> stop(@AuthenticationPrincipal UUID guestId, @PathVariable("activityId") String activityId) {
        return ok(activities.stop(guestId, ApiUuid.parse(activityId)));
    }

    @PostMapping(ACTIVITIES + "/{activityId}/complete")
    ResponseEntity<ActivityView> complete(@AuthenticationPrincipal UUID guestId, @PathVariable("activityId") String activityId) {
        return ok(activities.complete(guestId, ApiUuid.parse(activityId)));
    }

    @PutMapping(path = ACTIVITIES + "/{activityId}/feedback", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ActivityFeedback.Saved> feedback(
        @AuthenticationPrincipal UUID guestId,
        @PathVariable("activityId") String activityId,
        @RequestBody JsonNode body
    ) {
        if (!body.isObject() || body.size() != 1 || !body.has("answer") || !body.get("answer").isString()
            || !FEEDBACK_ANSWERS.contains(body.get("answer").asString())) {
            throw new ActivityException(ApiError.INVALID_INPUT);
        }
        var saved = activities.saveFeedback(guestId, ApiUuid.parse(activityId),
            ActivityFeedback.Answer.valueOf(body.get("answer").asString()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(saved);
    }

    @ExceptionHandler(ActivityException.class)
    ResponseEntity<ApiError.Body> activityError(ActivityException exception) {
        return exception.error().response();
    }

    private static ResponseEntity<ActivityView> ok(ActivityView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

}

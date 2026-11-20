package com.nightvault.backend.activity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nightvault.backend.activity.ActivityService.LegacyCommand;
import com.nightvault.backend.api.ApiError;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/worries/{worryId}/activities")
class LegacyActivityController {
    private final ActivityService activities;

    LegacyActivityController(ActivityService activities) {
        this.activities = activities;
    }

    @GetMapping
    ResponseEntity<List<View>> history(@AuthenticationPrincipal UUID guestId, @PathVariable UUID worryId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(activities.history(guestId, worryId).stream().map(View::of).toList());
    }

    @PostMapping("/{activityId}/commands")
    ResponseEntity<View> command(@AuthenticationPrincipal UUID guestId, @PathVariable UUID worryId,
        @PathVariable UUID activityId, @RequestBody Update request) {
        if (request.command() == null
            || (request.command() == LegacyCommand.CHECK
                ? request.step() == null || request.step() < 1 || request.step() > 3 || request.checked() == null
                : request.step() != null || request.checked() != null)) {
            throw new ActivityException(ApiError.INVALID_INPUT);
        }
        return ok(activities.legacyCommand(guestId, worryId, activityId, request.command(), request.step(), request.checked()));
    }

    @PutMapping("/{activityId}/feedback")
    ResponseEntity<View> feedback(@AuthenticationPrincipal UUID guestId, @PathVariable UUID worryId,
        @PathVariable UUID activityId, @RequestBody Feedback request) {
        if (request.answer() == null) {
            throw new ActivityException(ApiError.INVALID_INPUT);
        }
        return ok(activities.legacyFeedback(guestId, worryId, activityId, request.answer()));
    }

    @ExceptionHandler(ActivityException.class)
    ResponseEntity<ApiError.Body> activityError(ActivityException exception) {
        ApiError error = switch (exception.error()) {
            case INVALID_ACTIVITY_STATE, ACTIVITY_ALREADY_ENDED, ACTION_STEPS_INCOMPLETE,
                REST_NOT_FINISHED, REST_TIME_OVER, ACTIVITY_NOT_COMPLETED -> ApiError.ACTIVITY_CONFLICT;
            default -> exception.error();
        };
        return error.response();
    }

    static ResponseEntity<View> ok(ActivityView activity) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(View.of(activity));
    }

    record Update(LegacyCommand command, Integer step, Boolean checked) {}

    record Feedback(ActivityFeedback.Answer answer) {}

    record View(UUID activityId, UUID worryId, ActivityRow.HelpType helpType, ActivityRow.ActivityState state,
        List<Integer> checkedSteps, Integer remainingMillis, Instant endsAt, String feedback) {
        static View of(ActivityView activity) {
            var action = activity.action();
            var rest = activity.rest();
            Integer remainingMillis = rest == null ? null : Integer.valueOf(
                rest.remainingSeconds() == null ? 0 : rest.remainingSeconds() * 1_000);
            return new View(activity.activityId(), activity.worryId(), activity.helpType(), activity.state(),
                action == null ? List.of() : action.steps().stream().filter(ActivityView.Step::done)
                    .map(ActivityView.Step::stepNo).toList(),
                remainingMillis, rest == null ? null : rest.endsAt(),
                activity.feedback() == null ? null : activity.feedback().answer().name());
        }
    }
}

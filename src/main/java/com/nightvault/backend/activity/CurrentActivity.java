package com.nightvault.backend.activity;

import java.time.Instant;
import java.util.UUID;

import com.nightvault.backend.activity.ActivityRow.ActivityState;
import com.nightvault.backend.activity.ActivityRow.HelpType;

/**
 * 홈 이어하기 카드(HOME-03). 원문·요약은 넣지 않는다.
 *
 * @param progress ACTION은 체크 수, REST는 남은 시간. EMPATHY는 null
 */
record CurrentActivity(
    UUID activityId, UUID worryId, HelpType helpType, ActivityState state, Instant serverTime, Progress progress
) {
    static CurrentActivity of(ActivityRow row, Instant now) {
        Progress progress = switch (row.helpType()) {
            case ACTION -> ActionProgress.of(row);
            case REST -> new RestProgress(row.remainingSeconds(now),
                row.state() == ActivityState.IN_PROGRESS && row.remainingSeconds(now) == 0);
            case EMPATHY -> null;
        };
        return new CurrentActivity(row.id(), row.worryId(), row.helpType(), row.state(), now, progress);
    }

    sealed interface Progress permits ActionProgress, RestProgress {}

    record ActionProgress(int doneCount, int totalSteps) implements Progress {
        static ActionProgress of(ActivityRow row) {
            int done = 0;
            for (int stepNo = 1; stepNo <= 3; stepNo++) {
                done += row.step(stepNo) ? 1 : 0;
            }
            return new ActionProgress(done, 3);
        }
    }

    record RestProgress(int remainingSeconds, boolean timeOver) implements Progress {}
}

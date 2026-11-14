package com.nightvault.backend.activity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.nightvault.backend.activity.ActivityRow.ActivityState;
import com.nightvault.backend.activity.ActivityRow.HelpType;

/**
 * 활동 API 공통 응답. 프런트는 기기 시각이 아니라 {@code serverTime}·{@code rest.remainingSeconds}로 카운트다운을 다시 시작한다.
 *
 * <p>단계 문구·휴식 안내·공감 메시지(ai_contents)는 BE1 암호화 모듈을 공유한 뒤 추가한다.</p>
 *
 * @param stoppedActivityId 도움 변경(M-05)으로 이번 요청에서 중단된 활동. 그 외 null
 * @param nextUnviewedExists 완료 응답에서만 채운다(RESULT-03). 그 외 null
 */
record ActivityView(
    UUID activityId, UUID worryId, HelpType helpType, ActivityState state,
    Instant startedAt, Instant endedAt, int version, Instant serverTime,
    ActionProgress action, RestTimer rest,
    UUID stoppedActivityId, Boolean nextUnviewedExists
) {
    static ActivityView of(ActivityRow row, Instant now) {
        return new ActivityView(row.id(), row.worryId(), row.helpType(), row.state(),
            row.startedAt(), row.endedAt(), row.version(), now,
            row.helpType() == HelpType.ACTION ? ActionProgress.of(row) : null,
            row.helpType() == HelpType.REST ? RestTimer.of(row, now) : null,
            null, null);
    }

    ActivityView withStoppedActivityId(UUID value) {
        return new ActivityView(activityId, worryId, helpType, state, startedAt, endedAt, version, serverTime,
            action, rest, value, nextUnviewedExists);
    }

    ActivityView withNextUnviewedExists(boolean value) {
        return new ActivityView(activityId, worryId, helpType, state, startedAt, endedAt, version, serverTime,
            action, rest, stoppedActivityId, value);
    }

    record ActionProgress(List<Step> steps, int doneCount, boolean canComplete) {
        static ActionProgress of(ActivityRow row) {
            List<Step> steps = IntStream.rangeClosed(1, 3).mapToObj(no -> new Step(no, row.step(no))).toList();
            int done = (int) steps.stream().filter(Step::done).count();
            return new ActionProgress(steps, done, row.state() == ActivityState.IN_PROGRESS && done == steps.size());
        }
    }

    record Step(int stepNo, boolean done) {}

    /**
     * @param endsAt 실행 중일 때만 값이 있다
     * @param remainingSeconds 실행 중·일시정지일 때만 값이 있다. 0 미만은 없다
     * @param timeOver 실행 중이고 남은 시간이 0이면 true. 서버는 이때도 상태를 바꾸지 않는다(자동 완료 아님)
     */
    record RestTimer(int durationSeconds, Instant endsAt, Integer remainingSeconds, boolean timeOver) {
        static RestTimer of(ActivityRow row, Instant now) {
            Integer remaining = row.remainingSeconds(now);
            boolean timeOver = row.state() == ActivityState.IN_PROGRESS && remaining == 0;
            return new RestTimer(row.restDurationSeconds(), row.restEndsAt(), remaining, timeOver);
        }
    }
}

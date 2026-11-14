package com.nightvault.backend.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code activities} 한 행. 전이 메서드는 새 행 값을 돌려주고, 저장은 {@link ActivityStore#update}가 한다.
 * 상태가 바뀌는 전이는 {@code state_changed_at}을, 값이 바뀌는 모든 쓰기는 {@code version}을 올린다.
 */
record ActivityRow(
    UUID id, UUID guestId, UUID worryId, HelpType helpType, ActivityState state,
    Instant startedAt, Instant lastStartedAt, Instant stateChangedAt, Instant endedAt,
    Boolean step1Done, Boolean step2Done, Boolean step3Done,
    Integer restDurationSeconds, Instant restEndsAt, Integer restRemainingSeconds,
    int version
) {
    enum HelpType { ACTION, REST, EMPATHY }

    enum ActivityState {
        IN_PROGRESS, PAUSED, STOPPED, COMPLETED;

        boolean ended() {
            return this == STOPPED || this == COMPLETED;
        }
    }

    // ---- 조회 ----

    boolean step(int stepNo) {
        return switch (stepNo) {
            case 1 -> step1Done;
            case 2 -> step2Done;
            case 3 -> step3Done;
            default -> throw new IllegalArgumentException("stepNo");
        };
    }

    boolean allStepsDone() {
        return step1Done && step2Done && step3Done;
    }

    /** REST-03: 실행 중이면 {@code max(0, ceil(ends_at - now))}, 일시정지면 저장된 남은 초. 그 외 null. */
    Integer remainingSeconds(Instant now) {
        if (helpType != HelpType.REST) {
            return null;
        }
        return switch (state) {
            case IN_PROGRESS -> (int) Math.max(0, Math.ceilDiv(Duration.between(now, restEndsAt).toNanos(), 1_000_000_000L));
            case PAUSED -> restRemainingSeconds;
            default -> null;
        };
    }

    // ---- 전이 ----

    /** 중단. 액션 체크는 유지하고 휴식 타이머 값은 버린다. */
    ActivityRow stop(Instant now) {
        return new ActivityRow(id, guestId, worryId, helpType, ActivityState.STOPPED,
            startedAt, lastStartedAt, now, now, step1Done, step2Done, step3Done,
            restDurationSeconds, null, null, version + 1);
    }

    /** 중단된 액션 재개. {@code started_at}·체크는 그대로, {@code last_started_at}만 갱신한다. */
    ActivityRow resumeAction(Instant now) {
        return new ActivityRow(id, guestId, worryId, helpType, ActivityState.IN_PROGRESS,
            startedAt, now, now, null, step1Done, step2Done, step3Done,
            restDurationSeconds, null, null, version + 1);
    }

    ActivityRow withStep(int stepNo, boolean done) {
        return new ActivityRow(id, guestId, worryId, helpType, state,
            startedAt, lastStartedAt, stateChangedAt, endedAt,
            stepNo == 1 ? done : step1Done, stepNo == 2 ? done : step2Done, stepNo == 3 ? done : step3Done,
            restDurationSeconds, restEndsAt, restRemainingSeconds, version + 1);
    }

    ActivityRow pauseRest(Instant now, int remaining) {
        return new ActivityRow(id, guestId, worryId, helpType, ActivityState.PAUSED,
            startedAt, lastStartedAt, now, null, null, null, null,
            restDurationSeconds, null, Math.min(remaining, restDurationSeconds), version + 1);
    }

    ActivityRow resumeRest(Instant now) {
        return new ActivityRow(id, guestId, worryId, helpType, ActivityState.IN_PROGRESS,
            startedAt, lastStartedAt, now, null, null, null, null,
            restDurationSeconds, now.plusSeconds(restRemainingSeconds), null, version + 1);
    }
}

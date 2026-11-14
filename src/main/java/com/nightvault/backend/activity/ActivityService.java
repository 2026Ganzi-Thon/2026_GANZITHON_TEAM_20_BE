package com.nightvault.backend.activity;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import com.nightvault.backend.activity.ActivityRow.ActivityState;
import com.nightvault.backend.activity.ActivityRow.HelpType;
import com.nightvault.backend.api.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 활동 상태 전이(BE2 설계 §2). 쓰기는 모두 게스트 → 걱정({@link WorryAccess#lockForWrite}) → 활동 순서로 잠근다.
 * 같은 활동에 대한 동시 요청은 활동 행 {@code FOR UPDATE}에서 줄을 서고, 먼저 커밋한 요청이 이긴다.
 */
@Service
class ActivityService {
    private static final int MAX_START_ATTEMPTS = 3;

    private final ActivityStore store;
    private final WorryAccess worries;
    private final Clock clock;
    private final int restDurationSeconds;

    ActivityService(
        ActivityStore store,
        WorryAccess worries,
        Clock clock,
        @Value("${nightvault.activity.rest-duration-seconds:180}") int restDurationSeconds
    ) {
        this.store = store;
        this.worries = worries;
        this.clock = clock;
        this.restDurationSeconds = restDurationSeconds;
    }

    record StartResult(boolean created, ActivityView view) {}

    /**
     * 도움 선택·활동 시작·도움 변경(M-05, ACT-01, ACT-03 재개, REST-01, EMP-02).
     * <ol>
     *   <li>같은 유형의 열린 활동이 있으면 그대로 반환(재진입, {@code started_at} 유지)</li>
     *   <li>다른 유형이 열려 있으면 {@code replaceOpen}일 때만 그 활동을 중단하고 진행, 아니면 409 {@code OPEN_ACTIVITY_EXISTS}</li>
     *   <li>ACTION이고 중단된 액션이 있으면 같은 행을 재개(체크 유지)</li>
     *   <li>그 외에는 새 행. 동시 요청이 먼저 만들었으면 다시 읽어 1번부터 판단한다</li>
     * </ol>
     */
    @Transactional
    StartResult start(UUID guestId, UUID worryId, HelpType helpType, boolean replaceOpen) {
        Instant now = now();
        worries.lockForWrite(guestId, worryId, now);

        UUID stoppedId = null;
        for (int attempt = 0; attempt < MAX_START_ATTEMPTS; attempt++) {
            Optional<ActivityRow> open = store.lockOpen(worryId);
            if (open.isPresent()) {
                ActivityRow current = open.get();
                if (current.helpType() == helpType) {
                    return new StartResult(false, ActivityView.of(current, now).withStoppedActivityId(stoppedId));
                }
                if (!replaceOpen) {
                    throw new ActivityException(ApiError.OPEN_ACTIVITY_EXISTS);
                }
                store.update(current.stop(now));
                stoppedId = current.id();
            }

            if (helpType == HelpType.ACTION) {
                Optional<ActivityRow> stopped = store.lockStoppedAction(worryId);
                if (stopped.isPresent()) {
                    ActivityRow resumed = stopped.get().resumeAction(now);
                    store.update(resumed);
                    return new StartResult(false, ActivityView.of(resumed, now).withStoppedActivityId(stoppedId));
                }
            }

            Optional<ActivityRow> inserted = store.insert(guestId, worryId, helpType, now, restDurationSeconds);
            if (inserted.isPresent()) {
                return new StartResult(true, ActivityView.of(inserted.get(), now).withStoppedActivityId(stoppedId));
            }
        }
        throw new ActivityException(ApiError.TEMPORARILY_UNAVAILABLE);
    }

    /** 상태 복원(ACT-01, REST-03, HOME-05). 잠금 없이 읽는다. */
    @Transactional(readOnly = true)
    ActivityView get(UUID guestId, UUID activityId) {
        Instant now = now();
        UUID worryId = store.findWorryId(guestId, activityId).orElseThrow(ActivityService::notFound);
        worries.check(guestId, worryId, now);
        return withFeedback(ActivityView.of(store.find(guestId, activityId).orElseThrow(ActivityService::notFound), now));
    }

    /** 액션 체크 저장·해제(ACT-02). 순서 자유, 같은 값 재전송은 멱등. 진행 중 액션만. */
    @Transactional
    ActivityView setStep(UUID guestId, UUID activityId, int stepNo, boolean done) {
        Instant now = now();
        ActivityRow row = lockForWrite(guestId, activityId, now);
        requireType(row, HelpType.ACTION);
        requireNotEnded(row);
        if (row.step(stepNo) == done) {
            return ActivityView.of(row, now);
        }
        ActivityRow updated = row.withStep(stepNo, done);
        store.update(updated);
        return ActivityView.of(updated, now);
    }

    /** 휴식 일시정지(REST-02). 남은 시간을 저장하고 종료 예정 시각을 비운다. 0초 이후면 409 {@code REST_TIME_OVER}. */
    @Transactional
    ActivityView pause(UUID guestId, UUID activityId) {
        Instant now = now();
        ActivityRow row = lockForWrite(guestId, activityId, now);
        requireType(row, HelpType.REST);
        requireNotEnded(row);
        if (row.state() == ActivityState.PAUSED) {
            return ActivityView.of(row, now);
        }
        int remaining = row.remainingSeconds(now);
        if (remaining <= 0) {
            throw new ActivityException(ApiError.REST_TIME_OVER);
        }
        ActivityRow updated = row.pauseRest(now, remaining);
        store.update(updated);
        return ActivityView.of(updated, now);
    }

    /** 휴식 재개(REST-02). 종료 예정 시각 = 서버 현재 + 남은 시간. */
    @Transactional
    ActivityView resume(UUID guestId, UUID activityId) {
        Instant now = now();
        ActivityRow row = lockForWrite(guestId, activityId, now);
        requireType(row, HelpType.REST);
        requireNotEnded(row);
        if (row.state() == ActivityState.IN_PROGRESS) {
            return ActivityView.of(row, now);
        }
        ActivityRow updated = row.resumeRest(now);
        store.update(updated);
        return ActivityView.of(updated, now);
    }

    /** 중단(ACT-03 나중에 이어하기, REST-04 그만두기). 액션 체크는 유지하고 휴식 남은 시간은 버린다. */
    @Transactional
    ActivityView stop(UUID guestId, UUID activityId) {
        Instant now = now();
        ActivityRow row = lockForWrite(guestId, activityId, now);
        if (row.state() == ActivityState.STOPPED) {
            return ActivityView.of(row, now);
        }
        if (row.state() == ActivityState.COMPLETED) {
            throw new ActivityException(ApiError.ACTIVITY_ALREADY_ENDED);
        }
        ActivityRow updated = row.stop(now);
        store.update(updated);
        return ActivityView.of(updated, now);
    }

    /**
     * 사용자의 명시적 완료(ACT-04, REST-04, EMP-02). 액션은 3단계 모두 체크, 휴식은 시간 종료 후에만 가능하다.
     * 응답에 다음 미확인 걱정 존재 여부(RESULT-03)를 넣는다.
     */
    @Transactional
    ActivityView complete(UUID guestId, UUID activityId) {
        Instant now = now();
        ActivityRow row = lockForWrite(guestId, activityId, now);
        ActivityRow result = row;
        if (row.state() != ActivityState.COMPLETED) {
            if (row.state() == ActivityState.STOPPED) {
                throw new ActivityException(ApiError.ACTIVITY_ALREADY_ENDED);
            }
            switch (row.helpType()) {
                case ACTION -> {
                    if (!row.allStepsDone()) {
                        throw new ActivityException(ApiError.ACTION_STEPS_INCOMPLETE);
                    }
                }
                case REST -> {
                    if (row.state() == ActivityState.PAUSED || row.remainingSeconds(now) > 0) {
                        throw new ActivityException(ApiError.REST_NOT_FINISHED);
                    }
                }
                case EMPATHY -> {
                    // "읽었어요"는 조건 없이 완료한다.
                }
            }
            result = row.complete(now);
            store.update(result);
        }
        return withFeedback(ActivityView.of(result, now))
            .withNextUnviewedExists(worries.otherUnviewedExists(guestId, row.worryId(), now));
    }

    /**
     * 도움 평가 저장(RESULT-02). 완료된 활동에만 저장하고, 다시 보내면 답을 덮어쓴다(같은 답이면 그대로).
     * 활동 완료와 다른 트랜잭션이라 평가가 실패해도 완료 상태는 바뀌지 않는다.
     */
    @Transactional
    ActivityFeedback.Saved saveFeedback(UUID guestId, UUID activityId, ActivityFeedback.Answer answer) {
        Instant now = now();
        ActivityRow row = lockForWrite(guestId, activityId, now);
        if (row.state() != ActivityState.COMPLETED) {
            throw new ActivityException(ApiError.ACTIVITY_NOT_COMPLETED);
        }
        Optional<ActivityFeedback> existing = store.findFeedback(activityId);
        if (existing.isPresent() && existing.get().answer() == answer) {
            return ActivityFeedback.Saved.of(activityId, existing.get());
        }
        return ActivityFeedback.Saved.of(activityId, store.saveFeedback(activityId, answer, now));
    }

    // ---- 내부 ----

    /** 완료된 활동이면 평가를 붙인다. 미완료 활동은 평가가 있을 수 없다. */
    private ActivityView withFeedback(ActivityView view) {
        if (view.state() != ActivityState.COMPLETED) {
            return view;
        }
        return view.withFeedback(store.findFeedback(view.activityId()).orElse(null));
    }

    /** 활동 쓰기 공통: 소유 확인 → 게스트·걱정 잠금(접근 검사) → 활동 잠금. */
    private ActivityRow lockForWrite(UUID guestId, UUID activityId, Instant now) {
        UUID worryId = store.findWorryId(guestId, activityId).orElseThrow(ActivityService::notFound);
        worries.lockForWrite(guestId, worryId, now);
        return store.lock(guestId, activityId).orElseThrow(ActivityService::notFound);
    }

    private static void requireType(ActivityRow row, HelpType helpType) {
        if (row.helpType() != helpType) {
            throw new ActivityException(ApiError.INVALID_ACTIVITY_STATE);
        }
    }

    private static void requireNotEnded(ActivityRow row) {
        if (row.state().ended()) {
            throw new ActivityException(ApiError.ACTIVITY_ALREADY_ENDED);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ActivityException notFound() {
        return new ActivityException(ApiError.NOT_FOUND);
    }
}

package com.nightvault.backend.activity;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

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
        return ActivityView.of(store.find(guestId, activityId).orElseThrow(ActivityService::notFound), now);
    }

    // ---- 내부 ----

    /** 활동 쓰기 공통: 소유 확인 → 게스트·걱정 잠금(접근 검사) → 활동 잠금. */
    private ActivityRow lockForWrite(UUID guestId, UUID activityId, Instant now) {
        UUID worryId = store.findWorryId(guestId, activityId).orElseThrow(ActivityService::notFound);
        worries.lockForWrite(guestId, worryId, now);
        return store.lock(guestId, activityId).orElseThrow(ActivityService::notFound);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ActivityException notFound() {
        return new ActivityException(ApiError.NOT_FOUND);
    }
}

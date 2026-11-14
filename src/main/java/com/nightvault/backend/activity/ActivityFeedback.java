package com.nightvault.backend.activity;

import java.time.Instant;
import java.util.UUID;

/**
 * 도움 평가(RESULT-02) 한 건. 행이 없으면 미평가다(기본값 없음).
 *
 * @param answeredAt 최초 응답 시각. 답을 바꿔도 유지한다
 * @param updatedAt 마지막으로 답이 바뀐 시각
 */
record ActivityFeedback(Answer answer, Instant answeredAt, Instant updatedAt) {
    enum Answer { HELPFUL, UNSURE, NOT_HELPFUL }

    /** {@code PUT /activities/{activityId}/feedback} 응답. */
    record Saved(UUID activityId, Answer answer, Instant answeredAt, Instant updatedAt) {
        static Saved of(UUID activityId, ActivityFeedback feedback) {
            return new Saved(activityId, feedback.answer(), feedback.answeredAt(), feedback.updatedAt());
        }
    }
}

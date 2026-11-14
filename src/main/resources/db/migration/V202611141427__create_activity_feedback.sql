-- BE2 도움 평가(RESULT-02). 설계: docs/roles/BE2_design.md §1-3
-- 평가 행이 없으면 미평가다(기본값 없음). 완료된 활동에만 저장하는 조건은 테이블 간 조건이라
-- 서비스가 활동 행을 잠그고 확인한다.
CREATE TABLE activity_feedback (
    activity_id uuid PRIMARY KEY REFERENCES activities (id) ON DELETE CASCADE,
    answer text NOT NULL,
    answered_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT activity_feedback_answer_check CHECK (answer IN ('HELPFUL', 'UNSURE', 'NOT_HELPFUL')),
    CONSTRAINT activity_feedback_time_check CHECK (updated_at >= answered_at)
);

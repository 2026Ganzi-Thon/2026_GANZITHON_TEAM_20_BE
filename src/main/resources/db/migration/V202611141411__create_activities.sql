-- BE2 활동(ACT·REST·EMP·M-05). 설계: docs/roles/BE2_design.md §1-2
-- worries (id, guest_id) 복합 FK는 BE1 합의(worries UNIQUE (id, guest_id)) 후 바꾼다.
-- 그 전까지 걱정 소유자와 guest_id 일치는 서비스(WorryAccess)가 보장한다.
CREATE TABLE activities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    guest_id uuid NOT NULL REFERENCES guests (id) ON DELETE CASCADE,
    worry_id uuid NOT NULL REFERENCES worries (id) ON DELETE CASCADE,
    help_type text NOT NULL,
    state text NOT NULL,
    started_at timestamptz NOT NULL,
    last_started_at timestamptz NOT NULL,
    state_changed_at timestamptz NOT NULL,
    ended_at timestamptz,
    step1_done boolean,
    step2_done boolean,
    step3_done boolean,
    rest_duration_seconds integer,
    rest_ends_at timestamptz,
    rest_remaining_seconds integer,
    version integer NOT NULL DEFAULT 0,
    CONSTRAINT activities_help_type_check CHECK (help_type IN ('ACTION', 'REST', 'EMPATHY')),
    CONSTRAINT activities_state_check CHECK (state IN ('IN_PROGRESS', 'PAUSED', 'STOPPED', 'COMPLETED')),
    -- 일시정지는 휴식에만 있다.
    CONSTRAINT activities_pause_check CHECK (state <> 'PAUSED' OR help_type = 'REST'),
    CONSTRAINT activities_ended_check
        CHECK ((state IN ('STOPPED', 'COMPLETED')) = (ended_at IS NOT NULL)),
    CONSTRAINT activities_action_steps_check
        CHECK ((help_type = 'ACTION') = (step1_done IS NOT NULL AND step2_done IS NOT NULL AND step3_done IS NOT NULL)
           AND (help_type = 'ACTION' OR (step1_done IS NULL AND step2_done IS NULL AND step3_done IS NULL))),
    CONSTRAINT activities_action_complete_check
        CHECK (help_type <> 'ACTION' OR state <> 'COMPLETED' OR (step1_done AND step2_done AND step3_done)),
    CONSTRAINT activities_rest_duration_check
        CHECK ((help_type = 'REST') = (rest_duration_seconds IS NOT NULL)
           AND (rest_duration_seconds IS NULL OR rest_duration_seconds > 0)),
    -- 실행 중: ends_at만, 일시정지: remaining만, 그 외: 둘 다 없음
    CONSTRAINT activities_rest_timer_check CHECK (
        (help_type <> 'REST' AND rest_ends_at IS NULL AND rest_remaining_seconds IS NULL)
        OR (help_type = 'REST' AND state = 'IN_PROGRESS' AND rest_ends_at IS NOT NULL AND rest_remaining_seconds IS NULL)
        OR (help_type = 'REST' AND state = 'PAUSED' AND rest_ends_at IS NULL
            AND rest_remaining_seconds > 0 AND rest_remaining_seconds <= rest_duration_seconds)
        OR (help_type = 'REST' AND state IN ('STOPPED', 'COMPLETED')
            AND rest_ends_at IS NULL AND rest_remaining_seconds IS NULL)
    ),
    CONSTRAINT activities_version_check CHECK (version >= 0)
);

-- 같은 걱정에서 진행 중·일시정지 활동은 하나 (M-05, 중복 시작 방지)
CREATE UNIQUE INDEX activities_one_open_per_worry_idx
    ON activities (worry_id) WHERE state IN ('IN_PROGRESS', 'PAUSED');
-- 같은 걱정에서 이어할 수 있는 액션은 하나 (중단 액션은 같은 행을 재개)
CREATE UNIQUE INDEX activities_one_resumable_action_idx
    ON activities (worry_id) WHERE help_type = 'ACTION' AND state <> 'COMPLETED';
-- 홈 이어하기: 게스트의 최근 진행·일시정지 활동
CREATE INDEX activities_guest_open_idx
    ON activities (guest_id, state_changed_at DESC, id DESC) WHERE state IN ('IN_PROGRESS', 'PAUSED');
-- 기록 상세 이력·목록의 최신 선택 도움
CREATE INDEX activities_worry_history_idx ON activities (worry_id, last_started_at DESC, id DESC);

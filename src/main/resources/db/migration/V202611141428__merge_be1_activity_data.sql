ALTER TABLE worries ADD CONSTRAINT worries_id_guest_unique UNIQUE (id, guest_id);
ALTER TABLE activities ADD CONSTRAINT activities_worry_owner_fk
    FOREIGN KEY (worry_id, guest_id) REFERENCES worries (id, guest_id) ON DELETE CASCADE;

-- Keep older stopped actions as history when more than one could previously resume.
ALTER TABLE activities ADD COLUMN resumable boolean NOT NULL DEFAULT true;
DROP INDEX activities_one_resumable_action_idx;
CREATE UNIQUE INDEX activities_one_resumable_action_idx ON activities (worry_id)
    WHERE help_type = 'ACTION' AND state <> 'COMPLETED' AND resumable;

WITH legacy AS (
    SELECT a.*,
           row_number() OVER (
               PARTITION BY worry_id, help_type, (state = 'COMPLETED')
               ORDER BY (state IN ('IN_PROGRESS', 'PAUSED')) DESC, updated_at DESC, id DESC
           ) AS resume_rank
    FROM be1_activities a
)
INSERT INTO activities (
    id, guest_id, worry_id, help_type, state, started_at, last_started_at, state_changed_at, ended_at,
    step1_done, step2_done, step3_done, rest_duration_seconds, rest_ends_at, rest_remaining_seconds, resumable
)
SELECT a.id, w.guest_id, a.worry_id, a.help_type, a.state, a.created_at, a.updated_at, a.updated_at,
       CASE WHEN a.state IN ('STOPPED', 'COMPLETED') THEN a.updated_at END,
       CASE WHEN a.help_type = 'ACTION' THEN (a.checked_steps & 1) <> 0 END,
       CASE WHEN a.help_type = 'ACTION' THEN (a.checked_steps & 2) <> 0 END,
       CASE WHEN a.help_type = 'ACTION' THEN (a.checked_steps & 4) <> 0 END,
       CASE WHEN a.help_type = 'REST' THEN 180 END,
       a.ends_at,
       CASE WHEN a.state = 'PAUSED' THEN GREATEST(1, (a.remaining_ms + 999) / 1000) END,
       a.help_type <> 'ACTION' OR a.resume_rank = 1
FROM legacy a JOIN worries w ON w.id = a.worry_id;

INSERT INTO activity_feedback (activity_id, answer, answered_at, updated_at)
SELECT f.activity_id, f.answer, a.updated_at, a.updated_at
FROM be1_activity_feedback f JOIN be1_activities a ON a.id = f.activity_id;

ALTER TABLE activities ADD CONSTRAINT activities_id_worry_unique UNIQUE (id, worry_id);
CREATE TABLE activity_start_keys (
    worry_id uuid NOT NULL,
    start_key uuid NOT NULL,
    activity_id uuid NOT NULL,
    PRIMARY KEY (worry_id, start_key),
    FOREIGN KEY (activity_id, worry_id) REFERENCES activities (id, worry_id) ON DELETE CASCADE
);
INSERT INTO activity_start_keys (worry_id, start_key, activity_id)
SELECT worry_id, start_key, id FROM be1_activities;

DROP TABLE be1_activity_feedback;
DROP TABLE be1_activities;

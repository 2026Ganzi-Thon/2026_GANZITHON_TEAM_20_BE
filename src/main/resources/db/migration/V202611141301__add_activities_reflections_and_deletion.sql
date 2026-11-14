CREATE TABLE activities (
    id uuid PRIMARY KEY,
    worry_id uuid NOT NULL REFERENCES worries (id) ON DELETE CASCADE,
    start_key uuid NOT NULL,
    help_type text NOT NULL CHECK (help_type IN ('ACTION', 'REST', 'EMPATHY')),
    state text NOT NULL CHECK (state IN ('IN_PROGRESS', 'PAUSED', 'STOPPED', 'COMPLETED')),
    checked_steps integer NOT NULL DEFAULT 0 CHECK (checked_steps BETWEEN 0 AND 7),
    ends_at timestamptz,
    remaining_ms integer CHECK (remaining_ms BETWEEN 0 AND 180000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (worry_id, start_key)
);
CREATE UNIQUE INDEX activities_one_active ON activities (worry_id)
    WHERE state IN ('IN_PROGRESS', 'PAUSED');
CREATE INDEX activities_worry_history ON activities (worry_id, created_at DESC, id DESC);

CREATE TABLE activity_feedback (
    activity_id uuid PRIMARY KEY REFERENCES activities (id) ON DELETE CASCADE,
    answer text NOT NULL CHECK (answer IN ('HELPFUL', 'UNSURE', 'NOT_HELPFUL'))
);

CREATE TABLE reflections (
    worry_id uuid PRIMARY KEY REFERENCES worries (id) ON DELETE CASCADE,
    answer text NOT NULL CHECK (answer IN ('BETTER', 'ONGOING', 'AS_FEARED')),
    responded_at timestamptz NOT NULL
);

CREATE TABLE reflection_skips (
    guest_id uuid NOT NULL REFERENCES guests (id) ON DELETE CASCADE,
    local_date date NOT NULL,
    PRIMARY KEY (guest_id, local_date)
);

CREATE TABLE worry_deletion_receipts (
    worry_id uuid PRIMARY KEY,
    guest_id uuid NOT NULL REFERENCES guests (id) ON DELETE CASCADE,
    completed_at timestamptz NOT NULL
);

CREATE INDEX worries_for_cleanup ON worries (expires_at, id);
CREATE INDEX worries_pending_deletion ON worries (id) WHERE deletion_started_at IS NOT NULL;

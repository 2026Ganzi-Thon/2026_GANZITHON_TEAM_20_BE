CREATE TABLE analysis_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    guest_id uuid NOT NULL REFERENCES guests (id) ON DELETE CASCADE,
    submission_key uuid NOT NULL,
    submission_fingerprint bytea,
    state text NOT NULL DEFAULT 'QUEUED',
    accepted_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    publish_at timestamptz,
    input_ciphertext bytea,
    context_hash bytea,
    guidance_until timestamptz,
    attempt_id uuid,
    attempt_count integer NOT NULL DEFAULT 0,
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL,
    CONSTRAINT analysis_requests_submission_unique UNIQUE (guest_id, submission_key),
    CONSTRAINT analysis_requests_owner_unique UNIQUE (id, guest_id),
    CONSTRAINT analysis_requests_state_check
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SUPPORT_GUIDANCE', 'CANCELLED')),
    CONSTRAINT analysis_requests_lease_check
        CHECK ((state = 'RUNNING' AND attempt_id IS NOT NULL AND lease_until IS NOT NULL)
            OR (state <> 'RUNNING' AND attempt_id IS NULL AND lease_until IS NULL)),
    CONSTRAINT analysis_requests_expiry_check CHECK (expires_at > accepted_at),
    CONSTRAINT analysis_requests_attempt_count_check CHECK (attempt_count >= 0)
);

CREATE INDEX analysis_requests_queued_idx ON analysis_requests (next_attempt_at, id)
    WHERE state = 'QUEUED';
CREATE INDEX analysis_requests_lease_idx ON analysis_requests (lease_until, id)
    WHERE state = 'RUNNING';

CREATE TABLE analysis_retries (
    request_id uuid NOT NULL REFERENCES analysis_requests (id) ON DELETE CASCADE,
    retry_key uuid NOT NULL,
    accepted_at timestamptz NOT NULL,
    fingerprint bytea,
    PRIMARY KEY (request_id, retry_key)
);

CREATE TABLE worries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    guest_id uuid NOT NULL REFERENCES guests (id) ON DELETE CASCADE,
    request_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    sealed_at timestamptz NOT NULL,
    publish_at timestamptz NOT NULL,
    first_viewed_at timestamptz,
    expires_at timestamptz NOT NULL,
    deletion_started_at timestamptz,
    input_ciphertext bytea NOT NULL,
    CONSTRAINT worries_request_owner_fk
        FOREIGN KEY (request_id, guest_id) REFERENCES analysis_requests (id, guest_id) ON DELETE CASCADE,
    CONSTRAINT worries_publication_check CHECK (publish_at > created_at AND publish_at < expires_at)
);

CREATE INDEX worries_guest_created_idx ON worries (guest_id, created_at DESC, id DESC);

CREATE TABLE ai_contents (
    worry_id uuid PRIMARY KEY REFERENCES worries (id) ON DELETE CASCADE,
    content_ciphertext bytea NOT NULL
);

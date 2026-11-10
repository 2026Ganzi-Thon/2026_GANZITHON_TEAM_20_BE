CREATE TABLE guests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    state text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT guests_state_check CHECK (state IN ('ACTIVE', 'DELETING'))
);

CREATE TABLE guest_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    guest_id uuid NOT NULL REFERENCES guests (id) ON DELETE CASCADE,
    token_hash bytea NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL
);

CREATE INDEX guest_sessions_guest_id_idx ON guest_sessions (guest_id);

CREATE TABLE guest_settings (
    guest_id uuid PRIMARY KEY REFERENCES guests (id) ON DELETE CASCADE,
    default_open_local_time time NOT NULL DEFAULT TIME '08:00'
);

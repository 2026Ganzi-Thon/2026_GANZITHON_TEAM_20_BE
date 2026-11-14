ALTER TABLE guest_settings ADD COLUMN public_push_since timestamptz;

CREATE TABLE push_subscriptions (
    guest_id uuid NOT NULL REFERENCES guests (id) ON DELETE CASCADE,
    endpoint text NOT NULL,
    p256dh text NOT NULL,
    auth text NOT NULL,
    subscribed_at timestamptz NOT NULL,
    PRIMARY KEY (guest_id, endpoint)
);

CREATE TABLE vault_reminders (
    guest_id uuid PRIMARY KEY REFERENCES guests (id) ON DELETE CASCADE,
    worry_id uuid NOT NULL REFERENCES worries (id) ON DELETE CASCADE,
    endpoint text NOT NULL,
    send_at timestamptz NOT NULL,
    FOREIGN KEY (guest_id, endpoint) REFERENCES push_subscriptions (guest_id, endpoint) ON DELETE CASCADE
);

CREATE TABLE publication_push_receipts (
    worry_id uuid NOT NULL REFERENCES worries (id) ON DELETE CASCADE,
    guest_id uuid NOT NULL,
    endpoint text NOT NULL,
    PRIMARY KEY (worry_id, guest_id, endpoint),
    FOREIGN KEY (guest_id, endpoint) REFERENCES push_subscriptions (guest_id, endpoint) ON DELETE CASCADE
);

CREATE INDEX worries_publication_push ON worries (guest_id, publish_at)
    WHERE deletion_started_at IS NULL AND first_viewed_at IS NULL;

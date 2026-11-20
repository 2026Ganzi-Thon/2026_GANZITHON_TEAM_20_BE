-- A BE2 database may already have the activity tables before the BE1 migrations run.
-- Free the names for the original BE1 migration. Its checksum stays unchanged.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'activities' AND column_name = 'guest_id'
    ) THEN
        ALTER TABLE activities RENAME TO be2_activities_upgrade;
        ALTER TABLE be2_activities_upgrade RENAME CONSTRAINT activities_pkey TO be2_activities_upgrade_pkey;
        IF to_regclass('activity_feedback') IS NOT NULL THEN
            ALTER TABLE activity_feedback RENAME TO be2_feedback_upgrade;
            ALTER TABLE be2_feedback_upgrade RENAME CONSTRAINT activity_feedback_pkey TO be2_feedback_upgrade_pkey;
        END IF;
    END IF;
END $$;

-- Preserve the original version and checksum for databases that already applied it.
ALTER TABLE activity_feedback RENAME TO be1_activity_feedback;
ALTER TABLE activities RENAME TO be1_activities;
ALTER TABLE be1_activity_feedback RENAME CONSTRAINT activity_feedback_pkey TO be1_activity_feedback_pkey;
ALTER TABLE be1_activities RENAME CONSTRAINT activities_pkey TO be1_activities_pkey;

-- Restore tables already present in BE2 databases. The foreign key follows the table rename.
DO $$
BEGIN
    IF to_regclass('be2_activities_upgrade') IS NOT NULL THEN
        ALTER TABLE be2_activities_upgrade RENAME TO activities;
        ALTER TABLE activities RENAME CONSTRAINT be2_activities_upgrade_pkey TO activities_pkey;
    END IF;
    IF to_regclass('be2_feedback_upgrade') IS NOT NULL THEN
        ALTER TABLE be2_feedback_upgrade RENAME TO activity_feedback;
        ALTER TABLE activity_feedback RENAME CONSTRAINT be2_feedback_upgrade_pkey TO activity_feedback_pkey;
    END IF;
END $$;

-- V101 – Ensure audit_log has all required columns and indexes.
-- Defensive: adds columns with IF NOT EXISTS so this migration is safe
-- whether the table was created by V5, by Hibernate ddl-auto, or exists
-- only partially from a previously-failed migration attempt.

ALTER TABLE audit_log
    -- Original V5 columns (may be missing if the table pre-existed V5)
    ADD COLUMN IF NOT EXISTS user_id     BIGINT,
    ADD COLUMN IF NOT EXISTS school_id   BIGINT,
    ADD COLUMN IF NOT EXISTS entity_type VARCHAR(255),
    ADD COLUMN IF NOT EXISTS timestamp   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Enrichment columns added by this migration
    ADD COLUMN IF NOT EXISTS entity_id   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS ip_address  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS user_agent  VARCHAR(512),
    ADD COLUMN IF NOT EXISTS request_id  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS old_value   TEXT,
    ADD COLUMN IF NOT EXISTS new_value   TEXT,
    ADD COLUMN IF NOT EXISTS outcome     VARCHAR(32) NOT NULL DEFAULT 'SUCCESS';

-- Create indexes only if they do not already exist.
-- Using DO $$ … $$ so we can guard each CREATE INDEX with an existence check,
-- which avoids a hard error on re-runs in environments that do not support
-- CREATE INDEX IF NOT EXISTS (older PostgreSQL) or where the column was added
-- mid-flight on a previous aborted attempt.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE tablename = 'audit_log' AND indexname = 'idx_audit_log_user_id'
    ) THEN
        CREATE INDEX idx_audit_log_user_id ON audit_log (user_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE tablename = 'audit_log' AND indexname = 'idx_audit_log_school_ts'
    ) THEN
        CREATE INDEX idx_audit_log_school_ts ON audit_log (school_id, timestamp DESC);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE tablename = 'audit_log' AND indexname = 'idx_audit_log_action'
    ) THEN
        CREATE INDEX idx_audit_log_action ON audit_log (action);
    END IF;
END $$;

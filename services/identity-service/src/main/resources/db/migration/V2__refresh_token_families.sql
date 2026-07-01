-- Refresh-token rotation with reuse detection: auth_sessions becomes one row per
-- issued refresh token, grouped by family_id (login lineage) with a lifecycle status.
ALTER TABLE identity.auth_sessions ADD COLUMN IF NOT EXISTS family_id VARCHAR(64);
ALTER TABLE identity.auth_sessions ADD COLUMN IF NOT EXISTS status VARCHAR(16);
ALTER TABLE identity.auth_sessions ADD COLUMN IF NOT EXISTS rotated_at TIMESTAMPTZ;

-- Backfill existing live sessions so they survive the deploy: each becomes its own
-- single-token ACTIVE family (id doubles as family_id).
UPDATE identity.auth_sessions SET family_id = id    WHERE family_id IS NULL;
UPDATE identity.auth_sessions SET status = 'ACTIVE' WHERE status IS NULL;

ALTER TABLE identity.auth_sessions ALTER COLUMN family_id SET NOT NULL;
ALTER TABLE identity.auth_sessions ALTER COLUMN status SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_identity_auth_sessions_family ON identity.auth_sessions(family_id);

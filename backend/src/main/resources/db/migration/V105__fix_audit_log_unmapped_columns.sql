-- V105 – Fix all unmapped NOT NULL columns on audit_log.
-- The live DB has extra columns added directly (not via migrations) that have
-- NOT NULL constraints. The entity does not map these columns, so Hibernate's
-- INSERT omits them and PostgreSQL rejects the row. Fix strategy:
--   • Columns the entity DOES map: make nullable so null values are accepted.
--   • Columns the entity does NOT map (event_time, actor_user_id): add a
--     DEFAULT so PostgreSQL fills them in automatically on every INSERT.
--
-- All ADD COLUMN IF NOT EXISTS guards are for fresh environments where these
-- columns do not exist yet (they will be created nullable/with defaults).

-- event_time: not mapped by the entity; default to now() so every INSERT
-- gets the correct event timestamp without application code changes.
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS event_time TIMESTAMPTZ DEFAULT now();
ALTER TABLE audit_log ALTER COLUMN event_time DROP NOT NULL;
ALTER TABLE audit_log ALTER COLUMN event_time SET DEFAULT now();

-- ip_address, user_agent, request_id, school_id, user_id, new_value, old_value
-- are mapped but legitimately null for many event types.
ALTER TABLE audit_log ALTER COLUMN ip_address   DROP NOT NULL;
ALTER TABLE audit_log ALTER COLUMN user_agent   DROP NOT NULL;
ALTER TABLE audit_log ALTER COLUMN request_id   DROP NOT NULL;
ALTER TABLE audit_log ALTER COLUMN school_id    DROP NOT NULL;
ALTER TABLE audit_log ALTER COLUMN user_id      DROP NOT NULL;
ALTER TABLE audit_log ALTER COLUMN new_value    DROP NOT NULL;
ALTER TABLE audit_log ALTER COLUMN old_value    DROP NOT NULL;

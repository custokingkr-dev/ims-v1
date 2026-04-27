-- V103 – Make actor_user_id nullable on audit_log.
-- The column exists in the live DB as NOT NULL but is not mapped by the entity
-- (user_id already stores the same value). Add IF NOT EXISTS for fresh envs,
-- then drop any NOT NULL constraint so INSERTs without this column succeed.

ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS actor_user_id BIGINT;
ALTER TABLE audit_log ALTER COLUMN actor_user_id DROP NOT NULL;

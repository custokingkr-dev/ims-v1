-- V102 – Reconcile actor_email column on audit_log.
-- The column was added to the live database outside of migrations as NOT NULL.
-- Add it as nullable (IF NOT EXISTS covers fresh envs) then drop any NOT NULL
-- constraint so the entity can omit it for non-login events.

ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS actor_email VARCHAR(255);
ALTER TABLE audit_log ALTER COLUMN actor_email DROP NOT NULL;

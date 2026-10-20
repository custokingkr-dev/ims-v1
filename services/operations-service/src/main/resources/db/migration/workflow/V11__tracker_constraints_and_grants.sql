-- Notebook order tracker review fixes (spec §3.4, finding 14): what the database itself enforces.

-- 1. Internal notes and step-10 follow-ups are logs. The runtime role adds and reads entries but never changes or removes
--    one (the repository only inserts and selects). Deleting a tracker still removes them through the ON DELETE CASCADE
--    foreign keys, which run with the table owner's privileges. afterMigrate mirrors app_rt's grants onto the dedicated
--    runtime role, so the revoke reaches it too.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        REVOKE UPDATE, DELETE ON workflow.workflow_internal_notes, workflow.workflow_follow_ups FROM app_rt;
    END IF;
END $$;

-- 2. At most one active step per tracker. The index is checked row by row, so every statement must keep it: completing a
--    step completes it before the next one is activated, and reopening locks the later steps (the active one among them)
--    before the reopened step is activated. No statement ever holds two active rows, so the index need not be deferrable.
CREATE UNIQUE INDEX uk_wis_one_active ON workflow.workflow_instance_steps (instance_id) WHERE status = 'ACTIVE';

-- 3. V9's idx_wis_active_assignee_due (assignee_user_id, due_at) WHERE status = 'ACTIVE' serves every lookup this did.
DROP INDEX IF EXISTS workflow.idx_wis_assignee_active;

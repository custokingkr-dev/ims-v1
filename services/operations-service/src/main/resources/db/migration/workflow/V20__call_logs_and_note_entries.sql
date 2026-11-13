-- Item order workflows (design v1 §3.1, D35, D38, D40). "Calls, changes and approvals must be logged standardly" [SOP §2].
--   * workflow_call_logs: one row per call or discussion with the SOP's entities and outcomes. Entries belong to the order
--     and are append-only: the runtime role may add and read them, never change or remove them (as V11 did for notes).
--     Deleting a workflow still removes them through the ON DELETE CASCADE key, which runs with the owner's privileges.
--   * workflow_internal_notes gains step_order: null is an order-level internal note, a step number makes it a stage note.
--     Stage notes become append-only entries; each existing overwritten note is copied once as the first entry of its
--     stage. The old stage_note columns stay but are no longer read.
-- Owner backfills on forced-RLS tables need the deliberate bypass (V7).
SET LOCAL app.bypass_rls = 'on';

-- 1. Call logs (tenant data) ------------------------------------------------------------------------------------------
CREATE TABLE workflow.workflow_call_logs (
    id                BIGSERIAL    PRIMARY KEY,
    instance_id       BIGINT       NOT NULL,
    school_id         BIGINT       NOT NULL,
    -- The stage the call was about; null when the order had none (for example after it finished).
    step_order        INTEGER      CONSTRAINT ck_wcl_step_order CHECK (step_order BETWEEN 1 AND 99),
    contacted         VARCHAR(30)  NOT NULL CONSTRAINT ck_wcl_contacted CHECK (contacted IN (
                          'SCHOOL', 'DESIGNER', 'PRINTING_PRESS', 'MANUFACTURING_FACTORY', 'INTERNAL_APPROVER')),
    outcome           VARCHAR(30)  NOT NULL CONSTRAINT ck_wcl_outcome CHECK (outcome IN (
                          'CONNECTED', 'NO_ANSWER', 'BUSY', 'CALLBACK_REQUESTED', 'DELAY_REPORTED',
                          'CHANGES_REQUESTED', 'APPROVAL_RECEIVED')),
    -- Who was called: picked from the order's contacts, vendors or designer, or typed.
    contact_label     VARCHAR(200) CONSTRAINT ck_wcl_contact_label CHECK (contact_label IS NULL OR btrim(contact_label) <> ''),
    vendor_id         BIGINT       REFERENCES workflow.workflow_vendors (id),
    designer_id       BIGINT       REFERENCES workflow.workflow_designers (id),
    -- At most 100 words, which the application counts; the column caps the characters.
    notes             TEXT         CONSTRAINT ck_wcl_notes CHECK (notes IS NULL OR (btrim(notes) <> '' AND char_length(notes) <= 1000)),
    next_follow_up_at TIMESTAMPTZ,
    created_by        BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_wcl_instance FOREIGN KEY (instance_id, school_id)
        REFERENCES workflow.workflow_instances (id, school_id) ON DELETE CASCADE,
    -- A callback without a time cannot appear in Upcoming actions (D35, D39).
    CONSTRAINT ck_wcl_callback_follow_up CHECK (outcome <> 'CALLBACK_REQUESTED' OR next_follow_up_at IS NOT NULL)
);
-- An order's call history, newest first.
CREATE INDEX idx_wcl_instance_created ON workflow.workflow_call_logs (instance_id, created_at);
-- Open callbacks by school.
CREATE INDEX idx_wcl_school_follow_up ON workflow.workflow_call_logs (school_id, next_follow_up_at)
    WHERE next_follow_up_at IS NOT NULL;

ALTER TABLE workflow.workflow_call_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.workflow_call_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow.workflow_call_logs
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));

-- 2. Stage notes become entries ---------------------------------------------------------------------------------------
ALTER TABLE workflow.workflow_internal_notes
    ADD COLUMN step_order INTEGER CONSTRAINT ck_win_step_order CHECK (step_order BETWEEN 1 AND 99);

-- Each saved stage note once, with its author and save time. A blank note or one with no recorded author is not a note
-- anyone wrote, so it is not copied.
INSERT INTO workflow.workflow_internal_notes (instance_id, school_id, author_user_id, body, created_at, step_order)
SELECT s.instance_id, s.school_id, s.stage_note_saved_by, s.stage_note, COALESCE(s.stage_note_saved_at, now()), s.step_order
FROM workflow.workflow_instance_steps s
WHERE s.stage_note IS NOT NULL
  AND btrim(s.stage_note) <> ''
  AND s.stage_note_saved_by IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM workflow.workflow_internal_notes n
      WHERE n.instance_id = s.instance_id AND n.step_order = s.step_order AND n.body = s.stage_note)
ORDER BY s.instance_id, s.step_order;

-- 3. Runtime grants: add and read call logs only (the dedicated-role ACL is resynchronised by afterMigrate, so the revoke
--    reaches it too). Default privileges may have granted UPDATE and DELETE on creation; they are taken back explicitly.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT ON workflow.workflow_call_logs TO app_rt;
        REVOKE UPDATE, DELETE, TRUNCATE ON workflow.workflow_call_logs FROM app_rt;
        GRANT USAGE, SELECT ON SEQUENCE workflow.workflow_call_logs_id_seq TO app_rt;
    END IF;
END $$;

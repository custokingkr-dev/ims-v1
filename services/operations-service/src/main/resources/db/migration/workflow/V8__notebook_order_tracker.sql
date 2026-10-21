-- Notebook order tracker (spec v4). Reuses workflow_definitions / workflow_steps / workflow_instances /
-- workflow_actions and adds only what those four cannot express:
--   * workflow_steps         + customised_only, default time slot            (the 12-step definition)
--   * workflow_instances     + UNIQUE (id, school_id) for tenant-safe child foreign keys
--   * workflow_notebook_trackers   one row per order: vendor, designer, design-approval state, placement snapshot
--   * workflow_instance_steps      one row per order per step: status, assignee, clock, saved data, stage note
--   * workflow_school_step_sla     per-school, per-step time-slot overrides (superadmin only, app-enforced)
--   * workflow_internal_notes      append-only log
--   * workflow_follow_ups          step 10 repeated entries
--   * workflow_designers           global designer list (no tenant key, like workflow_definitions)
-- workflow_actions is the history log; actions used: STEP_COMPLETED, STEP_REOPENED, STEP_ASSIGNED,
-- DESIGN_SENT, DESIGN_APPROVED, CHANGES_REQUESTED.

-- 1. Definition steps -------------------------------------------------------------------------------------
ALTER TABLE workflow.workflow_steps
    ADD COLUMN customised_only   BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN default_sla_value INTEGER,
    ADD COLUMN default_sla_unit  VARCHAR(16);
-- A CHECK that evaluates to NULL passes, so "value without unit" is excluded explicitly by the pairing test.
ALTER TABLE workflow.workflow_steps ADD CONSTRAINT ck_wf_step_default_sla CHECK (
    (default_sla_value IS NULL) = (default_sla_unit IS NULL)
    AND (default_sla_value IS NULL OR (default_sla_value > 0 AND default_sla_unit IN ('HOURS', 'WORKING_DAYS'))));

INSERT INTO workflow.workflow_definitions (id, name, description) VALUES
    ('NOTEBOOK_ORDER_TRACKER', 'Notebook Order Tracker',
     '12-step production tracker for notebook orders; steps 2-4 apply to customised orders only')
ON CONFLICT (id) DO NOTHING;

INSERT INTO workflow.workflow_steps
    (definition_id, step_order, step_name, required_permission, required_role, customised_only, default_sla_value, default_sla_unit) VALUES
    ('NOTEBOOK_ORDER_TRACKER',  1, 'Order Details',           'workflow:act', NULL,         false, 4, 'HOURS'),
    ('NOTEBOOK_ORDER_TRACKER',  2, 'Designing Activity',      'workflow:act', NULL,         true,  2, 'WORKING_DAYS'),
    ('NOTEBOOK_ORDER_TRACKER',  3, 'Head Approval',           'workflow:act', 'SUPERADMIN', true,  1, 'WORKING_DAYS'),
    ('NOTEBOOK_ORDER_TRACKER',  4, 'CDR Conversion',          'workflow:act', NULL,         true,  4, 'HOURS'),
    ('NOTEBOOK_ORDER_TRACKER',  5, 'Production Planning',     'workflow:act', NULL,         false, 4, 'HOURS'),
    ('NOTEBOOK_ORDER_TRACKER',  6, 'Board Procurement',       'workflow:act', NULL,         false, 1, 'WORKING_DAYS'),
    ('NOTEBOOK_ORDER_TRACKER',  7, 'Printing Press',          'workflow:act', NULL,         false, 4, 'HOURS'),
    ('NOTEBOOK_ORDER_TRACKER',  8, 'Pre-Print Quality Gate',  'workflow:act', NULL,         false, NULL, NULL),
    ('NOTEBOOK_ORDER_TRACKER',  9, 'Factory Handover',        'workflow:act', NULL,         false, 4, 'HOURS'),
    ('NOTEBOOK_ORDER_TRACKER', 10, 'Production Follow-up',    'workflow:act', NULL,         false, NULL, NULL),
    ('NOTEBOOK_ORDER_TRACKER', 11, 'Validation Count',        'workflow:act', NULL,         false, 4, 'HOURS'),
    ('NOTEBOOK_ORDER_TRACKER', 12, 'Delivery & Invoicing',    'workflow:act', NULL,         false, 1, 'WORKING_DAYS')
ON CONFLICT (definition_id, step_order) DO NOTHING;

-- 2. Instances: a (id, school_id) key so every child row is tied to its own school ------------------------
ALTER TABLE workflow.workflow_instances ADD CONSTRAINT uk_wfi_id_school UNIQUE (id, school_id);

-- 3. Global designer list (no tenant key; superadmin-maintained, app-enforced) --------------------------
CREATE TABLE workflow.workflow_designers (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(200) NOT NULL CHECK (btrim(name) <> ''),
    phone       VARCHAR(32)  NOT NULL CHECK (btrim(phone) <> ''),
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  BIGINT,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_wf_designer UNIQUE (name, phone)
);

-- 4. One row per tracked order ------------------------------------------------------------------------------
CREATE TABLE workflow.workflow_notebook_trackers (
    instance_id          BIGINT       PRIMARY KEY,
    school_id            BIGINT       NOT NULL,
    order_id             VARCHAR(255) NOT NULL,
    customised           BOOLEAN      NOT NULL,
    ordered_count        INTEGER      NOT NULL CHECK (ordered_count > 0),
    placed_at            TIMESTAMPTZ  NOT NULL,
    time_zone            VARCHAR(64)  NOT NULL CHECK (btrim(time_zone) <> ''),
    vendor_name          VARCHAR(200),
    vendor_phone         VARCHAR(32),
    designer_id          BIGINT       REFERENCES workflow.workflow_designers (id),
    design_state         VARCHAR(20)  NOT NULL DEFAULT 'NOT_SENT'
                         CHECK (design_state IN ('NOT_SENT', 'SENT', 'CHANGES_REQUESTED', 'APPROVED')),
    design_state_note    VARCHAR(1000),
    design_state_at      TIMESTAMPTZ,
    design_state_by      BIGINT,
    approved_draft_ref   BIGINT,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_wnt_instance FOREIGN KEY (instance_id, school_id)
        REFERENCES workflow.workflow_instances (id, school_id) ON DELETE CASCADE,
    CONSTRAINT ck_wnt_vendor_pair CHECK ((vendor_name IS NULL) = (vendor_phone IS NULL)),
    CONSTRAINT ck_wnt_approved_ref CHECK (approved_draft_ref IS NULL OR design_state = 'APPROVED')
);
-- Catalog order ids are globally unique (catalog_orders.id is the primary key), so one tracker per order id.
CREATE UNIQUE INDEX uk_wnt_order ON workflow.workflow_notebook_trackers (order_id);

-- 5. One row per order per step ---------------------------------------------------------------------------
CREATE TABLE workflow.workflow_instance_steps (
    instance_id          BIGINT       NOT NULL,
    school_id            BIGINT       NOT NULL,
    definition_id        VARCHAR(100) NOT NULL DEFAULT 'NOTEBOOK_ORDER_TRACKER',
    step_order           INTEGER      NOT NULL,
    status               VARCHAR(10)  NOT NULL DEFAULT 'LOCKED' CHECK (status IN ('LOCKED', 'ACTIVE', 'COMPLETED')),
    assignee_user_id     BIGINT,
    assigned_by          BIGINT,
    assigned_at          TIMESTAMPTZ,
    started_at           TIMESTAMPTZ,
    due_at               TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    completed_by         BIGINT,
    data                 JSONB        NOT NULL DEFAULT '{}'::jsonb,
    data_saved_at        TIMESTAMPTZ,
    data_saved_by        BIGINT,
    stage_note           TEXT         CHECK (char_length(stage_note) <= 4000),
    stage_note_saved_at  TIMESTAMPTZ,
    stage_note_saved_by  BIGINT,
    version              BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (instance_id, step_order),
    CONSTRAINT fk_wis_instance FOREIGN KEY (instance_id, school_id)
        REFERENCES workflow.workflow_instances (id, school_id) ON DELETE CASCADE,
    CONSTRAINT fk_wis_step FOREIGN KEY (definition_id, step_order)
        REFERENCES workflow.workflow_steps (definition_id, step_order),
    CONSTRAINT ck_wis_status_times CHECK (
        (status = 'LOCKED'    AND started_at IS NULL     AND due_at IS NULL AND completed_at IS NULL)
     OR (status = 'ACTIVE'    AND started_at IS NOT NULL AND completed_at IS NULL)
     OR (status = 'COMPLETED' AND started_at IS NOT NULL AND completed_at IS NOT NULL))
);
-- "Which active steps are overdue" and "my open steps".
CREATE INDEX idx_wis_active_due ON workflow.workflow_instance_steps (school_id, due_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_wis_assignee_active ON workflow.workflow_instance_steps (assignee_user_id) WHERE status = 'ACTIVE';

-- 6. Per-school time-slot overrides. A row with both columns NULL means "no timer for this school and step". --
CREATE TABLE workflow.workflow_school_step_sla (
    school_id     BIGINT       NOT NULL,
    definition_id VARCHAR(100) NOT NULL DEFAULT 'NOTEBOOK_ORDER_TRACKER',
    step_order    INTEGER      NOT NULL,
    sla_value     INTEGER,
    sla_unit      VARCHAR(16),
    updated_by    BIGINT,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (school_id, definition_id, step_order),
    CONSTRAINT fk_wsss_step FOREIGN KEY (definition_id, step_order)
        REFERENCES workflow.workflow_steps (definition_id, step_order),
    CONSTRAINT ck_wsss_sla CHECK (
        (sla_value IS NULL) = (sla_unit IS NULL)
        AND (sla_value IS NULL OR (sla_value > 0 AND sla_unit IN ('HOURS', 'WORKING_DAYS'))))
);

-- 7. Internal notes (append-only by convention: the application never updates or deletes) ----------------
CREATE TABLE workflow.workflow_internal_notes (
    id             BIGSERIAL    PRIMARY KEY,
    instance_id    BIGINT       NOT NULL,
    school_id      BIGINT       NOT NULL,
    author_user_id BIGINT       NOT NULL,
    body           TEXT         NOT NULL CHECK (btrim(body) <> '' AND char_length(body) <= 4000),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_win_instance FOREIGN KEY (instance_id, school_id)
        REFERENCES workflow.workflow_instances (id, school_id) ON DELETE CASCADE
);
CREATE INDEX idx_win_instance_created ON workflow.workflow_internal_notes (instance_id, created_at);

-- 8. Step 10 follow-ups: repeated entries -------------------------------------------------------------------
CREATE TABLE workflow.workflow_follow_ups (
    id                BIGSERIAL    PRIMARY KEY,
    instance_id       BIGINT       NOT NULL,
    school_id         BIGINT       NOT NULL,
    percent_complete  SMALLINT     NOT NULL CHECK (percent_complete BETWEEN 0 AND 100),
    next_follow_up_on DATE,
    contact_phone     VARCHAR(32),
    created_by        BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_wfu_instance FOREIGN KEY (instance_id, school_id)
        REFERENCES workflow.workflow_instances (id, school_id) ON DELETE CASCADE
);
CREATE INDEX idx_wfu_instance_created ON workflow.workflow_follow_ups (instance_id, created_at);

-- 9. Row-level security. Same shape as the existing workflow policies plus a third disjunct: operators have no
-- home school, so they are admitted for exactly the schools in app.operator_schools, which the tracker
-- repository sets transaction-locally (empty = fail closed). Superadmin bypasses through app.bypass_rls.
DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'workflow_notebook_trackers', 'workflow_instance_steps', 'workflow_school_step_sla',
        'workflow_internal_notes', 'workflow_follow_ups', 'workflow_instances', 'workflow_actions'] LOOP
        EXECUTE format('ALTER TABLE workflow.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE workflow.%I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON workflow.%I', table_name);
        EXECUTE format($policy$
            CREATE POLICY tenant_isolation ON workflow.%I
            USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on'
                OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
            WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on'
                OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
        $policy$, table_name);
    END LOOP;
END $$;

-- 10. Runtime grants (the dedicated-role ACL is resynchronised by afterMigrate; this covers app_rt where it exists).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            workflow.workflow_designers, workflow.workflow_notebook_trackers, workflow.workflow_instance_steps,
            workflow.workflow_school_step_sla, workflow.workflow_internal_notes, workflow.workflow_follow_ups TO app_rt;
        GRANT USAGE, SELECT ON SEQUENCE
            workflow.workflow_designers_id_seq, workflow.workflow_internal_notes_id_seq,
            workflow.workflow_follow_ups_id_seq TO app_rt;
    END IF;
END $$;

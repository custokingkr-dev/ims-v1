-- Item order workflows (design v1 §3.1, D1-D2, D8, D15-D16, D23). One engine runs every item's order workflow from a
-- per-item template: the JSON file src/main/resources/workflow-templates/<definition id>.json plus the matching
-- workflow_steps rows below. The service refuses to start when a template's step orders, codes, titles, phases or default
-- SLAs differ from these rows, and TemplateContentTest/OrderWorkflowMigrationTest compare the two in CI.
--   * NOTEBOOK_ORDER_TRACKER keeps its id and its 12 rows (renamed only); FE, FX, BBK and the general template get 9 each.
--   * step_code is also the catalogue order status while the step is active, so it follows school-core's status pattern.
--   * phase is the board column: Intake & design, Planning, Production, Verification, Delivery.
--   * Default SLAs are 24/7 calendar HOURS; superadmin may edit them at runtime, which stamps updated_by/updated_at.
-- workflow_steps also holds the generic approval definitions (SUPPLY_ORDER_DEFAULT, FIREFIGHTING_DEFAULT and anything V1
-- copied from public). They have no codes or phases, so the two columns are required for the five item-order templates
-- only, not for every row.

-- 1. Definitions -----------------------------------------------------------------------------------------------------
UPDATE workflow.workflow_definitions
SET name = 'Notebook order workflow',
    description = 'Notebook order workflow (SOP 4.1): 12 steps; steps 2-4 apply to customised orders only'
WHERE id = 'NOTEBOOK_ORDER_TRACKER';

INSERT INTO workflow.workflow_definitions (id, name, description) VALUES
    ('FLIER_ORDER_WORKFLOW', 'Flier order workflow', 'Flier order workflow (SOP 4.2): 9 steps'),
    ('FLEX_ORDER_WORKFLOW', 'Flex order workflow', 'Flex order workflow (SOP 4.3): 9 steps'),
    ('BILL_BOOK_ORDER_WORKFLOW', 'Bill book order workflow', 'Bill book order workflow (SOP 4.4): 9 steps'),
    ('GENERAL_ITEM_ORDER_WORKFLOW', 'General item order workflow',
     'Order workflow for items without their own SOP section (certificates, flute boards, report cards, brochures, belts, standees): 9 steps')
ON CONFLICT (id) DO NOTHING;

-- 2. Step codes, phases and who last edited a default SLA -------------------------------------------------------------
ALTER TABLE workflow.workflow_steps
    ADD COLUMN step_code  VARCHAR(64),
    ADD COLUMN phase      VARCHAR(16),
    ADD COLUMN updated_by BIGINT,
    ADD COLUMN updated_at TIMESTAMPTZ;

ALTER TABLE workflow.workflow_steps
    ADD CONSTRAINT ck_wf_step_code CHECK (step_code IS NULL OR step_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    ADD CONSTRAINT ck_wf_step_phase CHECK (phase IN ('INTAKE', 'PLANNING', 'PRODUCTION', 'VERIFICATION', 'DELIVERY'));

-- 3. The notebook template's 12 rows: codes and phases. Names and SLAs are already the template's (V13). -----------------
UPDATE workflow.workflow_steps s
SET step_code = v.code, phase = v.phase, step_name = v.title
FROM (VALUES
    ( 1, 'ORDER_DETAILS',          'INTAKE',       'Order Details'),
    ( 2, 'DESIGNING_ACTIVITY',     'INTAKE',       'Designing Activity'),
    ( 3, 'CDR_CONVERSION',         'INTAKE',       'CDR Conversion'),
    ( 4, 'HEAD_APPROVAL',          'INTAKE',       'Head Approval'),
    ( 5, 'PRODUCTION_PLANNING',    'PLANNING',     'Production Planning'),
    ( 6, 'BOARD_PROCUREMENT',      'PLANNING',     'Board Procurement'),
    ( 7, 'PRE_PRINT_QUALITY_GATE', 'PRODUCTION',   'Pre-Print Quality Check'),
    ( 8, 'PRINTING_PRESS',         'PRODUCTION',   'Printing Press'),
    ( 9, 'FACTORY_HANDOVER',       'PRODUCTION',   'Factory Handover'),
    (10, 'PRODUCTION_FOLLOW_UP',   'PRODUCTION',   'Production Follow-up'),
    (11, 'VALIDATION_COUNT',       'VERIFICATION', 'Validation Count'),
    (12, 'DELIVERY_INVOICING',     'DELIVERY',     'Delivery & Invoicing')
) AS v(step_order, code, phase, title)
WHERE s.definition_id = 'NOTEBOOK_ORDER_TRACKER' AND s.step_order = v.step_order;

DO $$
BEGIN
    IF (SELECT count(*) FROM workflow.workflow_steps WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER' AND step_code IS NOT NULL) <> 12 THEN
        RAISE EXCEPTION 'V17: NOTEBOOK_ORDER_TRACKER must have exactly steps 1-12 before codes are assigned';
    END IF;
END $$;

-- 4. The nine-step templates: the same steps, codes, phases and SLAs for FE, FX, BBK and the general template. -----------
-- Codes match school-core's V36 production-workflow stages one to one, except DESIGN_ACTIVITY -> DESIGNING_ACTIVITY and
-- FINAL_VERIFICATION -> FINAL_APPROVAL (D4). Only superadmin completes Head Approval (required_role, as on the NB row).
INSERT INTO workflow.workflow_steps
    (definition_id, step_order, step_name, required_permission, required_role, customised_only,
     default_sla_value, default_sla_unit, step_code, phase)
SELECT d.id, v.step_order, v.title, 'workflow:act', v.required_role, false, v.sla_hours, 'HOURS', v.code, v.phase
FROM (VALUES ('FLIER_ORDER_WORKFLOW'), ('FLEX_ORDER_WORKFLOW'), ('BILL_BOOK_ORDER_WORKFLOW'), ('GENERAL_ITEM_ORDER_WORKFLOW')) AS d(id)
CROSS JOIN (VALUES
    (1, 'ORDER_DETAILS',           'INTAKE',       'Order Details',           NULL,          2),
    (2, 'DESIGNING_ACTIVITY',      'INTAKE',       'Designing Activity',      NULL,         72),
    (3, 'CDR_CONVERSION',          'INTAKE',       'CDR Conversion',          NULL,          2),
    (4, 'HEAD_APPROVAL',           'INTAKE',       'Head Approval',           'SUPERADMIN',  2),
    (5, 'PRODUCTION_PLANNING',     'PLANNING',     'Production Planning',     NULL,         24),
    (6, 'PRE_PRINT_QUALITY_CHECK', 'PRODUCTION',   'Pre-Print Quality Check', NULL,         24),
    (7, 'PRODUCTION_FOLLOW_UP',    'PRODUCTION',   'Production Follow-up',    NULL,         72),
    (8, 'FINAL_APPROVAL',          'VERIFICATION', 'Final Approval',          NULL,          4),
    (9, 'DELIVERY_INVOICING',      'DELIVERY',     'Delivery & Invoicing',    NULL,          4)
) AS v(step_order, code, phase, title, required_role, sla_hours)
ON CONFLICT (definition_id, step_order) DO NOTHING;

-- 5. Every item-order step has a code and a phase, and a code appears once per template. ----------------------------------
ALTER TABLE workflow.workflow_steps ADD CONSTRAINT ck_wf_step_item_order_code CHECK (
    definition_id NOT IN ('NOTEBOOK_ORDER_TRACKER', 'FLIER_ORDER_WORKFLOW', 'FLEX_ORDER_WORKFLOW',
                          'BILL_BOOK_ORDER_WORKFLOW', 'GENERAL_ITEM_ORDER_WORKFLOW')
    OR (step_code IS NOT NULL AND phase IS NOT NULL));
ALTER TABLE workflow.workflow_steps ADD CONSTRAINT uk_wf_step_def_code UNIQUE (definition_id, step_code);

-- 6. Runtime grants: superadmin edits a template step's default SLA (D23). Only the columns that edit needs are granted;
--    an environment whose app_rt already holds table-wide privileges (create-app-rt-role.sql) keeps them, nothing is revoked.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT ON workflow.workflow_definitions, workflow.workflow_steps TO app_rt;
        GRANT UPDATE (default_sla_value, default_sla_unit, updated_by, updated_at) ON workflow.workflow_steps TO app_rt;
    END IF;
END $$;

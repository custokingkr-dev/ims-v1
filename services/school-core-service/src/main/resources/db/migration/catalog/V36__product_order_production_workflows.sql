-- Production workflow for placed product orders outside the notebook tracker (2026-11-12). Nine fixed stages, from order
-- details to delivery and invoicing, for BILLBOOKS, BELTS, BROCHURES, CERTIFICATES, FLEX, FLIERS, FLUTE_BOARDS,
-- REPORT_CARDS and STANDEES; the application (ProductOrderWorkflowService) owns that list and the stage names, so the
-- tables hold no copy of either. A workflow is created, in one statement, the first time a placed, still-open order of one
-- of those categories is opened; superadmin and the school's operators run it and the school reads its progress.
-- WhatsApp group links are not stored here: product orders use the notebook tracker's school-scoped links in
-- operations-service (workflow.workflow_school_whatsapp_group_links).
CREATE TABLE catalog.product_order_workflows (
    order_id VARCHAR(255) PRIMARY KEY,
    school_id BIGINT NOT NULL,
    category_code VARCHAR(100) NOT NULL,
    current_stage VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_id, school_id),
    FOREIGN KEY (order_id, school_id) REFERENCES catalog.catalog_orders(id, school_id) ON DELETE CASCADE
);

CREATE TABLE catalog.product_order_workflow_steps (
    order_id VARCHAR(255) NOT NULL,
    school_id BIGINT NOT NULL,
    stage_code VARCHAR(64) NOT NULL,
    stage_order INTEGER NOT NULL CHECK (stage_order > 0),
    title VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('LOCKED', 'ACTIVE', 'COMPLETED')),
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    stage_note VARCHAR(4000),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (order_id, stage_code),
    UNIQUE (order_id, stage_order),
    FOREIGN KEY (order_id, school_id) REFERENCES catalog.product_order_workflows(order_id, school_id) ON DELETE CASCADE,
    CHECK ((status = 'LOCKED' AND started_at IS NULL AND completed_at IS NULL)
        OR (status = 'ACTIVE' AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND started_at IS NOT NULL AND completed_at IS NOT NULL))
);

DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['product_order_workflows', 'product_order_workflow_steps'] LOOP
        EXECUTE format('ALTER TABLE catalog.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE catalog.%I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format($policy$
            CREATE POLICY tenant_isolation ON catalog.%I
            USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on'
                OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
            WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on'
                OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
        $policy$, table_name);
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.product_order_workflows, catalog.product_order_workflow_steps TO app_rt;
    END IF;
END $$;

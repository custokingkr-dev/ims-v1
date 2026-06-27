CREATE TABLE IF NOT EXISTS workflow_definitions (
    id          VARCHAR(100) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS workflow_steps (
    id                  BIGSERIAL    NOT NULL,
    definition_id       VARCHAR(100) NOT NULL,
    step_order          INTEGER      NOT NULL,
    step_name           VARCHAR(255) NOT NULL,
    required_permission VARCHAR(100),
    required_role       VARCHAR(100),
    auto_approve        BOOLEAN      NOT NULL DEFAULT false,
    PRIMARY KEY (id),
    CONSTRAINT uk_wf_step_def_order UNIQUE (definition_id, step_order),
    CONSTRAINT fk_wf_step_def FOREIGN KEY (definition_id) REFERENCES workflow_definitions (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS workflow_instances (
    id            BIGSERIAL    NOT NULL,
    definition_id VARCHAR(100) NOT NULL,
    entity_type   VARCHAR(100) NOT NULL,
    entity_id     VARCHAR(255) NOT NULL,
    school_id     BIGINT,
    current_step  INTEGER      NOT NULL DEFAULT 0,
    status        VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    initiated_by  BIGINT,
    initiated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_wfi_def FOREIGN KEY (definition_id) REFERENCES workflow_definitions (id)
);

CREATE TABLE IF NOT EXISTS workflow_actions (
    id          BIGSERIAL    NOT NULL,
    instance_id BIGINT       NOT NULL,
    step_order  INTEGER      NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    actor_id    BIGINT,
    actor_email VARCHAR(255),
    notes       VARCHAR(1000),
    acted_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_wfa_instance FOREIGN KEY (instance_id) REFERENCES workflow_instances (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_wf_instances_entity ON workflow_instances (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_wf_instances_school ON workflow_instances (school_id, status);
CREATE INDEX IF NOT EXISTS idx_wf_actions_instance ON workflow_actions (instance_id);

DO $$
BEGIN
    IF to_regclass('public.workflow_definitions') IS NOT NULL THEN
    INSERT INTO workflow_definitions (id, name, description, active, created_at)
    SELECT id, name, description, active, created_at
    FROM public.workflow_definitions
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

INSERT INTO workflow_definitions (id, name, description) VALUES
    ('SUPPLY_ORDER_DEFAULT', 'Supply Order Approval', 'Standard 2-step approval for supply orders'),
    ('FIREFIGHTING_DEFAULT', 'Firefighting Request Approval', 'Bursar then Principal approval for firefighting')
ON CONFLICT (id) DO NOTHING;

DO $$
BEGIN
    IF to_regclass('public.workflow_steps') IS NOT NULL THEN
    INSERT INTO workflow_steps (id, definition_id, step_order, step_name, required_permission, required_role, auto_approve)
    SELECT id, definition_id, step_order, step_name, required_permission, required_role, auto_approve
    FROM public.workflow_steps
    ON CONFLICT (definition_id, step_order) DO NOTHING;
    END IF;
END $$;

INSERT INTO workflow_steps (definition_id, step_order, step_name, required_permission) VALUES
    ('SUPPLY_ORDER_DEFAULT', 1, 'Bursar Review', 'order:approve'),
    ('SUPPLY_ORDER_DEFAULT', 2, 'Principal Approval', 'order:approve'),
    ('FIREFIGHTING_DEFAULT', 1, 'Bursar Review', 'firefighting:approve'),
    ('FIREFIGHTING_DEFAULT', 2, 'Principal Approval', 'firefighting:approve'),
    ('FIREFIGHTING_DEFAULT', 3, 'Custoking Fulfillment', 'firefighting:fulfill')
ON CONFLICT (definition_id, step_order) DO NOTHING;

DO $$
BEGIN
    IF to_regclass('public.workflow_instances') IS NOT NULL THEN
    INSERT INTO workflow_instances
        (id, definition_id, entity_type, entity_id, school_id, current_step,
         status, initiated_by, initiated_at, completed_at, version)
    SELECT id, definition_id, entity_type, entity_id, school_id, current_step,
           status, initiated_by, initiated_at, completed_at, version
    FROM public.workflow_instances
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.workflow_actions') IS NOT NULL THEN
    INSERT INTO workflow_actions
        (id, instance_id, step_order, action, actor_id, actor_email, notes, acted_at)
    SELECT id, instance_id, step_order, action, actor_id, actor_email, notes, acted_at
    FROM public.workflow_actions
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

SELECT setval(pg_get_serial_sequence('workflow_steps', 'id'), COALESCE((SELECT MAX(id) FROM workflow_steps), 1), true);
SELECT setval(pg_get_serial_sequence('workflow_instances', 'id'), COALESCE((SELECT MAX(id) FROM workflow_instances), 1), true);
SELECT setval(pg_get_serial_sequence('workflow_actions', 'id'), COALESCE((SELECT MAX(id) FROM workflow_actions), 1), true);

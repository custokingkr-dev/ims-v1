-- Workflow engine tables

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
    CONSTRAINT fk_wfi_def    FOREIGN KEY (definition_id) REFERENCES workflow_definitions (id),
    CONSTRAINT fk_wfi_school FOREIGN KEY (school_id)     REFERENCES schools (id)
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

CREATE INDEX IF NOT EXISTS idx_wf_instances_entity   ON workflow_instances (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_wf_instances_school   ON workflow_instances (school_id, status);
CREATE INDEX IF NOT EXISTS idx_wf_actions_instance   ON workflow_actions (instance_id);

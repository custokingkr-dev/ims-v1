-- Duplicates must be reviewed explicitly; never delete historical actions to make a migration pass.
CREATE UNIQUE INDEX uk_workflow_entity_school ON workflow_instances(school_id, entity_type, entity_id);
ALTER TABLE workflow_instances ADD CONSTRAINT ck_workflow_state CHECK
 (status IN ('PENDING','IN_PROGRESS','APPROVED','REJECTED','CANCELLED','COMPLETED')
  AND current_step >= 0 AND version >= 0) NOT VALID;
ALTER TABLE workflow_steps ADD CONSTRAINT ck_workflow_step_positive CHECK (step_order > 0) NOT VALID;
ALTER TABLE workflow_actions ADD COLUMN request_correlation_id VARCHAR(128);
ALTER TABLE workflow_actions ADD COLUMN authority VARCHAR(16) NOT NULL DEFAULT 'SERVER';

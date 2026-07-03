-- Group A: workflow_instances already carries a (nullable) school_id set by the app.
ALTER TABLE workflow.workflow_instances ALTER COLUMN school_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_wf_instances_school_entity
    ON workflow.workflow_instances (school_id, entity_type, entity_id);

-- Group B: workflow_actions derives its tenant from its parent instance (same schema).
ALTER TABLE workflow.workflow_actions ADD COLUMN IF NOT EXISTS school_id BIGINT;
UPDATE workflow.workflow_actions a
   SET school_id = i.school_id
  FROM workflow.workflow_instances i
 WHERE i.id = a.instance_id AND a.school_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_wf_actions_school_instance
    ON workflow.workflow_actions (school_id, instance_id);

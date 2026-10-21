-- The worklist shows which school an order belongs to, and operations-service holds no school data. The name is a
-- display snapshot supplied when the tracker is created; the live name is always read from the catalogue for the
-- tracker header. Nullable: it is optional in the create call.
ALTER TABLE workflow.workflow_notebook_trackers ADD COLUMN school_name VARCHAR(200);

-- The worklist's order: open trackers by the active step's deadline. One small index serves it.
CREATE INDEX idx_wis_active_assignee_due
    ON workflow.workflow_instance_steps (assignee_user_id, due_at)
    WHERE status = 'ACTIVE';

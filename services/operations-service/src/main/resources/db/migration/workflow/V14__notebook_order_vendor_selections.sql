ALTER TABLE workflow.workflow_notebook_trackers
    ADD COLUMN vendor_selections JSONB NOT NULL DEFAULT '{}'::jsonb;

-- command_center_actions rows are school-scoped; the platform-NULL branch is superadmin-only
-- and not expected to persist NULL rows. Enforce NOT NULL (fails loudly if any NULL remains —
-- the runbook pre-checks). command_center_feed and reporting_event_inbox stay NULLABLE
-- (NULL = platform-wide) and are intentionally NOT altered here.
ALTER TABLE reporting.command_center_actions ALTER COLUMN school_id SET NOT NULL;

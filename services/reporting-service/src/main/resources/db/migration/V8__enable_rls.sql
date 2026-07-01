-- RLS backstop for command_center_actions (user-action table, always school-scoped).
-- command_center_feed and reporting_event_inbox intentionally remain WITHOUT RLS
-- (NULLABLE school_id = platform-wide projection rows written by contextless event consumers).

ALTER TABLE reporting.command_center_actions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reporting.command_center_actions;
CREATE POLICY tenant_isolation ON reporting.command_center_actions
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

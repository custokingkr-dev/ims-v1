ALTER TABLE reporting.academic_events ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reporting.academic_events;
CREATE POLICY tenant_isolation ON reporting.academic_events
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE reporting.event_student_contributions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reporting.event_student_contributions;
CREATE POLICY tenant_isolation ON reporting.event_student_contributions
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

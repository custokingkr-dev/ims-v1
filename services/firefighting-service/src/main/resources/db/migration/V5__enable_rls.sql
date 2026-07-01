ALTER TABLE firefighting.firefighting_requests ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON firefighting.firefighting_requests;
CREATE POLICY tenant_isolation ON firefighting.firefighting_requests
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE firefighting.ff_quotations ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON firefighting.ff_quotations;
CREATE POLICY tenant_isolation ON firefighting.ff_quotations
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

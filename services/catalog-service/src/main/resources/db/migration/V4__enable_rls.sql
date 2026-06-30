-- RLS backstop on the catalog tenant tables (NOT NULL school_id after V3).
-- ENABLE (not FORCE): owner (appuser) bypasses for Flyway/seed; app_rt is subject.
ALTER TABLE catalog.catalog_orders ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON catalog.catalog_orders;
CREATE POLICY tenant_isolation ON catalog.catalog_orders
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE catalog.annual_plan_items ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON catalog.annual_plan_items;
CREATE POLICY tenant_isolation ON catalog.annual_plan_items
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

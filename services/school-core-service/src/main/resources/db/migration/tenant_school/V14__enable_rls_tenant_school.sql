-- RLS backstop for tenant_school tables that currently rely on app-level filtering only.
-- Reporting fact/dim tables are intentionally excluded from this migration (they are
-- projector-write-only read models owned by reporting-service, not queried directly here).

-- Group A: standard tenant tables keyed on school_id.
DROP POLICY IF EXISTS tenant_isolation ON tenant_school.staff_members;
ALTER TABLE tenant_school.staff_members ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.staff_members
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');

DROP POLICY IF EXISTS tenant_isolation ON tenant_school.school_sections;
ALTER TABLE tenant_school.school_sections ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.school_sections
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');

DROP POLICY IF EXISTS tenant_isolation ON tenant_school.school_module_entitlements;
ALTER TABLE tenant_school.school_module_entitlements ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.school_module_entitlements
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');

-- Group B: schools table, keyed on id (not school_id).
DROP POLICY IF EXISTS tenant_isolation ON tenant_school.schools;
ALTER TABLE tenant_school.schools ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.schools
  USING      (id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');

-- Group C: zone tables — bypass-only (no per-school tenant column semantics apply here).
DROP POLICY IF EXISTS tenant_isolation ON tenant_school.zones;
ALTER TABLE tenant_school.zones ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.zones
  USING      (current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (current_setting('app.bypass_rls', true) = 'on');

DROP POLICY IF EXISTS tenant_isolation ON tenant_school.zone_school_mappings;
ALTER TABLE tenant_school.zone_school_mappings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.zone_school_mappings
  USING      (current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (current_setting('app.bypass_rls', true) = 'on');

DROP POLICY IF EXISTS tenant_isolation ON tenant_school.zone_admin_assignments;
ALTER TABLE tenant_school.zone_admin_assignments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.zone_admin_assignments
  USING      (current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (current_setting('app.bypass_rls', true) = 'on');

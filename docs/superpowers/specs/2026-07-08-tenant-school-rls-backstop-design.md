# tenant_school RLS Backstop — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** school-core-service (tenant_school schema).

Add Row-Level Security backstops to the `tenant_school` tables the isolation audit flagged as app-level-only, mirroring the RLS already proven on this schema's timetable tables (`V5`/`V6`). Defense-in-depth: app-level `WHERE school_id` filtering is present everywhere today; RLS makes a future forgotten filter fail closed instead of leaking.

## Findings (from design research)
- Zone-scoped access is **not** a live risk: no non-superadmin path reads the zone tables today (`resolveSchoolId` 403s a null-school caller; all `/zones/**` endpoints are `requireSuperAdmin`). So a standard `school_id` policy breaks nothing.
- Reporting fact/dim tables are **deferred** (projectors write with no `TenantContext` → `WITH CHECK` would break projection; already a documented intentional exclusion).
- `app_rt` already holds `SELECT/INSERT/UPDATE/DELETE` grants on `tenant_school` (`scripts/create-app-rt-role.sql`) — no new grants needed. `TenantAwareDataSource` (sets `app.current_school_id`/`app.bypass_rls`) is already live in school-core (timetable RLS uses it). Single-release, no two-phase gating.

## Tables + policy shape
New migration `tenant_school/V10__enable_rls_tenant_school.sql` (confirm next free version — sequence is at V9). For each, `ENABLE ROW LEVEL SECURITY` + a `tenant_isolation` policy:

**Group A — standard `school_id` policy** (`staff_members`, `school_sections`, `school_module_entitlements`):
```sql
USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
            OR current_setting('app.bypass_rls', true) = 'on')
WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
            OR current_setting('app.bypass_rls', true) = 'on')
```

**Group B — `schools` (tenant key is the PK `id`, not `school_id`):**
```sql
USING      (id = nullif(current_setting('app.current_school_id', true), '')::bigint
            OR current_setting('app.bypass_rls', true) = 'on')
WITH CHECK (id = nullif(current_setting('app.current_school_id', true), '')::bigint
            OR current_setting('app.bypass_rls', true) = 'on')
```

**Group C — superadmin-only tables (`zones`, `zone_school_mappings`, `zone_admin_assignments`), bypass-only** (no per-row tenant column; enforces nothing new today but fails closed if a future non-superadmin endpoint is added):
```sql
USING      (current_setting('app.bypass_rls', true) = 'on')
WITH CHECK (current_setting('app.bypass_rls', true) = 'on')
```

Document (migration comment) that reporting fact/dim tables are intentionally excluded pending projector context work, mirroring `V8__enable_rls.sql`'s comment style.

## Safety (verified — nothing breaks)
- `staff_members`/`school_sections`/`school_module_entitlements` reads/writes always school-scoped by the time they hit SQL (via `resolveSchoolId`); superadmin lists run under `bypass_rls=on`.
- `schools`: non-superadmin reads are `WHERE id = :schoolId`; superadmin school-list is `requireSuperAdmin` → bypass.
- `ModuleEntitlementClient` (operations→school-core) forwards the caller's `X-Authenticated-*` headers, so `TenantContext` (and the GUC) is correctly set even cross-service.
- Zone tables: only superadmin paths touch them → bypass; Group C changes nothing observable.

## Testing
New `TenantSchoolRlsIntegrationTest` (mirror `FirefightingRlsIntegrationTest`): provision `app_rt` (`NOBYPASSRLS`), seed two schools' rows in `staff_members`/`school_sections`/`school_module_entitlements`/`schools`; assert school-A context sees only A's rows, school-B only B's, superadmin (bypass) sees all, no-context sees nothing, and a cross-tenant `INSERT`/`UPDATE` is blocked by `WITH CHECK`. For Group C, assert a non-bypass (`app_rt`, school context) sees zero zone rows and superadmin/bypass sees all.

## Rollout / rollback
Single release (datasource already live). Pre-cutover sanity: `COUNT(*)` orphan check that every `school_id`/`id` referenced is a real school (FK should guarantee it). Rollback = forward migration `DROP POLICY tenant_isolation ON tenant_school.<t>; ALTER TABLE ... DISABLE ROW LEVEL SECURITY;` per table (per `docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md`).

## Files
`services/school-core-service/src/main/resources/db/migration/tenant_school/V10__enable_rls_tenant_school.sql` (new) + `TenantSchoolRlsIntegrationTest.java` (new).

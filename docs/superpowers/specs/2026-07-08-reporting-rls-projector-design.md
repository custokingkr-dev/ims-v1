# Reporting Fact/Dim RLS + Projector-Bypass — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** platform-service (reporting schema).

Add RLS backstops to the tenant-scoped reporting fact/dim tables the audit deferred (because event-consumer projectors write them with no `TenantContext`, so `WITH CHECK` would break projection). Solve the projector-write problem with a transaction-local bypass.

## Findings (research)
- Projector writes run with `app.current_school_id=''`, `bypass='off'` (no request context) → a `WITH CHECK` policy rejects them → projection silently breaks (rows land in `markFailed`). This is why V8/V12/V13 deferred RLS.
- Every projector upsert repository method is `@Transactional`, so a transaction-local `set_config('app.bypass_rls','on', true)` (the exact `CatalogReadRepository.allowCrossSchoolReadForOperations` precedent) cleanly overrides for the write and reverts at commit — no pool leak.
- Read paths (`ReportingReadRepository`/`ReportingApprovalRepository`) are request-scoped (GUC set by `TenantAwareDataSource`) + already self-scope by `school_id`; the RLS `USING` clause is a redundant backstop that doesn't change correctly-scoped results. Superadmin reads bypass.

## Pattern: transaction-local projector bypass (chosen over a dedicated projector DB role)
Add, as the first statement of each projector upsert method, a transaction-local bypass — ideally via a small shared helper (e.g. `ProjectorRls.allow(jdbc)`) so the "did every projector remember?" audit is one grep. (A dedicated `reporting_projector` role was rejected: net-new infra in every env, and a forgotten-bypass would silently run under a permanent bypass instead of failing loud in the test.)

## Scope — 8 tables get RLS + projector-bypass
`fact_attendance_daily`, `fact_payment`, `fact_fee_assignment`, `fact_catalog_order`, `fact_firefighting_request`, `fact_student_review_item`, `dim_section`, `dim_student`.
Standard policy (verbatim from `reporting/V6__enable_rls.sql`):
```sql
USING      (school_id = nullif(current_setting('app.current_school_id', true),'')::bigint
            OR current_setting('app.bypass_rls', true)='on')
WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true),'')::bigint
            OR current_setting('app.bypass_rls', true)='on')
```
Projector upsert methods getting the bypass line (8): `AttendanceFactReadRepository.upsert`, `FeeFactReadRepository.upsertFeeAssignment` + `upsertPayment`, `CatalogFactReadRepository.upsert`, `FirefightingFactReadRepository.upsert`, `StudentReviewFactReadRepository.upsert`, `DimensionProjectionRepository.upsertSection` + `upsertStudent`. (Confirm exact method names when implementing.)

**Excluded (global-by-design):** `dim_school` (keyed by `id`=the school, cross-tenant directory), `dim_academic_year` (no tenant col), `command_center_feed` (platform-wide feed, V8-documented), `reporting_event_inbox` (event transport), `billing_invoice_read` (platform-admin invoice surface). `academic_events`/`event_student_contributions`/`command_center_actions` already have RLS.

## Migration + rollout
Single migration `reporting/V22__enable_rls_facts_dims.sql` (confirm next free version) — the RLS + policy for the 8 tables. Ship the migration + the 8 projector-bypass lines + the test in ONE change so RLS is never on before the bypass exists.

## Testing
New `ReportingFactRlsIntegrationTest` (mirror `ReportingRlsIntegrationTest`/`FirefightingRlsIntegrationTest`): `app_rt` NOBYPASSRLS + `TenantAwareDataSource`. Per fact table: (1) isolation read (school-10 ctx sees only 10's rows, 20 only 20's, superadmin all, no-context none); (2) `WITH CHECK` blocks a cross-tenant insert; (3) **critical** — with NO `TenantContext` (projector-style), calling the actual repository upsert SUCCEEDS (proves the transaction-local bypass; goes RED if a projector forgets the bypass line). One (3)-assertion per projector write method.

## Risks
- A projector missing the bypass → silent projection breakage (RLS rejects its writes → `markFailed`). Mitigated by test #3 covering every write method + the shared helper. A NEW future fact table+projector must add both the policy and the bypass (document next to the policy).
- Nullable `school_id`: a malformed `school_id=NULL` fact row is invisible to scoped reads (only bypass sees it) — acceptable for a backstop; note it.

## Deferred (own efforts, documented)
- **identity RLS** — infeasible until identity gets a `TenantAwareDataSource` (it has none) + a nullable/global-assignment policy design. Standalone project.
- **notification RLS** — only `notification_broadcasts`/`notification_logs` have `school_id` (both request-scoped/superadmin-gated, no projector); low value; later separate request-scoped pass.

## Files
`reporting/V22__enable_rls_facts_dims.sql` (new), the 8 projector `*FactReadRepository`/`DimensionProjectionRepository` upsert methods (+ a `ProjectorRls` helper), `ReportingFactRlsIntegrationTest.java` (new).

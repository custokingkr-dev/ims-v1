# RLS Rollout Runbook — Custoking IMS

**Status:** Phase 1 complete (student, attendance, reporting — clean NOT-NULL tables)
**Branch:** `phase1-rls`
**Date:** 2026-06-30

---

## 1. Overview

PostgreSQL Row-Level Security (RLS) has been enabled as a database-enforced tenant-isolation backstop on the cleanly-scoped tables (all have NOT-NULL `school_id`) in three services:

| Service | Schema | Tables covered | Migration |
|---|---|---|---|
| student-service | `student` | `students`, `student_review_campaigns`, `student_review_items` | `V4__enable_rls.sql` |
| attendance-service | `attendance` | `attendance_student_records` | `V3__enable_rls.sql` |
| reporting-service | `reporting` | `academic_events`, `event_student_contributions` | `V6__enable_rls.sql` |

**Out of scope for Phase 1:** any table with a nullable `school_id`, cross-schema derived tables, or fee-domain tables. These follow after Task 1.4 denormalizes `school_id` (backfill + NOT NULL). Do not apply the two-phase rollout described here to those tables until that work lands.

---

## 2. GUC Contract

Every connection used by the application runtime MUST set two PostgreSQL session-level GUCs on each connection borrow (i.e., before any query is issued on that connection):

| GUC | Type | Meaning |
|---|---|---|
| `app.current_school_id` | text (coerced to bigint by policy) | The authenticated school_id for this request. Empty string (`''`) or missing → treated as NULL → no rows visible. |
| `app.bypass_rls` | text | Set to `'on'` for superadmin requests only. Any other value or missing = normal tenant isolation. |

These are set by `TenantAwareDataSource` on every `getConnection()` call, sourced from `TenantContext` (populated by the request filter from the gateway-injected `X-Authenticated-*` headers).

**Policy form (all six tables):**
```sql
USING (
    school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
    OR current_setting('app.bypass_rls', true) = 'on'
)
WITH CHECK (
    school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
    OR current_setting('app.bypass_rls', true) = 'on'
)
```

`nullif(..., '')` ensures that an empty-string GUC (missing context) compares as NULL rather than failing the cast, which causes the USING clause to evaluate to NULL (false) → zero rows returned. This is intentional fail-closed behavior.

---

## 3. Role Architecture

| Role | Owns schemas | Bypasses RLS | Used for |
|---|---|---|---|
| `appuser` | Yes (DDL owner) | Yes — RLS uses `ENABLE` not `FORCE`, so owners bypass automatically | Flyway migrations, DBA operations |
| `app_rt` | No | No — `NOBYPASSRLS`, not a `cloudsqlsuperuser` member | All application runtime traffic |

**Critical:** `ENABLE ROW LEVEL SECURITY` (not `FORCE`) is used on every table. This means the table owner (`appuser`) is transparently exempt from the policy. Flyway connects as `appuser` and therefore always sees all rows, regardless of GUC state — this is intentional so migrations never break on empty-context seeds.

`app_rt` has DML grants (`SELECT`, `INSERT`, `UPDATE`, `DELETE`) + `USAGE` on the affected schemas, but no DDL and no `BYPASSRLS` privilege.

---

## 4. Pre-Cutover Checklist

Before shipping either release to production, verify the following:

### 4.1 Confirm `app_rt` privilege posture

Run the privilege audit against the Cloud SQL instance:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\audit-app-rt-privileges.ps1
```

This script asserts:
- `app_rt` has `NOBYPASSRLS`
- `app_rt` is NOT a member of `cloudsqlsuperuser`
- `app_rt` has DML (`SELECT`, `INSERT`, `UPDATE`, `DELETE`) on all domain schemas
- `app_rt` has `USAGE` on all domain schemas
- `appuser` retains owner / DDL rights

**If `app_rt` is a `cloudsqlsuperuser` member, RLS will be bypassed silently.** Resolve before proceeding (see ops commits `092ffce` and `330fe2a` for the remediation steps).

### 4.2 Confirm `TenantAwareDataSource` is wired in all three services

Check that each service's Spring context exposes a `TenantDataSourceConfig` `BeanPostProcessor` that wraps the primary `DataSource` bean with `TenantAwareDataSource`. Verify via the `/actuator/beans` endpoint (if actuator is enabled) or by inspecting the application logs for `[TenantAwareDataSource] Wrapping DataSource bean`.

### 4.3 Confirm the GUC is set on every request

In staging, enable `log_min_duration_statement = 0` briefly and verify that every query on an RLS-enabled table is preceded by a `SET LOCAL app.current_school_id = '...'` statement. A missing GUC set means the `TenantAwareDataSource` is not wrapping that connection pool.

### 4.4 Two-role invariant (MUST be verified before every production deploy)

Correct RLS enforcement requires **both** of the following env vars to be set on each Cloud Run service, and they **must be distinct**:

| Env var | Required value | Role |
|---|---|---|
| `SPRING_DATASOURCE_USERNAME` | `app_rt` | Runtime DB user — subject to RLS policies |
| `FLYWAY_USERNAME` | `appuser` | DDL owner — bypasses RLS, runs schema migrations |

**Why both must be distinct:**

- If only `SPRING_DATASOURCE_USERNAME=app_rt` is set without `FLYWAY_USERNAME=appuser`, Spring Boot defaults Flyway to the datasource URL/username (`app_rt`). Flyway will then attempt DDL (CREATE TABLE, ALTER TABLE, etc.) as `app_rt`, which lacks DDL privileges → **migration failure at startup**.
- If `SPRING_DATASOURCE_USERNAME` is set to `appuser` or `postgres` (the table owner / a superuser), the runtime connects as the RLS-exempt owner → **RLS is silently disabled; all tenant rows are visible to all tenants**. Starting with this branch, the `RuntimeDbRoleGuard` (an `ApplicationReadyEvent` listener in each service's `security` package) catches this misconfiguration at startup: it throws `IllegalStateException` in the `prod` profile, and logs a WARN in other profiles.

**Checklist:** before shipping Release 2 (§5), confirm in Cloud Run service configuration:
- `SPRING_DATASOURCE_USERNAME` = `app_rt`
- `FLYWAY_USERNAME` = `appuser` (explicitly set; do NOT rely on the default)
- The two values are different

---

## 5. Two-Phase Production Rollout (Per Service)

**THE HAZARD:** On this branch, `TenantAwareDataSource` and the `V…__enable_rls.sql` migration are committed together per service. If the RLS migration runs on Cloud SQL **before** all live instances have the updated `TenantAwareDataSource` code deployed, any old instance (without the GUC-setting wrapper) will issue queries against an RLS-enabled table with no `app.current_school_id` set → the USING clause evaluates to NULL → **zero rows returned for all tenant requests**. This is a silent data-loss bug visible to end users as empty screens, not an error.

**Safe prod sequence: split into two Cloud Run revisions per service.**

### Release 1 — Deploy `TenantAwareDataSource` only (no schema migration)

1. Build and deploy the service image that includes `TenantAwareDataSource` + `TenantDataSourceConfig`.
   - The `V…__enable_rls.sql` migration file is present in the JAR but has NOT run yet (Flyway will apply it at startup of Release 2, not Release 1, because RLS is not yet enabled on the tables).
   - Wait for a **full rolling replacement**: all Cloud Run instances for this service are running the new image (no old pods remain). Cloud Run's rolling deploy completes when the new revision receives 100% traffic.
2. Smoke-test: confirm the service returns data normally (RLS is not yet active, so the GUC is set harmlessly on every connection).

### Release 2 — Ship the RLS migration

1. Trigger a redeployment (or a Flyway-only migration run) where Flyway connects as `appuser` and applies `V…__enable_rls.sql`.
   - Because ALL instances are already running `TenantAwareDataSource`, every subsequent application query will correctly set `app.current_school_id` before hitting the now-RLS-enabled table.
2. Smoke-test: confirm tenant A sees only tenant A rows, and that a superadmin session sees all rows.

**If your deploy pipeline applies Flyway at container startup:** the two-phase split requires deploying the Release 1 image and waiting for the rollout to fully complete before triggering Release 2. You can enforce this with a deployment gate (e.g., `gcloud run services describe` until `latestReadyRevision == latestCreatedRevision`) between the two Cloud Build steps.

**Rollout order:** student-service → attendance-service → reporting-service (or in parallel if the pipeline supports it; services are independent).

---

## 6. Rollback

RLS rollback is a **forward-only migration** (do not modify already-applied migration files). Add the next migration version in the affected service's history.

### student-service (`V5__disable_rls.sql`)

```sql
DROP POLICY IF EXISTS tenant_isolation ON student.students;
ALTER TABLE student.students DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON student.student_review_campaigns;
ALTER TABLE student.student_review_campaigns DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON student.student_review_items;
ALTER TABLE student.student_review_items DISABLE ROW LEVEL SECURITY;
```

### attendance-service (`V4__disable_rls.sql`)

```sql
DROP POLICY IF EXISTS tenant_isolation ON attendance.attendance_student_records;
ALTER TABLE attendance.attendance_student_records DISABLE ROW LEVEL SECURITY;
```

### reporting-service (`V7__disable_rls.sql`)

```sql
DROP POLICY IF EXISTS tenant_isolation ON reporting.academic_events;
ALTER TABLE reporting.academic_events DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON reporting.event_student_contributions;
ALTER TABLE reporting.event_student_contributions DISABLE ROW LEVEL SECURITY;
```

**After disabling RLS:** `app_rt` retains its DML grants and `TenantAwareDataSource` still sets the GUCs (harmlessly, since no policy reads them). Application-layer tenant filtering remains in effect. RLS is the **backstop**, not the sole isolation mechanism — disabling it does not revert to zero isolation.

**Do NOT drop `app_rt` or remove `TenantAwareDataSource`** during rollback — these are required for re-enabling RLS (Release 2 above) and for the broader MT-P0-1 app-layer isolation.

---

## 7. Verification (CI and Pre-Prod)

Each service ships an RLS integration test that proves isolation at the database level, connecting as `app_rt` (not the owner):

| Service | Test class |
|---|---|
| student-service | `com.custoking.ims.studentservice.security.StudentRlsIntegrationTest` |
| attendance-service | `com.custoking.ims.attendanceservice.security.AttendanceRlsIntegrationTest` |
| reporting-service | `com.custoking.ims.reportingservice.security.ReportingRlsIntegrationTest` |

**Test cases per service:**
- `schoolA_seesOnlyItsRows` — tenant A context → only tenant A rows visible
- `schoolB_seesOnlyItsRows` — tenant B context → only tenant B rows visible
- `superadmin_seesAll` — superadmin context (`bypass_rls=on`) → all rows visible
- `noContext_seesNothing` — no `TenantContext` set → 0 rows (fail-closed)
- `withCheck_blocksCrossTenantInsert` — tenant A context, insert with school_id=B → `ERROR: row-level security`

Tests use Testcontainers (Docker required). They are automatically skipped if Docker is not available (`Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable())`), so `mvn test` is always safe to run in environments without Docker — no false failures.

**Run all three services:**
```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
powershell -ExecutionPolicy Bypass -File scripts\invoke-microservice-tests.ps1 -Services student-service,attendance-service,reporting-service
```

All three must be green including the `*RlsIntegrationTest` classes before shipping Release 2 to production.

---

## 8. Scope Boundary — What Is NOT Covered

Phase 1 covers only the six tables listed in §1. The following are explicitly deferred until Task 1.4 (which will backfill and NOT-NULL-ify nullable `school_id` columns):

- Any table in `fee`, `catalog`, `billing`, `workflow`, `firefighting`, `notification`, `audit`, `identity`, or `tenant_school` schemas.
- Any table with a nullable `school_id` column (cross-schema derived rows, association tables that derive tenant from a parent FK, etc.).
- Any table in `student`/`attendance`/`reporting` schemas not listed in §1.

Application-layer tenant enforcement (MT-P0-1) and the `app_rt` privilege model remain the sole isolation mechanism for deferred tables until Task 1.4 is complete.

---

## 9. Related Runbooks and Scripts

- `docs/MICROSERVICE-ROLLBACK-RUNBOOK.md` — general service rollback playbook
- `docs/MICROSERVICE-OBSERVABILITY-RUNBOOK.md` — structured-log and alert playbook
- `scripts/audit-app-rt-privileges.ps1` — verify `app_rt` privilege posture (Phase 1.1 gate)
- `scripts/verify-microservice-migration.ps1 -RunDbAudit` — full boundary + DB audit
- `docs/ARCHITECTURE_REVIEW.md` § MT-P0-2 — principal review note on RLS backstop

---

## 10. RLS Extension to Task-1.4 Tables (2026-07-01)

**Branch:** `phase1-rls-extension`

Following the Task 1.4 NOT NULL denormalization (branch `phase1-tenant-keys`), RLS has been extended to the 10 newly-clean tenant-scoped tables across 6 services. The GUC contract, role architecture, and policy form are identical to Phase 1 (§2–§3 above).

### 10.1 In-Scope Tables

| Service | Schema | Tables covered | Migration |
|---|---|---|---|
| catalog-service | `catalog` | `catalog_orders`, `annual_plan_items` | `V4__enable_rls.sql` |
| firefighting-service | `firefighting` | `firefighting_requests`, `ff_quotations` | `V5__enable_rls.sql` |
| workflow-service | `workflow` | `workflow_instances`, `workflow_actions` | `V5__enable_rls.sql` |
| fee-service | `fee` | `fee_assignments`, `payment_records` | `V7__enable_rls.sql` |
| attendance-service | `attendance` | `attendance_daily` | `V6__enable_rls.sql` |
| reporting-service | `reporting` | `command_center_actions` | `V8__enable_rls.sql` |

**Services that NEWLY received `TenantAwareDataSource` on this branch:** catalog, firefighting, workflow, fee.
`attendance` and `reporting` already had `TenantAwareDataSource` from the Phase 1.3 backstop work; their datasource configuration was not modified here.

**Intentionally excluded (no RLS):**
- `reporting.command_center_feed` and `reporting.reporting_event_inbox` — `school_id` is nullable (NULL = platform-wide projection); these tables are written by contextless Pub/Sub consumers and scheduled jobs. Enabling RLS would break those writers.
- `fee.fee_bands` and `fee.fee_items` — global catalog rows, no per-tenant row scope.

**Reporting gate finding:** `command_center_actions` has zero runtime INSERTs in production (only request-scoped UPDATEs + the V1 migration seed copy). RLS is safe on it; no table had to be deferred.

### 10.2 Two-Phase Rollout

The same hazard as Phase 1.3 applies: if an `enable_rls` migration runs before all live instances have `TenantAwareDataSource` deployed, those old instances issue queries with no GUC set → USING clause evaluates to NULL → **zero rows returned for all tenant requests** (silent data-loss, not an error).

**Safe prod sequence — four newly-datasourced services (catalog, firefighting, workflow, fee):**

#### Release 1 — Deploy `TenantAwareDataSource` to the four new services (no schema migration)

1. Build and deploy the updated image for each of catalog-service, firefighting-service, workflow-service, and fee-service. The `V…__enable_rls.sql` migration file is present in the JAR but Flyway will not run it yet (RLS is not yet enabled on those tables).
2. Wait for a **full rolling replacement** (Cloud Run: new revision reaches 100% traffic, no old instances remain).
3. Smoke-test each service — data must be returned normally (GUC is set harmlessly; RLS not yet active).

attendance-service and reporting-service already have `TenantAwareDataSource` from the Phase 1.3 deploy; they may proceed directly to Release 2.

#### Release 2 — Ship the `enable_rls` migrations

1. Trigger redeployment (or a Flyway-only migration run) for each service. Flyway connects as `appuser` and applies `V…__enable_rls.sql`.
2. Because ALL instances are already running `TenantAwareDataSource`, every subsequent `app_rt` query correctly sets `app.current_school_id` before hitting the now-RLS-enabled table.
3. Smoke-test: confirm tenant A sees only tenant A rows; superadmin sees all.

**Never ship a service's `enable_rls` migration before its datasource Release 1 is fully rolled out.**

### 10.3 Pre-Cutover Orphan / Mis-Scope Check (carry-forward #2)

Before running each service's `enable_rls` migration, verify that no row has a `school_id` that disagrees with its parent's owning school. A mis-scoped row becomes visible to the **wrong tenant** under RLS.

Run the following queries against the production Cloud SQL instance (each MUST return 0; investigate and repair any non-zero count before proceeding):

```sql
-- attendance_daily: school_id must match the section's owning school
SELECT count(*) FROM attendance.attendance_daily ad
  LEFT JOIN tenant_school.school_sections ss ON ss.id = ad.section_id
 WHERE ad.school_id IS DISTINCT FROM ss.school_id;

-- fee_assignments: school_id must match the assigned student's school
SELECT count(*) FROM fee.fee_assignments fa
  LEFT JOIN student.students s ON s.id = fa.student_id
 WHERE fa.school_id IS DISTINCT FROM s.school_id;

-- payment_records: school_id must match the paying student's school
SELECT count(*) FROM fee.payment_records pr
  LEFT JOIN student.students s ON s.id = pr.student_id
 WHERE pr.school_id IS DISTINCT FROM s.school_id;
```

`catalog_orders`, `annual_plan_items`, `firefighting_requests`, `ff_quotations`, `workflow_instances`, `workflow_actions`, and `command_center_actions` derive `school_id` from same-schema parents or directly from the creating request context; the orphan/mis-scope risk is lower there, but a spot-check against the parent table's `school_id` column is still recommended before enabling RLS.

### 10.4 Rollback

RLS rollback is a **forward-only migration** (do not modify already-applied migration files). Add the next migration version in the affected service's history.

#### catalog-service (`V5__disable_rls.sql`)

```sql
DROP POLICY IF EXISTS tenant_isolation ON catalog.catalog_orders;
ALTER TABLE catalog.catalog_orders DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON catalog.annual_plan_items;
ALTER TABLE catalog.annual_plan_items DISABLE ROW LEVEL SECURITY;
```

#### firefighting-service (`V6__disable_rls.sql`)

```sql
DROP POLICY IF EXISTS tenant_isolation ON firefighting.firefighting_requests;
ALTER TABLE firefighting.firefighting_requests DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON firefighting.ff_quotations;
ALTER TABLE firefighting.ff_quotations DISABLE ROW LEVEL SECURITY;
```

#### workflow-service (`V6__disable_rls.sql`)

```sql
DROP POLICY IF EXISTS tenant_isolation ON workflow.workflow_instances;
ALTER TABLE workflow.workflow_instances DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON workflow.workflow_actions;
ALTER TABLE workflow.workflow_actions DISABLE ROW LEVEL SECURITY;
```

#### fee-service (`V8__disable_rls.sql`)

```sql
DROP POLICY IF EXISTS tenant_isolation ON fee.fee_assignments;
ALTER TABLE fee.fee_assignments DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON fee.payment_records;
ALTER TABLE fee.payment_records DISABLE ROW LEVEL SECURITY;
```

#### attendance-service (`V7__disable_rls.sql`)

```sql
DROP POLICY IF EXISTS tenant_isolation ON attendance.attendance_daily;
ALTER TABLE attendance.attendance_daily DISABLE ROW LEVEL SECURITY;
```

#### reporting-service (`V9__disable_rls.sql`)

```sql
DROP POLICY IF EXISTS tenant_isolation ON reporting.command_center_actions;
ALTER TABLE reporting.command_center_actions DISABLE ROW LEVEL SECURITY;
```

**After disabling RLS:** `app_rt` retains its DML grants and `TenantAwareDataSource` continues to set GUCs harmlessly. Application-layer tenant filtering (MT-P0-1) remains in effect. RLS is the backstop — disabling it does not remove all isolation.

### 10.5 Verification

Each of the 6 services ships a new `*RlsIntegrationTest` class (Testcontainers, runs as `app_rt`) proving the same five isolation assertions as Phase 1.3:

| Service | Test class |
|---|---|
| catalog-service | `com.custoking.ims.catalogservice.security.CatalogRlsIntegrationTest` |
| firefighting-service | `com.custoking.ims.firefightingservice.security.FirefightingRlsIntegrationTest` |
| workflow-service | `com.custoking.ims.workflowservice.security.WorkflowRlsIntegrationTest` |
| fee-service | `com.custoking.ims.feeservice.security.FeeRlsIntegrationTest` |
| attendance-service | `com.custoking.ims.attendanceservice.security.AttendanceRlsIntegrationTest` |
| reporting-service | `com.custoking.ims.reportingservice.security.ReportingCommandCenterRlsIntegrationTest` |

Tests are automatically skipped if Docker is not available (`Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable())`).

**Run all 6 services:**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
powershell -ExecutionPolicy Bypass -File scripts\invoke-microservice-tests.ps1 -Services catalog-service,firefighting-service,workflow-service,fee-service,attendance-service,reporting-service
```

All six must be green including the new `*RlsIntegrationTest` classes before shipping Release 2 to production.

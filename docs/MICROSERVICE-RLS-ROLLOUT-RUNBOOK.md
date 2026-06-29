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

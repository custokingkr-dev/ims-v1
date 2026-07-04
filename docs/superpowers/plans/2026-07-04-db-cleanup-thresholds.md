# DB Cleanup + Scaling Thresholds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Do the right-sized subset of Phase 3 Task 3.2 — remove two verified-dead `public.*` orphan tables, audit index/constraint correctness (fix only genuine gaps), and document DB scaling thresholds — deferring partitioning and speculative index-tuning as premature at current prod scale.

**Architecture:** Two implementer tasks produce a committed drop-script + a thresholds doc + (only if a genuine gap is found) a forward Flyway migration; a third controller task runs the drop against prod via the established Cloud Run psql-job pattern (unowned `public` schema → not a per-service migration) and verifies. Prod is barely populated (biggest table 22 rows / 144 kB), so this is cleanup + documentation, not perf engineering.

**Tech Stack:** PostgreSQL (Cloud SQL `custoking_ims_v1`), Flyway (per-service), Maven/Testcontainers (if a migration is added), Cloud Run one-off job (`postgres:16-alpine`), Markdown docs.

**Spec:** `docs/superpowers/specs/2026-07-04-db-cleanup-thresholds-design.md`

## Global Constraints

- **Do NOT partition any table, and do NOT add speculative performance indexes** — premature at current scale (measured: biggest table `attendance_student_records` = 22 rows / 144 kB). Only *correctness* index/constraint fixes are in scope.
- **The two orphan tables to drop are exactly `public.flyway_schema_history` and `public.outbox_events`** — both verified unreferenced by any code/config, and no service uses the `public` schema. Do NOT touch any per-service `flyway_schema_history` inside an owned schema (those are live).
- **Drop is prod-only, `IF EXISTS`, content-captured-to-log first** (safety record), and controller-operated via a Cloud Run psql job — NOT a Flyway migration.
- **Behavior-preserving.** If a correctness migration is added, it goes in the owning service and its Testcontainers suite must stay green at baseline count. Never edit an already-applied migration.
- **Branch:** `phase3-db-cleanup` (already created off `main`; the spec is committed there).
- **Maven on Windows (only if a migration is added):** `$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"` then `.\mvnw.cmd -f services\<svc>\pom.xml test`.
- **Cloud Run drop-job params:** image `postgres:16-alpine`, `--network=default --subnet=default --vpc-egress=all-traffic`, SA `755376288593-compute@developer.gserviceaccount.com`, secrets `/sql/run.sql=<secret>:latest` + `PGPASSWORD=db-password:latest`, env `PGHOST=10.116.0.3 PGUSER=appuser PGDATABASE=custoking_ims_v1`.

## File Structure

- `scripts/drop-dead-public-tables.sql` — **create** (Task 2): the tracked prod cleanup SQL (safety-select + DROP IF EXISTS + verify).
- `docs/DB-SCALING-THRESHOLDS.md` — **create** (Task 2): partition/index growth thresholds + the current measurement baseline + how to re-measure, incorporating Task 1's audit findings.
- `services/<owning-svc>/src/main/resources/db/migration/<schema>/V<next>__<name>.sql` — **create only if** Task 1 finds a genuine correctness gap (likely none).
- No application source changes.

---

### Task 1: Index & constraint correctness audit (lean; likely near-no-op)

**Files:** read-only analysis of `services/school-core-service/src/main/java/.../persistence/*ReadRepository.java` and `.../persistence/*Repository.java` (attendance/student/fee/catalog), `services/platform-service/.../persistence/Reporting*Repository.java`, and the existing `CREATE INDEX`/constraint DDL under each service's `db/migration/`. **Create** a migration ONLY if a genuine gap is found. Produces a findings summary consumed by Task 2's doc.

**Interfaces:**
- Produces: a short **audit findings summary** (plain text in the task report) — for each hot read path: the query's filter/sort/join columns, the index that serves it (or "gap"), and the verdict (covered / migration-added / intentionally-deferred). Task 2 pastes this into `docs/DB-SCALING-THRESHOLDS.md`.

- [ ] **Step 1: Enumerate the hot read-path query shapes**

```
cd /d/Projects/ims-v1
```
Grep the domain read repositories for their query shapes (native `@Query` and derived queries):
```
grep -rnE "@Query|nativeQuery|WHERE|ORDER BY|findBy|And" services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence 2>/dev/null | head -80
```
For each frequent read (list/detail endpoints, dashboard aggregates), note the columns used in `WHERE` / `ORDER BY` / join.

- [ ] **Step 2: List existing indexes + constraints per schema**

```
grep -rhnE "CREATE (UNIQUE )?INDEX|CONSTRAINT|PRIMARY KEY|FOREIGN KEY|NOT NULL" services/school-core-service/src/main/resources/db/migration services/platform-service/src/main/resources/db/migration 2>/dev/null | grep -viE "flyway" | head -120
```
Task 1.4 already added tenant-leading composites (e.g. `idx_attendance_daily_school_date (school_id, attendance_date, academic_year_id)`, student/section/date indexes). Confirm each hot query from Step 1 is served by an index whose leading columns match its filter (tenant-leading `school_id` first), and that intra-schema FKs/uniques that protect integrity exist.

- [ ] **Step 3: Decide — genuine gap or not**

- If every hot query is served by a correctly-ordered index and integrity constraints are present → **no migration**; record "no gap found" with the per-path reasoning. This is the expected outcome.
- If a genuine *correctness* gap exists (an index whose column order does not match a frequent filter/sort, or a clearly-missing intra-schema FK/UNIQUE/NOT NULL) → add ONE forward Flyway migration in the owning service: next version in that schema's sequence, `CREATE INDEX IF NOT EXISTS`/`ALTER TABLE … ADD CONSTRAINT` only for the specific gap. Do NOT add speculative perf indexes.

- [ ] **Step 4: If (and only if) a migration was added, validate it**

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\<owning-svc>\pom.xml clean test
```
Expected: BUILD SUCCESS at the service's baseline test count (the Testcontainers suite applies the new migration on a real Postgres). If no migration was added, skip this step.

- [ ] **Step 5: Commit (only if a migration was added; else no commit, findings go in the report)**

```bash
git add services/<owning-svc>/src/main/resources/db/migration
git commit -m "perf(db): add <index/constraint> correctness fix for <path> (Phase 3 Task 3.2)"
```

---

### Task 2: Drop script + scaling-thresholds doc

**Files:**
- Create: `scripts/drop-dead-public-tables.sql`
- Create: `docs/DB-SCALING-THRESHOLDS.md`

**Interfaces:**
- Consumes: Task 1's audit findings summary (paste into the doc's "Index audit" section).
- Produces: the committed drop SQL that Task 3 (controller) runs against prod.

- [ ] **Step 1: Write `scripts/drop-dead-public-tables.sql`**

```sql
-- One-off prod cleanup: drop two verified-dead monolith-era orphan tables in the
-- unowned `public` schema. Both confirmed unreferenced by any service code/config;
-- no service uses the public schema. Idempotent (IF EXISTS) — a no-op on any DB that
-- never had them. Run via the Cloud Run psql-job pattern (see the plan / spec).
\pset pager off

\echo '== PRE-DROP SAFETY RECORD: public.flyway_schema_history (old monolith Flyway history) =='
SELECT count(*) AS row_count FROM public.flyway_schema_history;
SELECT * FROM public.flyway_schema_history ORDER BY 1;

\echo '== PRE-DROP SAFETY RECORD: public.outbox_events (monolith leftover; Task 3.1 outbox will be per-service, NOT this) =='
SELECT count(*) AS row_count FROM public.outbox_events;
SELECT * FROM public.outbox_events;

\echo '== DROP =='
DROP TABLE IF EXISTS public.outbox_events;
DROP TABLE IF EXISTS public.flyway_schema_history CASCADE;

\echo '== POST-DROP: remaining public base tables (the two above must be gone) =='
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY 1;
```
(Runs with `psql -v ON_ERROR_STOP=0`, so if a table is already absent the SELECT logs an error and the run continues to the idempotent DROP.)

- [ ] **Step 2: Write `docs/DB-SCALING-THRESHOLDS.md`**

Create the doc with these sections (fill the "Index audit" section from Task 1's findings):
- **Purpose & current baseline:** why partitioning/perf-index-tuning are deferred; the measured snapshot table (attendance_student_records 22 rows/144 kB, attendance_daily 9 rows, students 10, catalog_orders 19, fee_* 5) as of 2026-07-04.
- **Partitioning threshold:** target = `attendance.attendance_student_records` (student×day), NOT `attendance_daily`; trigger ≈ **1–5M rows** or when index-backed hot queries degrade. Required restructure to note: the partition key (`attendance_date` or `academic_year_id`) must be part of the PK and every UNIQUE constraint; `attendance_daily`'s PK is currently `id VARCHAR` and `attendance_student_records` FKs to `attendance_daily(id)`, so partitioning needs a PK/child-FK rework; Postgres can't convert a table in place, so it's a create-partitioned-table-and-migrate operation (online). Range strategy: by academic year (or month).
- **Index-tuning threshold:** worthwhile once tables exceed ~10k–100k rows and the planner starts choosing index scans; re-derive from `EXPLAIN (ANALYZE, BUFFERS)` on the actual hot queries then. Note Task 1.4 already added tenant-leading composites.
- **Index audit (2026-07-04):** paste Task 1's per-path findings (covered / gap-fixed / deferred).
- **How to re-measure:** the read-only volume/size diagnostic (row counts + `pg_total_relation_size`) and how to run it via a Cloud Run psql job (reference `scripts/drop-dead-public-tables.sql`'s job pattern).

- [ ] **Step 3: Commit**

```bash
git add scripts/drop-dead-public-tables.sql docs/DB-SCALING-THRESHOLDS.md
git commit -m "chore(db): dead-orphan drop script + scaling-thresholds doc (Phase 3 Task 3.2)"
```

---

### Task 3: Run the drop in prod + verify (controller)

**Owner:** controller (prod DDL — like prior prod DB ops). Runs AFTER Tasks 1–2 are reviewed and the PR is merged to `main`, so the script is tracked. No implementer.

- [ ] **Step 1: Create the secret from the committed script + a one-off job, and run it**

```bash
cd /d/Projects/ims-v1
SA="755376288593-compute@developer.gserviceaccount.com"
gcloud secrets create dead-drop-sql --data-file=scripts/drop-dead-public-tables.sql 2>&1 | tail -1
gcloud secrets add-iam-policy-binding dead-drop-sql --member="serviceAccount:$SA" --role="roles/secretmanager.secretAccessor" 2>&1 | tail -1
gcloud run jobs create ims-dead-drop --image=postgres:16-alpine --region=asia-south2 \
  --network=default --subnet=default --vpc-egress=all-traffic --service-account="$SA" \
  --set-secrets="/sql/run.sql=dead-drop-sql:latest,PGPASSWORD=db-password:latest" \
  --set-env-vars="PGHOST=10.116.0.3,PGUSER=appuser,PGDATABASE=custoking_ims_v1,PGCONNECT_TIMEOUT=15" \
  --command="sh" --args="-c,psql -v ON_ERROR_STOP=0 -f /sql/run.sql" --max-retries=0 --task-timeout=120s 2>&1 | tail -1
gcloud run jobs execute ims-dead-drop --region asia-south2 --wait 2>&1 | tail -2
```

- [ ] **Step 2: Read the job log — confirm the safety record + the post-drop verification**

```bash
gcloud logging read 'resource.type="cloud_run_job" AND resource.labels.job_name="ims-dead-drop"' --limit=80 --format="value(textPayload)" --freshness=8m | grep -vE "^\s*$" | tac | head -60
```
Expected: the PRE-DROP row counts + contents are logged (safety record), and the POST-DROP "remaining public base tables" list does **not** include `flyway_schema_history` or `outbox_events`.

- [ ] **Step 3: Confirm prod is unaffected (nothing referenced these tables)**

```bash
GW="https://custoking-api-gateway-xkv7oenbna-em.a.run.app"
TOKEN=$(curl -s -X POST "$GW/api/v1/auth/login" -H "Content-Type: application/json" -d '{"email":"e2e-superadmin@local.test","password":"password"}' | python -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")
for p in "/api/v1/schools" "/api/v1/students?schoolId=4" "/api/v1/audit-logs"; do
  echo "$p -> $(curl -s -o /dev/null -w '%{http_code}' "$GW$p" -H "Authorization: Bearer $TOKEN")"
done
```
Expected: all `200` (the fleet never used the dropped tables; this just confirms no surprise).

- [ ] **Step 4: Clean up the one-off job + secret**

```bash
gcloud run jobs delete ims-dead-drop --region asia-south2 --quiet 2>&1 | tail -1
gcloud secrets delete dead-drop-sql --quiet 2>&1 | tail -1
```

- [ ] **Step 5: Record completion (controller)**

Update the prod-state memory: Phase 3 Task 3.2 done (right-sized) — 2 dead `public.*` orphans dropped, scaling thresholds documented (`docs/DB-SCALING-THRESHOLDS.md`), partitioning deferred with a threshold. Remaining Phase 3: Task 3.1 (reporting outbox) + Task 3.3 (connection pooling). Do NOT start those — separate specs/plans.

---

## Self-Review

**Spec coverage:**
- Component 1 (drop 2 dead public.* orphans, content-captured-first, Cloud Run job) → Task 2 (script) + Task 3 (controller run). ✓
- Component 2 (lean index/constraint correctness audit, migration only if genuine gap) → Task 1. ✓
- Component 3 (`docs/DB-SCALING-THRESHOLDS.md` with thresholds + baseline + audit findings + re-measure) → Task 2 Step 2. ✓
- Constraints: no partitioning, no speculative indexes, exact 2 tables, prod-only IF-EXISTS drop, per-service migration only for a real gap → Global Constraints + task steps. ✓
- Non-goals: partitioning, Task 3.1, Task 3.3 — respected. ✓

**Placeholder scan:** `<owning-svc>` / `V<next>__<name>` in Task 1 are conditional-on-a-gap (expected outcome is no migration) and are resolved at execution IF a gap is found — every concrete artifact (the drop SQL, the doc sections, the job commands, the exact two table names) is fully specified. No lazy placeholders.

**Consistency:** the two table names (`public.flyway_schema_history`, `public.outbox_events`), the Cloud Run job params (image/network/SA/secrets/env), the JDK-25 path, and the branch `phase3-db-cleanup` are consistent across tasks and match the spec. Task 1's "findings summary" is the exact artifact Task 2 Step 2 consumes.

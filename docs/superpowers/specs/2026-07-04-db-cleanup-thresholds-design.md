# DB Cleanup + Scaling Thresholds — Design (Phase 3, Task 3.2 right-sized)

**Source task:** `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` § Phase 3, Task 3.2 (`P2-2`) — "Indexing, partition attendance, drop dead objects." **Right-sized after measuring prod:** the headline items (partition + perf index-tuning) are premature at current scale and are explicitly deferred with documented thresholds; the safe, valuable subset is done now.

## Why right-sized (measured evidence, 2026-07-04)

Read-only prod query (`custoking_ims_v1`, via a Cloud Run psql job) showed the data is tiny:

| Table | Rows | Total size |
|---|---|---|
| `attendance.attendance_student_records` (the real volume table: student×day) | 22 | 144 kB |
| `attendance.attendance_daily` (the plan's named target: section×day) | 9 | 96 kB |
| `student.students` / `catalog.catalog_orders` / `fee.*` | 5–19 | tiny |

Consequences:
- **Partitioning is premature (YAGNI).** It pays off at millions of rows; the biggest table is 144 kB. And `attendance_daily`'s PK is `id VARCHAR` (not the date) with `attendance_student_records` FK-ing to `attendance_daily(id)`, so partitioning by `attendance_date` would force a PK restructure + child-FK rework + a full online table rewrite on a live table — real risk, zero current benefit. (The plan also names `attendance_daily`, but the volume table is `attendance_student_records`.)
- **Perf index-tuning is premature.** Postgres uses seq scans on tables this small regardless of indexes; "EXPLAIN improvements" is meaningless here, and Task 1.4 already added the tenant-leading composite indexes.
- **"Drop dead objects" is real but small.** No retired `public.*` *domain* tables remain (already cleaned in earlier phases). Only two dead infra tables survive in `public`, both verified **unreferenced** by any code/config (grep of `services/*/src` + resources), and **no service uses the `public` schema**: `public.flyway_schema_history` (old monolith Flyway history) and `public.outbox_events` (monolith leftover).

## Decisions (locked during brainstorming)

1. **Right-size:** drop verified-dead orphans + a lean index/constraint *correctness* audit + document scaling thresholds. **Defer** partitioning and speculative perf indexes until data warrants (captured in the thresholds doc).
2. **Orphan drop vehicle = a tracked one-off SQL run via the established prod-DB Cloud Run job pattern** — not a per-service Flyway migration, because the `public` schema is unowned (no service's Flyway history covers it) and these tables are prod-only monolith residue.
3. **Safety before drop:** the script first `SELECT`s each table's full content into the job log (a permanent Cloud Logging record) before `DROP TABLE IF EXISTS`.

## Components

### Component 1 — Drop the 2 dead `public.*` orphan tables
- **Script:** `scripts/drop-dead-public-tables.sql` (committed for the record). It:
  1. `SELECT count(*)` and `SELECT *` (LIMIT reasonable) from `public.flyway_schema_history` and `public.outbox_events` — captured to the job log as a pre-drop safety record.
  2. `DROP TABLE IF EXISTS public.outbox_events;` and `DROP TABLE IF EXISTS public.flyway_schema_history CASCADE;` (idempotent; `IF EXISTS` makes it a no-op on fresh/local DBs that never had them).
  3. Post-drop verification: list remaining `public.*` base tables (expect none of the two).
- **Run:** via a one-off Cloud Run job (image `postgres:16-alpine`, `--network=default --subnet=default --vpc-egress=all-traffic`, SA `755376288593-compute@developer`, mount the SQL + `PGPASSWORD=db-password` as secrets, connect `PGUSER=appuser PGHOST=10.116.0.3 PGDATABASE=custoking_ims_v1`). Controller-operated (prod DDL). Delete the job + secret after.
- **Note for Task 3.1:** the future reporting outbox is a *per-service* transactional outbox (in owning services' schemas), so it will not reuse `public.outbox_events` — dropping it now is clean and does not pre-empt 3.1.

### Component 2 — Index & constraint correctness audit (lean)
- Read the hot-path read repositories in the domain services (school-core: attendance/student/fee/catalog read repos; platform: reporting read repos) and compare the frequent `WHERE`/`ORDER BY`/join shapes against the existing indexes (esp. Task 1.4's tenant-leading composites already in the migrations).
- Add or fix a **forward Flyway migration in the owning service** ONLY for a genuine *correctness* gap: an index whose column order doesn't match a frequent filter/sort that would matter as data grows, or a clearly-missing intra-schema FK / UNIQUE / NOT NULL that protects integrity. **No speculative performance indexes.**
- Expected outcome: small or zero schema changes. Whatever is checked (and why nothing was added, if so) is recorded in the thresholds doc (Component 3) so the audit is auditable.

### Component 3 — Document DB scaling thresholds (`docs/DB-SCALING-THRESHOLDS.md`)
- New doc capturing the deferred work and the decision rationale:
  - **Partitioning:** target table = `attendance.attendance_student_records` (student×day), not `attendance_daily`; trigger ≈ **1–5M rows** (or when index-backed queries degrade); the required restructure (partition key `attendance_date`/`academic_year_id` must be in the PK and every UNIQUE; the child-FK/PK rework; online create-partitioned-table-and-migrate since Postgres can't convert in place); and range strategy (by academic year or month).
  - **Index tuning:** becomes worthwhile once tables exceed ~10k–100k rows and the planner starts choosing index scans; re-derive from `EXPLAIN (ANALYZE, BUFFERS)` on the actual hot queries then.
  - **How to re-measure:** the read-only volume/size diagnostic query (row counts + `pg_total_relation_size`) and how to run it (Cloud Run psql job pattern).
  - The current measurement snapshot (this doc's evidence table) as the baseline.

## Testing / verification

- **Component 1:** the pre-drop `SELECT` output + the post-drop `public.*` listing in the job log ARE the verification (0 of the two tables remain; prod app unaffected — re-run a quick authenticated route check to confirm no regression, though nothing references these tables).
- **Component 2:** any Flyway migration is validated by the owning service's existing Testcontainers integration suite (real Postgres + Flyway + RLS) staying green at its baseline count, plus a local `mvn test` on the service. If no migration is added, no test change.
- **Component 3:** docs-only.

## Rollback / risk

- The orphan drop is **prod-only, `IF EXISTS`, content-captured-first**. These tables are verified dead; risk is minimal. If somehow needed, the captured log content documents what existed (and `flyway_schema_history` for the old monolith has no forward value — each service now owns its own history).
- A Component-2 Flyway migration is forward-only per service; if a migration misbehaves it's caught by CI/Testcontainers before merge, and per-service deploy/rollback applies.
- No live-table rewrite, no PK/FK surgery, no partitioning — the risky operations are explicitly out of scope.

## Non-goals (YAGNI)

Partitioning any table now; speculative performance indexes; Task 3.1 (reporting outbox/Pub/Sub); Task 3.3 (connection pooling); any change to the retained `flyway_schema_history` tables **inside each service's own schema** (those are live and owned — only the orphaned `public` one is dropped).

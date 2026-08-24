# DB Scaling Thresholds

Phase 3 Task 3.2 was right-sized after measuring prod: data volumes are tiny (tens of
rows per table), so partitioning and further index tuning are premature optimizations
today. This doc captures the deferred work as explicit, re-checkable thresholds instead
of doing it now, plus the Task 1 index/constraint audit that closes out the correctness
side of Task 3.2.

## Purpose & current baseline

Partitioning and additional performance-index tuning are **deferred, not skipped** —
they are correct future work once tables reach real production volume, but doing them
against near-empty tables today would add migration/operational risk (PK/FK restructure,
partition maintenance) with no measurable benefit, and the shape of the "right" index is
best derived from real `EXPLAIN (ANALYZE, BUFFERS)` plans at scale, not guessed now.

Measured snapshot (prod, 2026-07-04):

| Table | Row count | Size |
|---|---|---|
| `attendance.attendance_student_records` | 22 | 144 kB |
| `attendance.attendance_daily` | 9 | — |
| `student.students` | 10 | — |
| `catalog.catalog_orders` | 19 | — |
| `fee.*` (bands/items/assignments/payments) | 5 each | — |

At this volume every hot query is served entirely from a few pages of a tiny table or
index; sequential scans and unindexed sorts cost nothing. None of the deferred items
below are correctness gaps (see Index audit).

## Partitioning threshold

- **Target table:** `attendance.attendance_student_records` (the student × day fact
  table), **not** `attendance_daily` (the small per-section/day parent).
- **Prepare at 10 million rows.** Refresh statistics, repeat production-shaped query plans, run the
  checked-in production operator sequence against a restored clone, and obtain a maintenance/freeze
  decision. This is deliberately before the migration becomes urgent.
- **Schedule execution at 20 million rows and finish before 25 million.** At current onboarding targets,
  200,000–300,000 students generate roughly 44–66 million fact rows per 220-day academic year, so waiting
  for an incident is not a workable rollout policy. Query-plan degradation can advance these thresholds.
- **Required restructure:** Postgres declarative partitioning requires the partition key
  to be part of the table's `PRIMARY KEY` and every `UNIQUE` constraint. Today
  `attendance_daily`'s PK is `id VARCHAR`, and `attendance_student_records` carries a FK
  to `attendance_daily(id)`. Partitioning `attendance_student_records` by
  `attendance_date` (or `academic_year_id`) therefore requires reworking that PK/child-FK
  relationship first (e.g. composing the partition key into the PK, or replacing the
  `attendance_daily(id)` FK with a composite key that includes the partition column).
- **Postgres cannot convert an existing table to partitioned in place** — the operation
  is: create a new partitioned table with the reworked keys, backfill/migrate rows, swap
  the table in (an online create-partitioned-and-migrate, not an `ALTER TABLE`).
- **Range strategy:** range-partition by academic year (or by month within a year, if
  monthly ranges are needed for retention/archival granularity).

## Production rollout contract

The 25-million-row rehearsal proves the target semantics and resource envelope; it does **not** make an
application-startup Flyway rewrite safe. PostgreSQL cannot build the replacement table, global identity
registry, backfill, indexes, and name swap atomically without long locks and a large transaction/WAL
envelope. The current application also writes directly to the authoritative table, so an online copy needs
a reviewed dual-write/catch-up protocol that the repository does not currently have.

DATA-01 therefore uses the guarded operator sequence in
`docs/runbooks/ATTENDANCE-DATA01-PARTITION-ROLLOUT.md`. It requires an approved write freeze, bounded
`lock_timeout`, phase evidence, equal row counts/checksums, constraint/RLS/pruning verification, and a
rollback decision before application writes resume. No production Flyway migration has been added, and no
operator script is authorized merely by crossing a monitoring threshold.

The rollout remains maintenance-window based until one of these is explicitly designed and tested:

1. dual-write triggers plus a high-watermark/catch-up protocol that preserves the global identity registry;
2. a logical-replication/change-data-capture cutover with delete/update ordering; or
3. an application release that dual-writes both representations and proves convergence.

## Index-tuning threshold

- Additional performance indexes (beyond the tenant-leading composites already added in
  Task 1.4) become worthwhile once a table exceeds roughly **10,000–100,000 rows** — the
  point at which the query planner typically starts preferring index scans over
  sequential scans for the hot filter/sort patterns.
- **Do not pre-guess indexes now.** Re-derive the actual index shape from
  `EXPLAIN (ANALYZE, BUFFERS)` on the real hot queries once a table crosses this
  threshold, rather than adding speculative composites against near-empty tables.
- Task 1.4 has already added tenant-leading composite indexes (`school_id`-first, or the
  natural tenant/foreign key first) across attendance, student, fee, catalog, and
  reporting read paths — see the Index audit below for the full per-path list.

## Index audit (2026-07-04)

Task 1 audited every hot read path in `school-core-service`
(Attendance/Student/Fee/Catalog `ReadRepository`) and `platform-service`
(`Reporting*Repository`) against the `CREATE INDEX` / constraint DDL in each service's
Flyway history. Summary of findings:

- **No correctness gap.** All 20 audited read paths are served by an index whose leading
  column(s) match the query's filter — tenant-leading `school_id` first wherever the
  query filters by school — and every intra-schema `FOREIGN KEY` / `UNIQUE` / `NOT NULL`
  constraint that protects data integrity (dedup upserts, one-assignment-per-year,
  one-admission-number-per-school, command-center feed dedup, etc.) is present.
- Cross-schema FKs were intentionally dropped in earlier migrations per the
  "no cross-service foreign keys" architecture rule; the replacement (`NOT NULL`
  tenant key + tenant-leading index) is present everywhere checked.
- **One deferred perf candidate:** `attendance.attendance_daily` has no
  `(attendance_date, academic_year_id)` composite index; the relevant reporting query
  (`ReportingReadRepository.lowAttendanceSections` / dashboard defaulter queries)
  currently falls back to `idx_attendance_daily_date(attendance_date)` plus a join-side
  index. This is a **performance-not-correctness** gap — the table is a handful of rows
  per school/day, a UNIQUE constraint already guarantees at most one row per
  section/date/year (so no duplicate/ambiguous results are possible), and a sequential
  scan costs nothing at current scale. Deferred; revisit once `attendance_daily` crosses
  the index-tuning threshold above.
- **Out of scope, not a gap:** `ReportingApprovalRepository`'s pending-approvals query
  joins `catalog.catalog_orders` (covered by `idx_catalog_orders_status`) with
  `firefighting.firefighting_requests`, which is owned by operations-service and outside
  Task 1's declared scope (school-core-service + platform-service only). This is a
  Task 3.1 (outbox/cross-service) concern, not a Task 3.2 (scaling) one.
- No Flyway migration was added as a result of this audit — nothing changed; the audit
  is documentation confirming the existing schema is already correct.

Full per-path table and constraint spot-checks were captured during the Task 1 audit,
but the scratch report was not kept as durable project documentation.

## How to re-measure

Re-run this simple, read-only diagnostic periodically (e.g. quarterly, or when a table
"feels" like it's grown) to check against the thresholds above:

```sql
-- Row counts + on-disk size for the tables named in this doc
SELECT
  n.nspname AS schema,
  c.relname AS table,
  c.reltuples::bigint AS approx_row_count,
  pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname IN ('attendance', 'student', 'fee', 'catalog')
  AND c.relkind = 'r'
ORDER BY pg_total_relation_size(c.oid) DESC;
```

Run it the same way `scripts/drop-dead-public-tables.sql` is run — as a one-off `psql`
script against the target Cloud SQL instance via a short-lived Cloud Run job (or
`cloud-sql-proxy` + local `psql`), never against prod from a developer machine directly.
For exact row counts (rather than the planner's `reltuples` estimate) on the small
tables this doc tracks, `SELECT count(*) FROM <schema>.<table>` is cheap enough to run
directly.

## Continuous DATA-01 monitoring

`AttendanceStorageHealthReporter` emits one bounded-cardinality structured sample every five minutes from
`pg_stat_user_tables`, `pg_class`, and relation-size functions. It uses planner/autovacuum row estimates and
`pg_partition_tree`, so collection does not perform an exact `count(*)` and continues to work after cutover.
Its scan signal is an interval delta rather than a lifetime PostgreSQL counter; a restart establishes a
zero-delta baseline, counter resets are suppressed, and sequential-scan risk is suppressed below one million
rows where PostgreSQL is expected to prefer cheap sequential reads.

Reviewable Terraform is in `deploy/gcp/observability/attendance_growth.tf` and is guarded by
`enable_attendance_growth_monitoring=false`. No live metric or alert is created until an operator reviews a
plan and explicitly enables it. The source thresholds are:

| Signal | Warning/action threshold | Reason |
|---|---:|---|
| Approximate fact rows | 10,000,000 | start clone rehearsal, plan review and maintenance approval |
| Approximate fact rows | 20,000,000 | schedule the approved cutover before 25,000,000 |
| Aggregate fact-table index bytes | 8 GiB | inspect write amplification, duplicate/unused indexes and disk headroom |
| Sequential tuples read | one full-table equivalent per five-minute interval, sustained 15 minutes | capture `EXPLAIN (ANALYZE, BUFFERS)` for the responsible production-shaped query |

Because PostgreSQL statistics are estimates and can reset, an alert is a review trigger rather than proof
that DDL is required. Run `ANALYZE attendance.attendance_student_records`, inspect the raw structured log,
and confirm query plans before acting. Multiple Cloud Run instances are reduced with `MAX`, not summed, so
replicas do not multiply the scan signal.

## Connection pooling

- **Current headroom:** peak ≈ 5 domain services × Hikari `maximum-pool-size` 5 ×
  `max-instances` ≤2 ≈ ~50, plus transient
  per-schema Flyway migration pools (max 3, min-idle 0, drain after migrate) — vs
  `max_connections=200` (≈25% utilization). There is substantial headroom before pooling
  or connection limits become a concern.
- **Single source of truth:** pool size/min-idle are defined **only** in each service's
  `application.yml` (`spring.datasource.hikari.maximum-pool-size: ${DB_POOL_MAX:5}`,
  `minimum-idle: ${DB_POOL_MIN:0}`); override per-env via `DB_POOL_MAX`/`DB_POOL_MIN`. The
  duplicate `SPRING_DATASOURCE_HIKARI_*` env vars previously set in retired deployment config
  common_env and per-service blocks in `docker-compose.yml` have been removed — Spring's
  relaxed env-var binding meant they silently overrode the yml defaults, so two sources of
  truth could (and did) drift out of sync.
- **Pooler threshold:** introduce a pooler (PgBouncer in transaction mode, or the Cloud SQL
  connector's built-in pooling) once peak connections approach ~150/200 — e.g. as
  `max-instances` or per-service pool sizes grow.
- **Prerequisite for transaction-mode pooling:** the RLS GUC in
  `services/*/security/TenantAwareDataSource.java` is currently **session-level**
  (`set_config('app.current_school_id', …, false)`, set on every Hikari borrow — safe with
  direct Hikari connections, certified by `TenantGucConnectionReuseIntegrationTest`).
  Transaction-mode pooling multiplexes physical backends per transaction rather than per
  session, so it first requires converting this GUC to **transaction-local**
  (`SET LOCAL` / `set_config(…, true)`) set inside each transaction, plus reworking the
  autocommit read path (which is why session-level was chosen in the first place). Do this
  conversion, with its own RLS regression tests, before enabling a transaction-mode
  pooler.

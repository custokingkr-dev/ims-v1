# Scale Readiness, GCP Cost Model, and One-Week Production Plan

> **Status reconciliation (2026-08-12):** completed measurements remain evidence, but current unresolved
> gates and execution order are maintained in
> [REMAINING-WORK-2026-08-12.md](REMAINING-WORK-2026-08-12.md). The system is approved for controlled
> canaries, not simultaneous onboarding of 100-150 schools, until the arrival-rate mixed workload,
> production SQL/HA decision, async production topology, and governance gates pass.

Original evidence date: 2026-08-10; capacity/cost status reconciled 2026-08-11
Repository: `custokingkr-dev/ims-v1`
GCP project inspected: `custoking`
Region: `asia-south2` (Delhi)
Target fleet: 100-150 schools, 200,000-300,000 active student records
Largest supported school target: 10,000 students

> **Final status update (2026-08-11):** This document preserves the August 10 implementation and test history,
> but its original 2-vCPU conclusion is superseded. `db-custom-4-7680` passed the burst and complete
> 4h10m/300-VU attendance-write soak. After V16/V17 plan improvements, the unchanged closed-loop
> MixedMorning gate still failed on 4 vCPU (100% CPU) and 8 vCPU (99.45% CPU); 8 vCPU improved aggregate
> p95/p99 to 453.03/936.02 ms but did not pass CPU/error gates. Live 20x500 onboarding inserted/reconciled
> exactly 10,000 rows, two-instance admission passed, stabilized cleanup reached zero, Pub/Sub backlog is
> zero, and dev SQL is stopped on `db-f1-micro`. There is no successful MixedMorning production-sizing
> result; use an approved arrival-rate workload before selecting production capacity. Detailed evidence is in
> `docs/workstreams/RELIABILITY-SCALE-RECOVERY-CHANGES-2026-08-11.md` and
> `docs/workstreams/ONBOARDING-CERTIFICATION-RESULTS-2026-08-11.md`.

## 1. Decision

The service and tenancy architecture can support the target fleet without introducing Kubernetes,
Redis, a database per school, or another application rewrite. The current production configuration
cannot safely be assumed to support it.

The following must be true before a large onboarding wave:

1. Production Cloud SQL moves off `db-g1-small`. Google classifies this shared-core shape as a
   low-cost test/development instance and excludes it from the Cloud SQL SLA.
2. The 10,000-student and 300,000-student load profiles pass in dev/stage using production-like
   data, queries and connection limits.
3. Attendance growth receives a partition/retention decision before tens of millions of rows are
   accumulated.
4. Student directory pagination remains database-side. This review implements that correction.
5. Attendance and import write paths are batched before mass onboarding.
6. Background projections run through request-driven jobs or push handlers, not only in-process
   timers on scale-to-zero instances.
7. The critical authentication, IAM and deployment-governance findings are closed.

The current low-cost planning default is a dedicated 4-vCPU/7.5-GiB zonal PostgreSQL instance because
the 2-vCPU shape failed the target guard and 4 vCPU/7.5 GiB is the cheapest measured full 300-VU-soak pass.
This is test-backed capacity planning, not production purchase, availability/SLA or business approval.
Use regional HA if the business requires a Cloud SQL SLA or cannot accept a zonal database outage.

## 2. What Was Examined

- React/Vite frontend and workspace modules.
- Node API gateway authentication, proxying, rate limits, CORS and request limits.
- Five Spring Boot services and all PostgreSQL/Flyway migrations.
- Tenant context, PostgreSQL RLS, runtime/migration database roles and service boundaries.
- Student, attendance, fee, import, photo, reporting, notification and billing paths.
- Transactional outbox, Pub/Sub delivery and reporting projection behavior.
- Cloud Run manifests, Skaffold, Cloud Deploy, GitHub Actions and Terraform.
- Live Cloud Run, Cloud SQL, Pub/Sub, Secret Manager, IAM, WIF, buckets, monitoring, backups,
  budgets and billing export.
- Current official Google Cloud pricing and the billing-account pricing export.
- Full application test suites and production frontend build.

No live infrastructure or production data was mutated during the review.

## 3. Workload Assumptions

The user supplied two distinct constraints. They are modeled independently:

- A single large school may contain 10,000 students.
- The whole initial fleet contains 200,000-300,000 students across 100-150 schools.

This means the average school is approximately 2,000 students while the platform must avoid
algorithms that fail on a 10,000-student outlier.

Planning assumptions used by `scripts/estimate-scale-cost.ps1`:

| Variable | 100-school model | 150-school model |
| --- | ---: | ---: |
| Schools | 100 | 150 |
| Students | 200,000 | 300,000 |
| School days/year | 220 | 220 |
| Staff users/school | 25 | 25 |
| API actions/staff/day | 40 | 40 |
| Business days/month | 22 | 22 |
| Cloud Run container requests/browser action | 2.2 | 2.2 |
| Average billable duration/request | 300 ms | 300 ms |
| Detailed attendance retention | 3 years | 3 years |

These are planning assumptions, not measured customer behavior. The model intentionally does not
hide uncertainty behind a single invoice number.

## 4. Capacity Model

### 4.1 Student master data

Three hundred thousand student master records are not large for PostgreSQL. Even allowing roughly
4 KiB per student for the main row, related lifecycle data and indexes, this is around 1.1 GiB.

The design is favorable because:

- tenant-leading indexes exist on the student and related domain tables;
- PostgreSQL RLS is enforced with a connection-scoped tenant identifier;
- runtime connections use a non-owner role;
- list APIs are school-scoped;
- class and section IDs further narrow common operational queries.

The risk is query shape, not master-row count. Before this implementation the student directory
read every matching student, built Java maps for all of them and only then performed page slicing.
A 10,000-student school therefore incurred a 10,000-row database read for every 50-row page. It
also ran two lateral review-state lookups per student. This is now database-paginated with a
separate aggregate count query.

### 4.2 Attendance history

Attendance is the dominant relational growth stream:

| Fleet | Detail rows/day | Detail rows/220-day year |
| --- | ---: | ---: |
| 200,000 students | 200,000 | 44,000,000 |
| 300,000 students | 300,000 | 66,000,000 |

Using a 550-byte planning allowance per logical attendance record, including heap, several indexes
and ordinary bloat:

| Fleet | Estimated attendance storage/year | Three-year modeled used data, including core data and 30% headroom |
| --- | ---: | ---: |
| 200,000 | 22.5 GiB | 88.9 GiB |
| 300,000 | 33.8 GiB | 133.3 GiB |

Actual storage must be measured with `pg_total_relation_size`; this is a sizing estimate.

The attendance indexes cover the important tenant/date, section/date and student/date access
patterns. The table is not partitioned. PostgreSQL can operate at 66 million rows, but vacuum,
index maintenance, backup/restore time and historical reports will become progressively more
expensive. Introduce time partitioning before the table becomes operationally difficult, not on
the first day merely for fashion.

Recommended boundary:

- retain the current table while it is below 10-20 million rows and measured latency is healthy;
- prepare monthly or academic-year range partitioning before 25 million rows;
- archive expired detail according to the product/legal retention policy;
- keep summarized facts for long-range dashboards.

### 4.3 Morning attendance peak

At 300,000 students and an average 40 students/section, the fleet has about 7,500 sections. If all
sections submit attendance over two hours, the average is only about one section submission per
second. A five-times burst is approximately five submissions per second.

The current implementation performs per-student validation and upsert statements in a Java loop.
A 40-student section can therefore create more than 80 database statements plus summary counts.
At a five-request/second burst this can become several hundred SQL statements/second.

This is likely to saturate `db-g1-small` and the current total school-core connection capacity well
before Cloud Run reaches concurrency 80. Replace the loop with set-based validation and JDBC batch
upserts. The transaction must remain section-atomic and idempotent.

### 4.4 Imports and onboarding

Current limits:

- student import: 500 rows/batch;
- managed photo mapping: 1,000 rows/batch;
- workbook parsing: 10 MiB;
- source image: 20 MiB;
- ordinary photo upload: 5 MiB, normalized before permanent storage.

A 10,000-student school requires 20 student-import batches and ten photo-mapping batches. This is
technically possible but operationally poor. Confirmation currently performs row-by-row inserts,
enrollment writes and outbox events inside a request transaction even though the API exposes a job
identifier.

Required improvement:

- upload once to Cloud Storage;
- validate asynchronously;
- process 250-500 rows per transaction;
- report durable progress and row-level errors;
- make confirmation resumable and idempotent;
- cap simultaneous import jobs per school and across the fleet.

### 4.5 Fees and payments

Fee assignments and payments are tenant-indexed and much smaller than attendance. A pessimistic
three million payment rows/year is ordinary PostgreSQL scale. Correctness, reconciliation,
idempotency and immutable financial audit records are more important than raw capacity here.

### 4.6 Photos

Current normalized photos average roughly 40-50 KiB in the inspected bucket. At 300,000 students,
permanent photos are therefore approximately 12-15 GiB. Even a 100-KiB average is only 30 GiB.
Storage is inexpensive; repeated downloads and import staging are the larger risks.

Keep normalized current photos in Standard storage, delete temporary sources through lifecycle
rules, use long private cache headers/signed delivery, and generate a small list thumbnail if list
views begin transferring full portraits.

## 5. Code Readiness Matrix

| Path | Current assessment | Target-fleet result |
| --- | --- | --- |
| Tenant isolation/RLS | Strong and integration-tested | Suitable |
| Student master storage | Correct shared-schema model | Suitable |
| Student list pagination | Previously paginated in Java | Corrected in this change |
| Student search | Tenant-scoped `%LIKE%` across many fields | Acceptable at 10k/school; measure before adding costly indexes |
| Attendance storage | Correct indexes, no partitions | Suitable initially; must partition/retain before large history |
| Attendance submission | Set validation and multi-row upsert | Deployed; historical 300-VU stage passed, later guarded capacity gates remain open |
| Student import | 500-row explicit batches, retry-safe confirmation | Cost-minimized onboarding path; 20 batches for 10k students |
| Review campaign creation | Set insert plus 500-row event chunks | Deployed; 10k-school path is about 21 statements |
| Reporting projections | Idempotent projections but formerly permanent failure/no lease | Retry, lease and dead-letter added here |
| Billing outbox | At-least-once but formerly no concurrent claim | `SKIP LOCKED` added here |
| Cloud Run background timers | Stop at zero instances/CPU throttling | Replace with request-triggered work or jobs |
| Frontend student paging | Uses 50-row server pages | Suitable |
| Frontend bundle | Workspace and spreadsheet chunks exceed 900 KiB | Functional; split for latency/mobile use |
| Database connection pools | School-core dev uses 20 per instance | Four-instance test ceiling is 80/200 connections |
| Load testing | Exact 300k fixture and guarded write/read workloads | 4 vCPU passed burst and corrective 4h10m attendance soak; V17 plans improved, but unchanged MixedMorning failed CPU gates on both 4 and 8 vCPU. Live 10k onboarding and two-instance admission passed; final cleanup is zero |

## 6. Cloud SQL Recommendation

### Current state

Production is PostgreSQL 16 on `db-g1-small`: one shared vCPU and 1.7 GB RAM, zonal, 10-GB SSD,
private IP, automatic storage growth, backups and PITR.

Google's current documentation says `db-f1-micro` and `db-g1-small` are designed for low-cost test
and development and should not be used for production. Shared-core and single-zone instances are
excluded from the Cloud SQL SLA.

### Cost-minimized performance path

1. Retain `db-custom-2-7680` only as the rejected 300-VU/lower-concurrency comparison.
2. Keep it zonal only if the business explicitly accepts no Cloud SQL SLA and restore/failover
   downtime in exchange for lower cost.
3. Retain `db-custom-4-7680` as the least-cost measured shape that passed the full target soak; test a higher
   supported shape only if a remaining representative workload, such as MixedMorning, proves saturation.
4. Enable Query Insights during the onboarding/load-test window and set a maintenance window.
5. Enforce encrypted database connections.
6. Recalculate pools before increasing Cloud Run max instances.

The August 10 run measured 57.4% CPU, 50.6% memory and 67 application backends on a clean 300-VU
attendance-write profile. A stricter August 11 morning-burst run on the same 2-vCPU/7.5-GiB shape later
failed after three CPU guard samples reached 83.52%; the historical pass therefore does not certify that
shape for the target. Four vCPU/7.5 GiB passed the full short burst at 58.29% maximum CPU, then failed the
first full soak at 2h28m on sustained CPU. After the measured fixes and exact cleanup, the corrective run
completed the full 4h10m/300-VU profile with k6 exit 0, all thresholds passing, 4,178,728 requests, overall
p95/p99 of 108.536/238.370 ms, attendance-write p95/p99 of 74.591/116.766 ms, and maximum Cloud SQL CPU/
memory/connections of 54.22%/46.8998%/81. MixedMorning remains a separate failed workload gate:
the first corrected run stopped after CPU samples of 82.46%, 100% and 100%, and exact logs also
proved the project exhausted its 20-vCPU regional Cloud Run allocation during cold scale. Directory
indexes, a cheaper stats query and dev-only startup-boost mitigation must be redeployed and retested.

### Availability path

Use a regional HA dedicated instance when school operations require an SLA. HA approximately
doubles database compute and storage charges but provides synchronous regional standby/failover.
Backups alone do not provide comparable recovery time.

### Connection budget

Current dev scale-test school-core pool ceiling:

```text
4 school-core instances * 20 Hikari connections = 80 connections
Cloud SQL max_connections = 200
```

Cloud Run concurrency 80 does not create 80 database connections. The measured 300-VU stage used
67 application backends and remained under the 70% connection gate. Maintain at least 30% database
connection headroom for migrations, administration, jobs and failover behavior; do not increase
maximum instances or pools independently of this budget.

## 7. Cloud Run Recommendation

Keep request-based billing and minimum instances zero by default. This is the correct cost posture.

Change the current fleet-wide assumptions as follows:

| Service | Current max | Initial scale-test max | Notes |
| --- | ---: | ---: | --- |
| Frontend | 2 | 3-5 | Mostly static/cacheable |
| Gateway | 3 | 4 measured | Node async I/O; distributed rate limiting still needed |
| Identity | 2 | 3 | Login bursts, otherwise low traffic |
| School-core | 2 | 4 measured | 20-connection pool; 4-vCPU attendance-soak planning default only. MixedMorning failed 4 and 8 vCPU and requires an arrival-rate rerun before production sizing |
| Operations | 2 | 3 | Lower traffic |
| Platform | 2 | 3-5 | Pub/Sub projections and dashboards |
| Billing | 2 | 2 | Low frequency |

These are maximums, not reserved instances, so they do not create idle cost. They can increase
database connections and burst spend, so deploy them only with explicit pool math and budgets.

If cold starts violate school-hour latency, schedule a single minimum gateway or school-core
instance only during operating hours. Do not warm every Java service continuously.

## 8. Cost Model

### Pricing basis

The billing-account pricing export on 2026-08-10 reports the following Delhi list prices:

- dedicated zonal PostgreSQL vCPU: INR 4.744054/vCPU-hour;
- dedicated zonal PostgreSQL RAM: INR 0.8034285/GiB-hour;
- zonal PostgreSQL SSD: INR 19.511835/GiB-month;
- regional database compute/storage: approximately twice zonal;
- Cloud Run request-billed CPU: USD 0.0000336/vCPU-second (Delhi Tier 2 input checked 2026-08-11);
- Cloud Run request-billed memory: USD 0.0000035/GiB-second (Delhi Tier 2 input checked 2026-08-11);
- Cloud Run requests: USD 0.40/million;
- Pub/Sub: first 10 GiB/month free, then USD 40/TiB.

### Live baseline

For August 1-10, gross spend was approximately INR 2,794 and promotional credits reduced net cost
to zero. Cloud Run and Cloud SQL represented about 85% of gross cost. A naive full-month extension
is approximately INR 8,700, but development/deployment activity is not uniform.

### Fleet projections

| Scenario | Database plan | Cloud Run planning | Total zonal/month | Regional HA/month |
| --- | --- | ---: | ---: | ---: |
| 100 schools / 200k students | 4 vCPU, 7.5 GiB, 100 GiB | ~INR 7,302 | INR 25,505-39,852 (midpoint INR 31,881) | INR 41,667-65,105 (midpoint INR 52,084) |
| 150 schools / 300k students | 4 vCPU, 7.5 GiB, 150 GiB | ~INR 10,954 | INR 29,917-46,745 (midpoint INR 37,396) | INR 46,860-73,218 (midpoint INR 58,575) |

The current midpoint modeled totals are INR 31,881 and INR 37,396 zonal, and INR 52,084 and INR 58,575
regional HA. They use the 4-vCPU/7.5-GiB full-soak-backed planning default. The soak pass does not choose a
production database, approve the modeled spend, or decide zonal versus HA; MixedMorning and the remaining
production-readiness gates still apply.

Approximate platform infrastructure cost is therefore:

- 100-school zonal: INR 255-399/school/month;
- 150-school zonal: INR 199-312/school/month;
- 100-school HA: INR 417-651/school/month;
- 150-school HA: INR 312-488/school/month.

Excluded from these figures:

- SMS/WhatsApp/email provider charges;
- GST/taxes and support plans;
- engineering/support staff;
- domains and other SaaS subscriptions;
- extraordinary exports or internet egress;
- promotional credits and negotiated discounts.

Messaging can exceed GCP cost. For example, notifying 5% absentees across 300,000 students for 22
days means 330,000 outbound messages/month. Provider pricing and consent/retry behavior must be a
separate commercial model.

### Reproduce the estimate

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/estimate-scale-cost.ps1 `
  -SchoolCount 100 -StudentCount 200000

powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/estimate-scale-cost.ps1 `
  -SchoolCount 150 -StudentCount 300000
```

Re-query current prices before making a commercial commitment.

## 9. Cost Controls That Preserve Performance

Do:

- keep Cloud Run min instances at zero unless an SLO proves otherwise;
- use higher maximum scale, because it is not an idle reservation;
- batch SQL to reduce Cloud Run active time and Cloud SQL CPU together;
- keep all runtime data paths in Delhi to avoid regional transfer;
- schedule dev SQL and stop unnecessary dev activity outside working hours;
- delete old build images and deploy/source artifacts;
- retain logs selectively and sample traces;
- cache static frontend assets aggressively;
- record requests, stored bytes, messages and heavy jobs by school;
- buy a Cloud SQL CUD only after 30-60 days at the final dedicated shape.

Do not:

- stay on a shared-core production database merely to save fixed spend;
- enable one minimum instance on all seven services;
- add GKE, Redis, Kafka, a PgBouncer VM or database-per-school at this scale without evidence;
- put every student photo or report in the relational database;
- add broad indexes before checking query plans and write amplification;
- treat a budget alert as a hard spending cap.

## 10. Security and Reliability Preconditions

The earlier infrastructure audit remains part of scale readiness. More customers increase the
impact of each control failure.

Critical:

1. Refresh tokens were accepted through the access-token introspection path. Source now rejects them in both
   gateway and identity; dev validation passed and production governance remains gated.
2. Dev now uses dedicated runtime identities and passed its permission regression. Production least-privilege
   cutover remains gated.
3. Dev reporting push moved to a dedicated OIDC identity/audience and no longer has a query
   credential. The existing production reporting subscription still requires migration. Dev notification
   now has a dedicated OIDC subscription, retry/DLQ and passing synthetic probes; production
   still requires a deliberate topology/provider decision. Rotate static secrets
   only after every existing consumer is cut over.
4. The public repository has no protected branches/rulesets while a repo-wide WIF trust can assume
   a broad deployment account. Restrict branches, workflow claims and service accounts.

High:

- background workers can stop when services scale to zero;
- production SQL is zonal/shared-core and the recovery drill has never run;
- dev and prod share one project, runtime identity and VPC boundary;
- Cloud Run services use direct `run.app` exposure without a load balancer/WAF;
- 44 secrets have no rotation schedule;
- CI previously allowed its summary to pass after dependency jobs failed;
- the scheduled container scan previously used `exit-code: 0`;
- notification delivery remains logging/dry-run only;
- frontend dependencies and container findings require remediation.

## 11. Changes Implemented in This Review

1. Refresh bearer-token confusion fixed at the gateway and identity introspection layers.
2. Regression tests added for refresh-token rejection.
3. Student workspace pagination moved from full-result Java slicing to SQL `LIMIT/OFFSET` plus
   database counts.
4. Reporting events now use atomic leases, retry backoff, five-attempt dead-lettering and abandoned
   lease recovery.
5. Billing outbox selection now uses `FOR UPDATE SKIP LOCKED`.
6. PR CI summary now fails when a required dependency fails or is cancelled.
7. Scheduled Trivy scanning now exits non-zero on policy violations.
8. Reproducible capacity/cost estimator added.
9. A read-oriented k6 school-day workload and fixture template added.
10. Attendance register writes now use set validation, a multi-row upsert, a filtered aggregate and
    one final event; a 120-student PostgreSQL integration test covers the batched path.
11. A dev-only synthetic fleet runner and guarded k6 attendance writer now cover the 10,000-student
    school and 300,000-student fleet shapes without production PII.
12. Student review campaigns now use one set-based item insert and 500-row outbox chunks; a
    520-student integration test verifies chunk-boundary correctness.
13. Live dev generated exactly 100 schools, 300,000 students, a 10,000-student largest school and
    7,576 sections in 74.76 seconds; cleanup and an independent zero-residue status check passed.
14. The August 10 sustained 300-VU write stage passed at 217.60 requests/second with 0.01% errors, write
    p95 122.91 ms, write p99 235.06 ms, 57.4% peak database CPU and 67/200 application backends. Later
    guarded evidence supersedes this as a sizing conclusion: 2 vCPU failed the target morning-burst guard.
15. The 500-VU stage was conservatively stopped after consecutive 87.9% and 87.6% database CPU samples. The
    historical two-vCPU envelope was therefore capped below 500 concurrent attendance writers; it is now
    rejected for the target 300-VU certification profile.
16. A read-path academic-year rewrite that serialized attendance transactions was removed and is
    protected by a PostgreSQL `xmin` no-rewrite regression test.
17. Dev max instances are four for gateway and school-core with minimum instances zero; the latter
    has a measured 80-connection theoretical pool ceiling against database maximum 200.
18. Cloud SQL state control now waits for the exact asynchronous operation before declaring start
    or stop complete, correcting a race exposed by the scheduled cost-control shutdown.
19. Student import confirmation now locks its batch and returns the original completed job/result
    on retries. The 500-row frontend/backend cap is retained as the explicit low-cost onboarding
    process, avoiding always-on workers and limiting a 10,000-student school to 20 bounded batches.

These changes are repository changes only. They are not deployed to production by this review.

## 12. One-Week Implementation and Rollout Plan

### Day 1 - Security and release gates

- Review and merge refresh-token boundary fix.
- Add branch protection/rulesets and required checks.
- Restrict GitHub WIF to approved branch, workflow and environment claims.
- Pin third-party actions by commit SHA.
- Make Trivy policy explicit: block new critical/high findings; track an approved baseline for
  existing findings rather than permanently disabling enforcement.

Evidence: gateway/identity tests, protected-branch screenshot/export, WIF policy export.

### Day 2 - Production-like data and query baseline

- Seed dev/stage with 150 tenant schools, 300,000 students, realistic classes/sections and at least
  one academic year of attendance distribution.
- Anonymize or synthesize data; do not clone production PII casually.
- Capture `EXPLAIN (ANALYZE, BUFFERS)` for student list/search, attendance save/report, fee summary,
  dashboard and reporting projection queries.
- Enable Query Insights for the test window.

Evidence: seed manifest, row counts, query-plan bundle, baseline database metrics.

### Day 3 - Write-path batching

- Completed in dev: replace per-student attendance validation/upserts with set-based validation and
  one multi-row upsert.
- Completed in dev: batch student review campaign items and their projection outbox events.
- Completed with the cost-minimized option: retain explicit 500-row transactions and make
  confirmation idempotent/retry-safe. Introduce asynchronous infrastructure only if a measured
  onboarding SLA requires unattended single-file processing.
- Add idempotency/retry tests for each path.

Evidence: statement count before/after, transaction tests, failed-job replay test.

### Day 4 - Async delivery and runtime identities

- Deploy reporting retry/lease migration in dev.
- Move outbox relay triggering to Cloud Scheduler + Cloud Run Jobs or another request-driven relay.
- Keep Pub/Sub push for projection wake-up.
- Completed for dev reporting push: remove the query credential, use a dedicated OIDC identity and
  exact Cloud Run audience, and prove one canonical event is acknowledged with HTTP 204.
- Remove the production reporting query credential after consumer compatibility is verified.
- Decide the notification-event topology: create OIDC subscriptions in dev/prod and prove delivery,
  or retire the unused topics/consumer configuration.
- Create least-privilege runtime identities per service/environment.
- Completed in dev: seven dedicated runtime identities with per-secret, per-topic, per-bucket and
  service-scoped invoker bindings; production cutover remains gated.

Evidence: zero stale outbox age under idle APIs, dead-letter replay, IAM policy diff, rotated secrets.

### Day 5 - Load, soak and failure tests

- Run k6 at 100, then 300, then 500 virtual staff users.
- Add a controlled attendance write scenario against dev only.
- Run a four-hour soak and a morning five-times burst.
- Terminate a projector instance while work is leased and prove recovery.
- Exercise Cloud SQL restart and connection backoff.

Initial gates:

- HTTP error rate below 1%;
- read p95 below 800 ms and p99 below 2 s;
- attendance save p95 below 1.5 s;
- no database CPU above 80% for 15 sustained minutes;
- no memory exhaustion or swap pressure;
- connection usage below 70% of configured max;
- outbox oldest pending below five minutes;
- Pub/Sub oldest unacked below two minutes;
- no cross-tenant result under concurrent mixed-school traffic.

### Day 6 - Database sizing and recovery

- Treat 2 vCPU as rejected for the target and retain the measured 4-vCPU/7.5-GiB full-soak pass as the
  planning default; size higher only if remaining representative evidence requires it.
- Provision 100-150 GiB initial storage with auto-growth and alerts.
- Decide zonal cost mode versus regional HA in writing.
- Enforce encrypted connections and set maintenance window.
- Run the first PITR recovery drill and record RTO/RPO.
- Finalize the attendance partition migration design; do not perform a risky online rewrite without
  rehearsal and rollback.

Evidence: signed sizing decision, recovery artifact, restored row checks, measured RTO/RPO.

### Day 7 - Canary and onboarding gate

- Deploy through dev and production canary stages.
- Watch latency, 5xx, CPU, memory, connections, slow queries, outbox age and Pub/Sub backlog.
- Onboard a small school cohort first, not all 100 schools at once.
- Hold at 5-10 schools for one school-day peak, then 25, 50, 100 and 150 only when gates remain
  green.

Rollback triggers:

- tenant isolation failure;
- error rate above 2% for five minutes;
- p95 above two seconds for 15 minutes on core operations;
- database CPU above 90% for 15 minutes;
- connection exhaustion;
- unprocessed events older than 15 minutes;
- unexplained cost-rate increase above twice the modeled daily range.

## 13. Running the Load Test

The test must target dev/stage, never production writes.

1. Copy `load-tests/fixtures.example.json` to ignored `load-tests/fixtures.json`.
2. Add short-lived dev access tokens for representative schools.
3. Install k6 in the controlled test environment.
4. Run:

```powershell
$env:BASE_URL = 'https://DEV_GATEWAY_URL'
$env:K6_FIXTURES_FILE = './load-tests/fixtures.json'
$env:PEAK_VUS = '100'
k6 run load-tests/school-day-read.js
```

Run 100, 300 and 500 VUs and preserve the JSON summary with Cloud Monitoring screenshots. The
read test alone is not the production gate; attendance writes and import jobs need separate safe
dev scenarios after batching is implemented.

## 14. Operational Thresholds

| Signal | Warning | Scale/fix action |
| --- | --- | --- |
| Cloud SQL CPU | p95 > 65% for a week | Optimize top queries; then add vCPU |
| Cloud SQL memory | sustained pressure/cache churn | Increase RAM/dedicated shape |
| Connections | > 70% | reduce pool/max scale or introduce a proven pooler design |
| Attendance table | 10-20M rows | finalize/test partition design |
| Attendance table | 25M rows | execute partition/retention plan before further onboarding |
| Storage free | < 25% | increase alert/provisioning headroom |
| School-core p95 | > 800 ms | inspect SQL and pool queue before adding instances |
| 5xx rate | > 1% | stop onboarding and diagnose |
| Outbox oldest pending | > 5 min | relay incident |
| Pub/Sub oldest unacked | > 2 min | consumer/projector incident |
| Dead-letter event | any | page owner and replay after correction |
| Monthly gross forecast | > 125% model | cost incident/query regression review |

## 15. Go/No-Go Checklist

- [ ] Dedicated production Cloud SQL chosen from measured load results.
- [ ] Zonal-versus-HA risk accepted by business owner.
- [x] 300,000-student seed and 10,000-student tenant tests pass in dev.
- [ ] Attendance batch path passes the corrected full soak, but mixed-read production capacity remains
  blocked: unchanged 4-vCPU and 8-vCPU MixedMorning runs both failed the sustained CPU gate. Define and pass
  the approved arrival-rate workload; do not mark the closed-loop comparison passed.
- [x] Imports use an explicit 500-row operational batching process with retry-safe confirmation.
- [ ] Student list/search meets p95/p99 targets.
- [x] Dev reporting retries, dead-letter and guarded replay are verified; production remains gated.
- [x] Dev background relay operates while user-facing services are idle; Scheduler jobs returned to `PAUSED`.
- [x] Per-service runtime IAM is deployed and regression-tested in dev.
- [ ] Per-service runtime IAM is deployed and canary-tested in production.
- [x] Dev reporting Pub/Sub query credential is removed and dedicated OIDC delivery is verified.
- [ ] Production reporting Pub/Sub query credential is removed and rotated.
- [x] Dev notification topic has a deliberately provisioned/tested OIDC subscriber and DLQ; production
  notification provisioning and consented provider acceptance remain gated.
- [ ] Branch protection, required CI and restricted WIF are active.
- [x] Dev CodeQL and HIGH/CRITICAL container gates pass: CodeQL open 0; Trivy HIGH/CRITICAL 0 after
  exact-head stable-category run `31517658827` (209 MEDIUM/30 LOW remain for owned remediation).
- [ ] Promote the reviewed security fixes to `main` in the approved window and prove its current
  51 HIGH/zero CRITICAL Trivy backlog closes without administrative dismissal.
- [x] Dev PITR recovery drill passes with recorded RTO/RPO and clone/object/IAM cleanup; production remains gated.
- [x] The INR 5,000 crossing is resource-attributed, active dev cost is contained, and new full load runs
  fail closed on gross-spend headroom; see `docs/GCP-BUDGET-INCIDENT-2026-08-11.md`.
- [ ] Spending owner approves the production fleet envelope and corresponding alert/notification amounts;
  do not raise the budget merely to clear the current warning.
- [ ] Bounded per-school import usage exists, but complete API/attendance/storage/provider cost attribution does not.
- [ ] Notification-provider unit economics and consent controls are approved.
- [ ] Canary cohort completes a real school-day peak before the next wave.

Production remains a no-go while any mandatory item above is open. The passing four-hour 300-VU soak does
not replace the failed mixed-read gate, controlled recovery/PITR, runtime IAM/OIDC, production branch/security
scan, the production database/HA decision, business approval or staged canary evidence. Final dev fixture
cleanup is complete. The authoritative external-gate list is the final section of
`docs/PLANNED-CHANGES-EXECUTION-2026-08-11.md`.

## 16. Sources

Primary Google documentation:

- Cloud SQL machine series and shared-core specifications:
  https://docs.cloud.google.com/sql/docs/postgres/machine-series-overview
- Cloud SQL instance guidance and shared-core production warning:
  https://docs.cloud.google.com/sql/docs/postgres/instance-settings
- Cloud SQL SLA exclusions:
  https://cloud.google.com/sql/sla
- Cloud SQL pricing:
  https://cloud.google.com/sql/pricing
- Cloud SQL PostgreSQL best practices:
  https://docs.cloud.google.com/sql/docs/postgres/best-practices
- Cloud Run pricing and request-based billing:
  https://cloud.google.com/run/pricing
- Cloud Run concurrency guidance:
  https://docs.cloud.google.com/run/docs/about-concurrency
- Java on Cloud Run:
  https://docs.cloud.google.com/run/docs/tips/java
- Pub/Sub pricing:
  https://cloud.google.com/pubsub/pricing
- Authenticated Pub/Sub push subscriptions:
  https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions
- Cloud Run service-to-service authentication:
  https://docs.cloud.google.com/run/docs/authenticating/service-to-service
- Cloud Storage pricing:
  https://cloud.google.com/storage/pricing

Repository companions:

- `docs/GCP-COST-OPTIMIZATION-PLAN-2026-08.md`
- `docs/current-state/project-architecture.md`
- `docs/current-state/gcp-infrastructure.md`
- `docs/current-state/gaps-and-drift.md`
- `scripts/estimate-scale-cost.ps1`
- `load-tests/school-day-read.js`

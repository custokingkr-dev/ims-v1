# Dev Scale Validation - 2026-08-10

## Scope

Environment: `dev`
Project: `custoking`
Region: `asia-south2`
Gateway: `https://custoking-api-gateway-dev-l7mhms5c2a-em.a.run.app`

Production was not modified. The production release remains restricted to `main`, dev-approved
content-addressed images, and the Cloud Deploy canary path.

## Release

Application release commit: `9782a1130564eabfc00591deb16ea1981b895a52`

Release workflow:
https://github.com/custokingkr-dev/ims-v1/actions/runs/31384133108

The workflow completed successfully and:

- detected only the five affected application services;
- built and pushed content-addressed images;
- started/confirmed the dev Cloud SQL instance;
- deployed immutable image digests with the fast dev Cloud Run path;
- verified ready revisions, runtime image digests, and 100% traffic;
- verified gateway health;
- created `dev-approved-src-*` promotion tags;
- uploaded release evidence.

Deployed revisions:

| Service | Revision | Traffic |
| --- | --- | ---: |
| API gateway | `custoking-api-gateway-dev-00143-br2` | 100% |
| Identity | `custoking-identity-service-dev-00146-dg2` | 100% |
| School core | `custoking-school-core-service-dev-00177-k8q` | 100% |
| Platform | `custoking-platform-service-dev-00144-k5x` | 100% |
| Billing | `custoking-billing-service-dev-00144-vkq` | 100% |

The reporting startup log confirms Flyway applied:

```text
Migrating schema "reporting" to version "26 - reporting projection retry lease"
```

Load-harness correction commit: `5ac7c9cdf7efead87931df307d9b6682010f0a24`

Follow-up workflow:
https://github.com/custokingkr-dev/ims-v1/actions/runs/31386279340

This workflow completed successfully as a no-deployment change. It adds ephemeral environment
credentials to the k6 harness and supplies the required attendance date without changing a
deployed service image.

Attendance batching release commit: `90956df835e83f79a8d0db03f8b0559990e36332`

Release workflow:
https://github.com/custokingkr-dev/ims-v1/actions/runs/31388190321

This workflow detected, built and deployed only school core. Revision
`custoking-school-core-service-dev-00176-wvv` serves 100% of dev traffic using immutable digest
`sha256:25e3838867879784052eff4c5eb3597ac5a2e9dd14b3cd2c9e54d36c4ce72548`. The release, gateway
health smoke, runtime-image check and dev-approved digest publication all passed.

Review campaign batching release commit: `275c6d3187f7541c3bbdd5c4221e8a666b7f4258`

Release workflow:
https://github.com/custokingkr-dev/ims-v1/actions/runs/31391901898

This second school-core-only release deployed revision
`custoking-school-core-service-dev-00177-k8q` at 100% traffic using immutable digest
`sha256:33274218f8674b0e11b685104ae914505ebe64052ffb0595a30c4d8f19cb1fcc`. Image, runtime,
gateway health, release evidence, and dev-approved digest checks passed.

Academic-year contention release commit: `8bca2f6c179f21d28b217f05affbdcc93a69d0c1`

Release workflow:
https://github.com/custokingkr-dev/ims-v1/actions/runs/31401679102

The workflow passed all service builds, the serialized Cloud Deploy release, rollout completion,
runtime digest verification, gateway health, and dev-approved image publication. The deployed
school-core revision is `custoking-school-core-service-dev-msnddpe5`, serving 100% of traffic with
digest `sha256:ec402f10c4b0d46389b10062beef0b429f4ea01fa4b7032e40d8b9d41be2dfdd`.
The gateway revision is `custoking-api-gateway-dev-msndnt01`, also serving 100% of traffic.

For the bounded dev scale profile, gateway and school-core maximum instances were raised to four while
minimum instances remained zero. School-core uses a 20-connection pool, giving a theoretical
school-core ceiling of 80 connections (`4 * 20`) against `max_connections=200`. These maximums do
not reserve idle Cloud Run instances.

## Verification Results

### Local affected suites before release

| Suite | Passed | Failed |
| --- | ---: | ---: |
| Identity | 117 | 0 |
| School core | 478 | 0 |
| Platform | 222 | 0 |
| Billing | 52 | 0 |
| API gateway | 58 | 0 |
| Total | 927 | 0 |

Platform used a clean build and successfully applied all 26 reporting migrations to a new
PostgreSQL 16 test schema.

The attendance batching follow-up ran the complete school-core suite: 480 tests passed, with zero
failures, errors or skips. This includes a 120-student register round trip and a duplicate-student
request test that proves validation occurs before any attendance mutation.

After review campaign batching, the complete school-core suite increased to 481 tests and again
passed with zero failures, errors, or skips. A 520-student test crosses the 500-event chunk boundary
and verifies one distinct review item and outbox event per student.

After the academic-year contention correction, the complete school-core suite increased to 482
tests across 70 suites and passed with zero failures, errors, or skips. The new PostgreSQL regression
compares the row's `xmin` before and after repeated current-year resolution and proves that the
read path no longer rewrites an unchanged academic year.

The import retry correction increased the complete suite to 483 tests across the same 70 suites,
again with zero failures, errors, or skips. Its PostgreSQL integration test confirms that confirming
the same preview twice returns the original job/result, creates exactly one student, and leaves the
batch `DONE` instead of overwriting it as skipped.

### Cloud Run release verification

- Five affected revisions ready: pass.
- Exact runtime image digest: pass.
- Latest revision at 100% traffic: pass.
- Gateway `/gateway-health`: `UP`.
- Direct service Cloud Run job: `ims-direct-service-smoke-8xbzx`, success.
- Cloud Run 5xx during the validation window: zero across the five affected services.

### Full authenticated business smoke

The cross-platform smoke wrapper was corrected to send long SQL execution overrides through the
Cloud Run v2 REST API instead of the Windows command line. It also now loads `System.Net.Http`
explicitly for Windows PowerShell and uses a 60-second endpoint ceiling so a cold service is not
misclassified by the former 20-second limit.

Final live dev result:

- authenticated checks: 40/40 passed;
- failures: zero;
- health, identity, tenant-school and RBAC: passed;
- student list/detail/import template and photo upload: passed;
- attendance, fees, catalog and workflows: passed;
- firefighting, reporting, notifications and audit: passed;
- billing and superadmin endpoints: passed;
- real-environment preflight: ready;
- preflight blockers: zero;
- temporary smoke users and student: retired successfully by
  `ims-gateway-smoke-sql-dev-sf5lh`.

After the attendance release, the authenticated smoke and preflight were repeated against the
environment-specific gateway URL. The result again passed 40/40 checks with zero failures and zero
preflight blockers. The wrapper now resolves the current Cloud SQL private IP, database name and
Cloud Run gateway URL from the requested environment. Its SQL job uses shell `pipefail`, preventing
a failed `psql` pipeline from being reported as a successful job.

The missing legacy compatibility artifact remains a warning only for the environment-suffixed
deployment path.

### Refresh-token boundary

Live dev result:

| Operation | Status |
| --- | ---: |
| Login | 200 |
| Protected request with access token | 200 |
| Protected request with refresh token as bearer | 401 |

The test logged out the temporary session after verification.

### Sequential live read probe

Twenty authenticated requests were made to each path:

| Path | Success | Average | p95 | Maximum |
| --- | ---: | ---: | ---: | ---: |
| `/api/v1/students?page=0&size=50` | 20/20 | 55.0 ms | 61.4 ms | 90.3 ms |
| `/api/v1/dashboard/command-center` | 20/20 | 50.4 ms | 54.5 ms | 186.5 ms |

This confirms the database-side student pagination path operates correctly in the deployed dev
revision. It does not substitute for a 10,000-student school dataset.

### Attendance write batching

The deployed repository now executes a section register as a fixed set of database operations
instead of two statements per submitted student:

1. validate all submitted students in one set query;
2. upsert the daily row and obtain its id in one statement;
3. upsert all student attendance rows in one multi-value statement;
4. calculate all four status totals in one filtered aggregate query;
5. update the final daily totals and emit one outbox event.

For a 40-student class, the core save path falls from approximately 90 database statements to five,
excluding the unchanged response-read queries. Invalid and duplicate rows are rejected before the
first write. PostgreSQL integration coverage passed with 120 rows in a single request.

This validates batching correctness and deployment. A controlled concurrent attendance write-load
run remains a fleet-scale gate and must use an isolated synthetic tenant so it cannot alter normal
dev school attendance.

The isolated test tooling is now implemented. Against a disposable PostgreSQL 16 database with the
real tenant-school, student, attendance, and reporting migrations, it generated exactly 100 schools,
300,000 students, a largest school of 10,000 students, and 7,576 sections in 13.51 seconds. The local
database occupied 121 MB, and cleanup completed in 5.49 seconds with zero reserved rows remaining.

The same exact shape was then seeded into live dev on a temporary `db-custom-2-7680` database in
74.76 seconds. A guarded, dev-only identity was created with the fixture and removed by cleanup.
Cleanup completed in 71.71 seconds, and an independent status run confirmed zero reserved schools,
students, sections, and attendance records. No production data or environment was used.

### Student review campaign batching

Campaign initiation previously selected all students and then performed an item insert, item
reread, and outbox insert for each student. For 10,000 students that was approximately 20,001
database statements. The deployed path now uses one `INSERT ... SELECT ... RETURNING` for all review
items, then writes projection events in 500-row chunks. The same 10,000-student case is approximately
21 statements while retaining per-student event identity and transaction atomicity.

### k6 dev read workload

The first run correctly exposed that the new harness omitted the required `date` query parameter
from the attendance summary call. Every attendance call returned a non-5xx client error, while the
other two paths succeeded. The harness was fixed and the result was not counted as an application
capacity failure.

Corrected run:

- virtual users: 25;
- duration: 15-second ramp, 60-second hold, 15-second ramp down;
- flows: student page, command center, attendance daily summary;
- requests: 1,095;
- iterations: 365;
- request rate: 11.48 requests/second;
- checks: 2,190/2,190 passed;
- HTTP failures: 0/1,095;
- average latency: 72.72 ms;
- p90: 133.13 ms;
- p95: 139.30 ms;
- maximum: 323.26 ms;
- configured thresholds: all passed.

Result artifact:
`artifacts/dev-scale-release/k6-25vus-corrected-summary.json`

### Cloud SQL during the corrected test window

Current dev shape: `db-f1-micro`.

| Metric | Average | Maximum |
| --- | ---: | ---: |
| CPU utilization | 8.13% | 9.89% |
| PostgreSQL backends | 1.77 | 6 |
| Reported memory utilization | 100% | 100% |

The low CPU and request latency are encouraging for the small current dataset. Memory saturation
and the shared-core shape mean this environment must not be used to certify the 200,000-300,000
student target. Production-like scale testing requires a temporary dedicated 2-vCPU database and
synthetic data volume.

### Full-volume attendance write stages

The write workload used 100 schools, 300,000 students, a 10,000-student largest school, 7,576
sections, ten independently authenticated tokens, and disjoint per-VU section ownership. Stable k6
metric tags prevent URL-cardinality distortion. Failed reads back off instead of creating a retry
storm. The gateway's current limiter is per bearer token at 50 requests/second with burst 100, so a
single shared token is not representative of independent staff sessions.

| Stage | Requests | Error rate | Attendance write p95 | Attendance write p99 | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| 10 VU diagnostic | 858 | 0% | 66 ms | 79 ms | Pass |
| 100 VU short | 8,360 | 0% | 142 ms | 208 ms | Pass |
| 100 VU sustained, 14 min | 66,944 | 0% | 125.56 ms | 176.87 ms | Pass |
| 300 VU short, after fix | 18,834 | 0.27% | 901 ms | 1.29 s | Pass |
| 300 VU sustained, 9 min | 117,838 | 0.01% | 122.91 ms | 235.06 ms | Pass |
| 500 VU bounded | stopped by safety guard | n/a | n/a | n/a | CPU >80% for two samples |

The clean 300-VU sustained run averaged 217.60 requests/second. It returned 117,822 successful 2xx
responses, six 429 responses and ten 5xx responses; successful attendance saves remained well inside
the 1.5-second write threshold. Peak observed Cloud SQL CPU was 57.4%, memory 50.6%, and application
backends 67/200.

At 500 VUs, Cloud SQL CPU rose from 78.8% to 87.9% and 87.6% in consecutive one-minute samples.
The run was deliberately terminated by a conservative safety guard before the longer 15-minute
production-sizing threshold could be exercised. Memory remained approximately 52% and
application backends 68/200, so CPU—not memory or connection exhaustion—was the first database
constraint. This proves the tested two-vCPU shape supports the clean 300-VU profile with headroom,
but 500 concurrent attendance writers are outside its approved envelope.

The first attempted sustained 300-VU run is excluded from capacity results. The scheduled
`Ops / GCP cost controls` workflow started at `2026-08-10T15:30:45Z` and stopped Cloud SQL during the
test. PostgreSQL recorded administrator-command connection termination. This exposed an operational
race in `set-dev-cloudsql-state.ps1`: it could observe a transient RUNNABLE state before the async
patch operation completed. The helper now waits on the exact Cloud SQL operation id before verifying
the final runtime and activation state.

The pre-fix 300-VU diagnostic also found the principal application bottleneck. About 57 database
sessions waited behind an unconditional
`UPDATE tenant_school.academic_years SET label = ? WHERE id = ?` executed by the nominal read path.
`AcademicCalendar` now returns immediately when the stored label already matches and uses
`IS DISTINCT FROM` for the remaining race-safe updates. Before that correction, 300-VU p95 was about
3.05 seconds and p99 about 4.72 seconds; after it, the sustained write p95 was 122.91 ms.

## Observability Finding

Application logs showed no request-processing exceptions during the validation window. The only
`ERROR` entries were OpenTelemetry exporter timeouts from identity, school core, platform and
billing. These do not appear in request results, but telemetry delivery should be corrected or
reclassified before production so exporter failures do not obscure application errors.

## Student Import Operating Model

Student data imports intentionally remain capped at 500 rows in both the frontend and backend. A
10,000-student onboarding is therefore twenty independently auditable batches. This keeps request
memory, transaction duration, rollback scope, and Cloud SQL bursts bounded without adding Cloud
Tasks, an always-on worker, or another paid queue/compute path.

Confirmation is now retry-safe. It locks the preview batch with `SELECT ... FOR UPDATE`, reuses the
existing job id, and returns the stored completed result—including the admission-number/student-id
mapping needed by the photo step—when a gateway, browser, or operator repeats the request. Concurrent
confirmations serialize on the batch row. An uncommitted failure rolls the batch transaction back;
the same file token can then be retried. This is the selected low-cost explicit batching process,
not an unattended 10,000-row asynchronous import. If schools require a single-file/background SLA,
Cloud Tasks or a request-driven Cloud Run Job remains a later, measured enhancement.

## Incomplete Gates

This dev release is validated for functional deployment, the exact 300,000-student fleet shape,
and a controlled 300-VU attendance-write stage. It is not yet the final production certification.

The following remain required:

1. Four-hour soak at the approved 300-VU ceiling and a separate morning burst.
2. Intentional failure-injection/recovery drill and PITR evidence. The unplanned scheduled shutdown
   proved application reconnection but is not a substitute for a controlled recovery drill.
3. Least-privilege runtime identities and OIDC Pub/Sub push authentication.
4. Query-plan evidence for the long-history attendance/reporting shape and the partition/retention
   decision before tens of millions of attendance-detail rows accumulate.
5. Production database choice and availability decision: two versus four vCPU and zonal cost mode
   versus regional HA.

## Production Gate

Do not begin production deployment before 23:00 IST on 2026-08-10.

Passing the time restriction alone is insufficient. Production remains a no-go until the incomplete
gates above are closed and explicitly reviewed.

Before promotion:

- verify the dev release remains healthy;
- review OpenTelemetry timeout noise;
- verify the exact `dev-approved-src-*` image tags;
- merge/promote the reviewed service content to `main`;
- run the production preflight;
- use Cloud Deploy's `5% -> 25% -> 50% -> stable` sequence;
- stop on any tenant-isolation, migration, error-rate, connection, or latency gate failure.

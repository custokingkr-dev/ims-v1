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
| School core | `custoking-school-core-service-dev-00176-wvv` | 100% |
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

## Observability Finding

Application logs showed no request-processing exceptions during the validation window. The only
`ERROR` entries were OpenTelemetry exporter timeouts from identity, school core, platform and
billing. These do not appear in request results, but telemetry delivery should be corrected or
reclassified before production so exporter failures do not obscure application errors.

## Incomplete Gates

This dev release is validated for functional deployment and a controlled 25-user read workload.
It is not yet the final fleet-scale certification.

The following remain required:

1. Synthetic 10,000-student single-school and 300,000-student fleet dataset.
2. Temporary dedicated Cloud SQL test shape (`db-custom-2-7680` minimum).
3. Controlled attendance write-load scenario against an isolated synthetic tenant (batching is
   implemented and deployed).
4. Asynchronous/resumable student imports and batch review campaign creation.
5. Four-hour soak, 100/300/500-user stages, failure injection and recovery drill.
6. Least-privilege runtime identities and OIDC Pub/Sub push authentication.
7. Production availability decision: zonal cost mode versus regional HA.

## Production Gate

Do not begin production deployment before 23:00 IST on 2026-08-10.

Before promotion:

- verify the dev release remains healthy;
- review OpenTelemetry timeout noise;
- verify the exact `dev-approved-src-*` image tags;
- merge/promote the reviewed service content to `main`;
- run the production preflight;
- use Cloud Deploy's `5% -> 25% -> 50% -> stable` sequence;
- stop on any tenant-isolation, migration, error-rate, connection, or latency gate failure.

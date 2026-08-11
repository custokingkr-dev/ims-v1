# Onboarding, Isolation, Erasure And Cost Certification Evidence

Date: 2026-08-11
Scope: 100-150 schools, 200,000-300,000 total student records, with at least one 10,000-student school.
Environment: onboarding evidence used local JDK 25, Testcontainers PostgreSQL 16 and Docker Desktop;
the sizing section also cites the reliability workstream's synthetic dev load artifacts; no production data.
Decision: the bounded 500-row import path has credible local evidence for a supervised 10,000-student
onboarding, but the system is not yet certified for production rollout. Dev multi-replica, real Cloud SQL,
privacy/offboarding, provider, recovery and operational gates remain open.

This report separates measured evidence from planning assumptions. Local timings are not Cloud Run or
Cloud SQL SLOs. Cost outputs are models, not measurements, invoices, quotes or commitments. Privacy and
telecom notes are engineering gates, not legal advice.

## What Changed In The Repository

### Database-backed import admission control

`StudentImportAdmissionGuard` uses PostgreSQL transaction-scoped advisory locks to enforce:

- at most one active import confirmation per school;
- at most two active import confirmations across the fleet;
- immediate rejection rather than an in-memory queue; and
- automatic lock release on commit, rollback or connection loss.

`StudentReadRepository.confirmImport` first locks the durable batch row and replays an already-completed
result. Only an incomplete confirmation then acquires admission. Consequently, simultaneous retries of one
token remain serialized and a completed retry consumes no fleet slot.

Admission failures are returned as HTTP `429 Too Many Requests` with `Retry-After: 5`, a stable `code`,
`retryAfterSeconds`, and a non-sensitive message. `school_import_active` distinguishes same-school
contention from `import_capacity_busy` fleet contention.

This is effective across Java threads, connection pools and service replicas that use the same PostgreSQL
database. It is not yet dev-certified across two Cloud Run revisions/instances and it does not limit active
preview batches or expire abandoned previews.

The production-path re-audit confirmed the lock is acquired inside the repository's externally invoked
Spring `@Transactional` method. Spring binds the JDBC connection to the transaction and rolls back on the
guard's runtime exception; PostgreSQL transaction-level advisory locks are released at transaction end.
The handler now also detects an admission exception wrapped in `InvalidDataAccessApiUsageException`, so
persistence translation cannot silently turn a deliberate rejection into HTTP 500. Both direct and wrapped
rejections increment `ims.student.import.admission.rejections` with only the closed `reason` dimension
(`school_import_active` or `import_capacity_busy`); school id, token, message and student data are never
metric tags.

[Spring transaction documentation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
documents proxy interception, `PROPAGATION_REQUIRED` and runtime-exception rollback. Its
[JDBC connection documentation](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html)
documents thread-bound transactional connections. PostgreSQL's
[advisory-lock documentation](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS)
distinguishes transaction-level locks from session-level locks. These semantics are why this guard does not
depend on a Java-process-local mutex.

### Reconciliation and privacy improvements

- The import UI can download skipped rows for correction. CSV fields are escaped, UTF-8 BOM is emitted for
  spreadsheet interoperability, and formula-like values are neutralized to prevent spreadsheet execution.
- MSG91 dry-run logging no longer emits the provider payload, student/guardian contact details, OTPs or
  template variables; it logs only the event id and channel.
- Import batch and row evidence now has explicit tenant-RLS integration coverage on a reused physical
  connection, including fail-closed behavior when tenant context is absent and no-op cross-tenant mutation.
- The notification and infrastructure cost scripts now expose overrideable rate/volume assumptions and
  report average-school, per-student and allocated 10,000-student-school figures.
- `GET /api/v1/students/imports/usage` derives a bounded, PII-free daily aggregation from the durable import
  ledger: opaque school id, UTC date, previewed/completed/unfinished batches, attempted/inserted/skipped
  rows and original source bytes. The lookback is clamped to 90 days and output to 20,000 rows. RLS still
  constrains a runtime caller that asks for fleet scope. This closes import attribution only; it is not a
  complete cross-service usage ledger or an exact tenant bill.

The skipped-row file contains student data by design. It is an operator-requested reconciliation artifact,
not an anonymized export; production use still requires authorization, secure handling and an approved
retention/deletion rule.

## Measured Local Certification

### 10,000-student bounded import

Command:

```powershell
.\mvnw.cmd -f services\school-core-service\pom.xml `
  '-Dtest=StudentOnboardingScaleCertificationIntegrationTest' `
  '-Donboarding.scale.certification=true' test
```

Result: 4 tests passed. Test class time was 240.0 seconds; Maven total was 4:15. The authoritative enriched
run executed the production repository path as 20 sequential preview/confirm transactions of 500 rows.

| Measurement | Result |
| --- | ---: |
| Students | 10,000 |
| Batches | 20 |
| End-to-end test duration | 210,762 ms |
| Derived throughput | 47.45 students/second |
| Mean preview-plus-confirm batch | 10,533.45 ms |
| p95 preview-plus-confirm batch | 12,866 ms |
| Slowest preview-plus-confirm batch | 15,079 ms |
| PostgreSQL database size after fixture | 34,225,175 bytes |

The duration includes the completed-token retry and final reconciliation queries. The throughput is a
derived local measurement, not a production capacity promise. An earlier corrected run also passed in
172,037 ms, showing material workstation/container variance; the slower enriched run above is retained as
the reporting baseline.

A final integrated-code repeat also passed all four tests on 2026-08-11: 181,986 ms for the 10,000-row
path (54.95 students/second), 9,094.70 ms mean batch time, 10,085 ms p95, and 10,144 ms slowest batch.
The same-token case completed in 1,105 ms with one job and no duplicates; two concurrent 500-row schools
completed in 4,726 ms. Its in-memory export digest was
`32240920c8e43c06756af2a0b797e831658a58db05b6890ad01b7da997a57bf3`; the digest changes between runs
because the synthetic document embeds the newly allocated school id. Target counts again reached zero and
the control counts remained unchanged. This repeat confirms the implementation but does not replace the
slower baseline or convert local timing into a Cloud Run/Cloud SQL SLO.

Exact reconciliation after retry proved:

- 10,000 student rows and 10,000 distinct admission numbers;
- 20 import batches in `DONE`/100% state;
- 10,000 `Imported` rows with applied student id and time;
- 10,000 current enrollment rows; and
- 10,000 `student.upserted.v1` outbox events.

This proves 20 independent bounded batches. It does not prove the still-planned cross-batch onboarding
session, signed school-level closeout, resumable browser workflow or unattended one-file processing.

### Retry, concurrency, admission and tenant isolation

| Scenario | Local result | What it proves |
| --- | --- | --- |
| Two simultaneous confirmations of one 100-row token | 1,163 ms; same job id; 100 students; zero duplicates | durable idempotent replay after row-lock serialization |
| Two schools confirming 500 rows each | 5,072 ms; both reconcile exactly | two-school concurrency on local PostgreSQL |
| Noisy same school while one transaction holds admission | rejected; another school admitted | per-school lock does not consume the remaining fleet slot |
| Third school while two fleet slots are held | rejected with `import_capacity_busy` | two-slot global bound |
| Admission-holder rollback | later acquisition succeeds | transaction-scoped locks do not leave a stale lease |
| Cross-school token and job access | token rejected and job invisible | repository scoping for the exercised path |
| Import evidence under RLS | tenant A/B isolated on one reused physical connection | GUC reset/reuse does not leak import evidence |
| No tenant context under RLS | zero import batches and rows visible | evidence tables fail closed |
| Cross-tenant RLS update/delete | zero affected rows; control data unchanged | direct runtime-role mutation is tenant constrained |

Focused regression command:

```powershell
.\mvnw.cmd -f services\school-core-service\pom.xml `
  '-Dtest=StudentImportRlsIntegrationTest,StudentRlsIntegrationTest,TenantGucConnectionReuseIntegrationTest,StudentImportAdmissionGuardIntegrationTest,ValidationExceptionHandlerImportAdmissionTest,StudentImportPhotoIntegrationTest' test
```

Result: 30 tests passed, Maven total 39.216 seconds. The narrower admission/API run passed 4 tests in
14.97 seconds. These are Testcontainers results, not a dev multi-replica exercise.

A post-audit targeted run over the direct/wrapped 429 contract, bounded metric dimensions, usage API,
usage aggregation, import RLS, admission locks and existing photo/import regressions passed 47/47 tests
with zero failures, errors or skips. Maven reported 30.279 seconds (32.6 seconds wall clock). This is the
most recent integrated evidence for those paths; it does not replace the separate 10,000-row run or prove
Cloud Run multi-instance behavior.

The complete default school-core suite also reported `BUILD SUCCESS`: 501 tests, zero failures, errors or
skips, Maven total 2:37 on JDK 25. During JVM shutdown, a scheduled outbox task logged a connection refusal
after its Testcontainers database had already stopped and Surefire logged its 30-second self-fork shutdown
guard; neither changed the successful test result, but the lifecycle warning remains an operational test-
harness cleanup issue rather than production evidence.

### Synthetic export and school-core erase rehearsal

The test kept a synthetic 20-student export in memory, serialized it with a schema version and synthetic
marker, and calculated SHA-256
`5ca4daff439ae942e8cbbb86123e3c1a49edd54ee75ad506bb4ba3ba95c62c9a`.
It did not write the export to disk, GCS, Drive or a support system.

| School-core record group | Target before | Target after | Control before | Control after |
| --- | ---: | ---: | ---: | ---: |
| students | 20 | 0 | 20 | 20 |
| enrollments | 20 | 0 | 20 | 20 |
| import rows | 20 | 0 | 20 | 20 |
| import batches | 1 | 0 | 1 | 1 |
| guardians | 0 | 0 | 0 | 0 |
| consent events | 0 | 0 | 0 | 0 |
| tenant-school outbox | 800 | 0 | 800 | 800 |
| sections | 390 | 0 | 390 | 390 |
| school row | 1 | 0 | 1 | 1 |

The target reached zero and the control tenant was byte-for-byte count-stable for the listed groups.
Because guardian and consent counts began at zero, this run does not positively prove deletion of populated
guardian/consent records. It is a dependency-order rehearsal for the school-core schemas only.

It does **not** implement or certify production offboarding. Identity, operations, billing, reporting,
platform projections, object storage, Drive, provider records, logs, exports, caches, backups/PITR,
soft-deleted objects, legal holds, retries, approvals and proof retention were outside the exercise.

## Corrected Monthly Cost Scenarios

### Rate evidence and assumptions

The 2026-08-11 billing pricing export was checked for the current Delhi SKUs used by the model:

- Cloud Run request-based Tier 2 CPU, SKU `085C-A237-027A`: USD 0.0000336/vCPU-second;
- Cloud Run request-based Tier 2 memory, SKU `600C-3782-6708`: USD 0.0000035/GiB-second;
- Cloud Run requests: USD 0.40/million requests above the applicable free tier;
- Cloud SQL Enterprise custom zonal compute in Delhi: INR 4.744053999/vCPU-hour and INR
  0.803428499/GiB-hour; SSD storage: INR 19.511834999/GiB-month;
- Cloud Storage Standard Delhi, SKU `B219-7161-A1AF`: USD 0.023/GiB-month; and
- the model's USD/INR rate input is 95.646 and remains overrideable.

The official [Cloud SQL pricing](https://cloud.google.com/sql/pricing),
[Cloud Run pricing](https://cloud.google.com/run/pricing) and
[Cloud Storage pricing](https://cloud.google.com/storage/pricing) pages describe their respective billing
dimensions. Storage rates vary by location, and operations, network transfer and live/noncurrent/soft-
deleted objects can create separate charges. The script therefore exposes every rate and does not treat
the values as permanent.

Google's [Cloud SQL machine-series documentation](https://docs.cloud.google.com/sql/docs/postgres/machine-series-overview)
states that Enterprise general-purpose custom vCPUs are 1 or an even number from 2 to 96, with 0.9-6.5 GB
memory per vCPU, memory in 256 MB multiples and at least 3,840 MB. Thus `db-custom-4-7680` is a supported
candidate shape. Documentation establishes configuration validity only; it cannot establish workload
headroom, which requires the guarded load result recorded below.

Platform-model workload assumptions:

- 220 school days/year, three years of attendance retained;
- 550 bytes per attendance fact including indexes/bloat planning allowance;
- 25 staff/school, 40 API actions/staff/day, 22 business days/month;
- 2.2 container requests/browser action, 0.30 active second/request, 40% planning margin;
- 100 KiB normalized photo, 100% photo coverage, one stored version;
- zonal and regional-HA SQL alternatives; and
- no free tier, promotional credit or discount deducted.

These assumptions must be replaced by measured dev request duration, actual photo distribution, retention
policy and contracted/billing-account rates before approval.

### Dev sizing correction

The reliability workstream's guarded 300-VU/15-minute morning-burst run on `db-custom-2-7680` stopped after
Cloud SQL CPU remained at or above the 80% stop threshold for three samples. Maximum CPU was 83.52%, memory
usage 48.638% and connections 94. Before the safety stop, k6 recorded 120,816 requests at 245.40 requests/
second, 0.00% HTTP failures, attendance-write p95 445.45 ms and p99 840.14 ms. Passing HTTP latency/error
thresholds does not override the database safety failure: the two-vCPU shape is rejected for the 300-VU
requirement. A lower 200-VU run cannot certify the required 300-VU envelope.

The next tested supported shape, `db-custom-4-7680` (4 vCPU, 7.5 GiB), then passed the complete 17m30s
profile: 30-second ramp, 15-minute 300-VU hold and two-minute ramp-down. Artifact
`morningburst-20260811015047` records k6 exit 0 with no abort, maximum CPU 58.29%, memory usage 48.4233%
and 99 connections. It completed 276,923 HTTP requests at 263.353/second. There were 38 HTTP 429 responses
(0.013722%) and no 5xx response; overall p95/p99 were 110.543/262.731 ms and attendance-write p95/p99 were
115.817/262.703 ms. This is the cheapest tested shape that passed the target burst, not a four-hour soak,
recovery/failover test or production SLO.

For lower concurrency only, artifact `morningburst-20260811010210` kept `db-custom-2-7680` within the
database guards during a separate 200-VU/15-minute proxy: maximum CPU 58%, memory 48.087%, connections 92,
185,060 requests, two HTTP failures, write p95 126.732 ms and printed p99 186.29 ms. Its earlier harness
did not capture the numeric k6 exit code (`null`), so it is retained as a low-end comparison rather than an
exit-certified result. VUs describe synthetic concurrent workers, not student records; neither 200 nor 300
VUs should be equated to the 200,000/300,000 database row totals.

At the checked Delhi list inputs of USD 0.0496/vCPU-hour and USD 0.0084/GiB-hour, `db-custom-4-7680`
compute is USD 0.2614/hour, approximately USD 190.82 or INR 18,251.41 for 730 hours. The rejected two-vCPU
shape is USD 0.1622/hour, approximately USD 118.41 or INR 11,325.09. Selecting the passing shape therefore
adds INR 6,926.32/month zonally or INR 13,852.64 for the doubled regional-HA compute model. Compared with
the earlier untested 4-vCPU/15-GiB assumption, it saves INR 4,398.77 zonally or INR 8,797.54 in the HA model.

### Platform model only

| Scenario | 100 schools / 200k students | 150 schools / 300k students |
| --- | ---: | ---: |
| Attendance rows/year | 44.0 million | 66.0 million |
| Estimated three-year used database | 88.9 GiB | 133.3 GiB |
| Planned provisioned database | 100 GiB | 150 GiB |
| Planning SQL shape | 4 vCPU / 7.5 GiB | 4 vCPU / 7.5 GiB |
| Modeled photo storage | 19.07 GiB / INR 42 | 28.61 GiB / INR 63 |
| Cloud Run planning amount | INR 7,302 | INR 10,954 |
| Zonal platform total | INR 31,881 | INR 37,396 |
| Zonal planning range | INR 25,505-39,852 | INR 29,917-46,745 |
| Zonal average/school | INR 318.81 | INR 249.31 |
| Zonal per student | INR 0.1594 | INR 0.1247 |
| Zonal allocated 10k school | INR 467.92 | INR 398.41 |
| Regional-HA platform total | INR 52,084 | INR 58,575 |
| Regional-HA planning range | INR 41,667-65,105 | INR 46,860-73,218 |
| Regional-HA average/school | INR 520.84 | INR 390.50 |
| Regional-HA per student | INR 0.2604 | INR 0.1952 |
| Regional-HA allocated 10k school | INR 747.99 | INR 617.65 |

The model intentionally excludes SMS/WhatsApp, tax, support labor, domains, photo operations and egress,
extraordinary network egress, free tiers and credits. `db-custom-4-7680` is now the evidence-backed planning
default for the tested target burst, but it is not a certified purchase recommendation until soak,
recovery and production-readiness gates pass. `db-custom-2-7680` remains an explicit lower-concurrency
comparison only. The zonal versus HA choice is a recovery/SLA business decision and must not be made from
cost alone.

### Explicit WhatsApp scenario

This scenario assumes exactly two India utility messages per student/month, no authentication, marketing
or SMS traffic, one shared WhatsApp number, INR 500/number/month and 18% GST.

| Scenario | 100 schools / 200k students | 150 schools / 300k students |
| --- | ---: | ---: |
| Utility messages/month | 400,000 | 600,000 |
| Pre-tax shared-sender total | INR 46,500 | INR 69,500 |
| With 18% GST | INR 54,870 | INR 82,010 |
| Per student | INR 0.2744 | INR 0.2734 |
| Shared-sender average school | INR 548.70 | INR 546.73 |
| Shared-sender allocated 10k school | INR 2,719.90 | INR 2,717.93 |
| School-owned number average school | INR 1,132.80 | INR 1,132.80 |
| School-owned number allocated 10k school | INR 3,304.00 | INR 3,304.00 |
| School-owned-number fleet total | INR 113,280 | INR 169,920 |

The checked public [MSG91 India WhatsApp pricing](https://msg91.com/in/pricing/whatsapp) lists INR 0.115
for utility and authentication, INR 0.8631 for marketing and INR 2.4971 for international authentication,
exclusive of 18% GST. Its [subscription guidance](https://msg91.com/help/whatsapp/whatsapp-subscription)
lists INR 500/month/number after the initial two free months. These are public scenario inputs, not the
project's contract, invoice or delivery forecast. Retries, provider charging outcomes, free service windows,
wallet rules, negotiated rates and template-category mix are not modeled.

Under only the stated two-utility-message/shared-number scenario, platform plus messaging would be INR
86,751 zonal or INR 106,954 HA for 100/200k, and INR 119,406 zonal or INR 140,585 HA for 150/300k. Messaging
dominates the modeled variable bill; reducing duplicate/unwanted sends and deciding shared versus
school-owned sender numbers matters more than photo-storage micro-optimization.

## Guarded Dev Post-Deploy Verification Path

`scripts/verify-dev-import-admission.ps1` defaults to plan-only mode and performs no network request. It
will execute only when all of these are explicit:

- `-Execute`;
- an absolute localhost or HTTPS URL with a distinct `dev` DNS label;
- for remote dev, `-AllowRemoteDevWrites` and an `-ExpectedDevHost` exactly matching the URL host;
- a positive dedicated synthetic school id;
- two different, still-`PREVIEWED` tokens, each containing exactly 500 valid synthetic rows; and
- a short-lived school-admin token in `IMS_DEV_IMPORT_GUARD_TOKEN` (or another explicitly named
  environment variable).

Before writing, it performs a read-only import-ledger preflight for both tokens. It then starts two
same-school confirmations before awaiting either response. Acceptance is exactly one 2xx and one HTTP 429
with `school_import_active`, `Retry-After: 5`, and body `retryAfterSeconds: 5`. The PII-free JSON evidence
contains only host, synthetic school id, timings, status/code/retry values and aggregate inserted/skipped
counts; it never records access tokens, file tokens or response student rows.

Example after the new revision is deployed and the prerequisites have been created by an authorized dev
operator:

```powershell
$env:IMS_DEV_IMPORT_GUARD_TOKEN = '<short-lived school-admin token>'
$env:IMS_DEV_IMPORT_GUARD_FIRST_TOKEN = '<first-preview-token>'
$env:IMS_DEV_IMPORT_GUARD_SECOND_TOKEN = '<second-preview-token>'
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/verify-dev-import-admission.ps1 `
  -GatewayBaseUrl 'https://<exact-dev-gateway-host>' `
  -ExpectedDevHost '<exact-dev-gateway-host>' `
  -SchoolId <synthetic-school-id> `
  -AllowRemoteDevWrites `
  -Execute
Remove-Item Env:\IMS_DEV_IMPORT_GUARD_TOKEN
Remove-Item Env:\IMS_DEV_IMPORT_GUARD_FIRST_TOKEN
Remove-Item Env:\IMS_DEV_IMPORT_GUARD_SECOND_TOKEN
```

Execution intentionally commits one synthetic batch (normally 500 students); the rejected batch remains
previewed. The operator must reconcile the successful batch and clean up the dedicated synthetic fixture
using the approved dev cleanup process. This workstream prepared and locally guard-tested the harness but
did not run it against dev, because no short-lived actor, two fresh preview tokens or cleanup approval was
provided. Parser/plan-only checks passed without network access; negative checks rejected a production-
looking host, missing remote write opt-in, URI user-info, a missing actor token and an existing evidence
path before network access. The existing evidence file's SHA-256 remained unchanged. Cloud Run's official
[concurrency documentation](https://docs.cloud.google.com/run/docs/about-concurrency) confirms multiple
requests and instances can execute concurrently; a single-instance/local result is therefore not the
required multi-replica evidence.

## Completion Ledger

| Master ID | Repository/local status | Code-feasible work still open | External/live gate that cannot be fabricated |
| --- | --- | --- | --- |
| COST-01 | Delhi-SKU platform and explicit messaging models are executable; the cheapest measured passing 4-vCPU/7.5-GiB candidate passed the target burst and the model records its incremental cost; budget remains correctly described as alert-only | complete four-hour soak/recovery evidence and add budget automation after its destination is approved | spending owner approves envelope/recipients/escalation, zonal-versus-HA decision and live budget notification/forecast incident evidence |
| COST-02 | bounded 90-day PII-free import usage aggregation and tenant-scoped API implemented; RLS/local reconciliation passed | extend the same bounded approach to API duration, attendance, storage and provider facts; version allocation/reconciliation | finance approves allocation drivers/tolerance; real provider invoice and billing-export reconciliation |
| DATA-01 | sensitive dry-run logging removed; skipped CSV hardened; 20-row checksummed in-memory export and school-core erase/control rehearsal passed | implement the resumable cross-service/object/provider inventory and positive fixtures for every data class after policy schema is approved | privacy/legal approves notices, lawful basis/guardian consent, retention, legal hold, backup/log lag, export custody and erasure proof |
| ONB-01 | bounded CSV reconciliation and exact local 10k/20-batch certification passed | persistent cross-batch onboarding session, browser-resumable photo phase and signed closeout remain | dev gateway/Cloud SQL timing, disconnect/retry and school-operator acceptance |
| ONB-02 | one-per-school/two-fleet PostgreSQL guard, wrapped/direct 429 contract, low-cardinality rejection metric and guarded dev verifier implemented; local concurrency/RLS passed | active-preview expiry, retry UX/dashboard and continuous negative checks | deploy new revision; provide fresh synthetic tokens/actor/cleanup approval; prove two-instance dev behavior with Cloud SQL telemetry |
| NOTIFY-01 | PII-safe dry-run and overrideable public-rate scenario tool passed | consent/suppression/template enforcement, signed callbacks and provider reconciliation remain | legal category/basis, TRAI/DLT, Meta/MSG91 sender/templates/rate card, consented test recipient and invoice approval |
| PILOT-01 | per-school checklist, waves and stop conditions documented | evidence bundle automation can follow only after upstream gates have stable artifacts | named canary school, contacts/support window, full school-day observation and business/privacy go/no-go |

## Remaining Production Blockers

1. Deploy the admission change to dev and run the 10,000-student fixture through the real gateway and
   school-core service with at least two service instances, Cloud SQL telemetry and no production data.
2. Add the persistent school-level onboarding session/ledger and resumable photo attachment; the current
   proof is 20 independent imports and browser-staged photos are not resumable.
3. Build the approved export/offboarding state machine across all services and external/object systems,
   including backups, soft delete, legal hold, retry, two-person approval and evidence retention.
4. Obtain privacy/legal decisions for minors, notices, purpose/lawful basis, guardian consent, withdrawal,
   retention and rights requests; enforce decisions before photo and notification processing.
5. Complete TRAI principal-entity/header/content/consent-template registration and approve each template's
   category, sender/WABA, signed MSG91 terms, webhook security and consented live pilot.
6. Extend the bounded import attribution to API duration, attendance, storage and provider facts, then
   reconcile the versioned allocation. Current average/large-school figures remain modeled allocations,
   not billable attribution or margin evidence.
7. Complete the four-hour soak and recovery evidence for the 4-vCPU/7.5-GiB planning candidate, then
   approve zonal-versus-HA recovery objectives; the passing short dev burst does not validate failover.
8. Verify the new low-cardinality admission-rejection metric reaches dev telemetry and add the approved
   dashboard/alert; complete monitoring for database connections/CPU/storage, jobs/Pub/Sub, onboarding
   reconciliation, provider results and gross-cost forecast, with named operational owners.
9. Rehearse rollback, backup restore, school support and incident/privacy escalation, then roll out in waves.

Until these gates close, the defensible posture is a small, supervised pilot—not a commitment that 100-150
schools can onboard concurrently or that the current structure is production-certified at 300,000 records.

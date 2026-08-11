# Onboarding, Cost, Compliance And Operations Change Plan

Date: 2026-08-11
Scope: 100-150 schools, 200,000-300,000 total student records, and a maximum expected school size of
10,000 students.
Status: repository and live GCP audit complete; bounded import admission, reconciliation/privacy hardening,
cost tools and local certification evidence implemented. Live dev 20x500/10,000-row reconciliation,
two-distinct-instance admission, final zero-residue cleanup, Pub/Sub drain and SQL downsize/stop passed.
Production mutations, mixed-read capacity, destructive retention decisions, provider activation and legal
decisions remain gated.

Detailed measured results, corrected Delhi rate evidence and the completion ledger are in
[ONBOARDING-CERTIFICATION-RESULTS-2026-08-11.md](./ONBOARDING-CERTIFICATION-RESULTS-2026-08-11.md).

## Final Dev Execution Reconciliation

The remaining dev-only work was executed on 2026-08-11 without production mutation:

- exact 20x500 onboarding inserted 10,000/10,000, skipped zero, reconciled 20 DONE ledgers and exact
  usage deltas, and replayed the completed final batch without duplication in 527.372 seconds;
- temporary min-2/concurrency-1 school revision forced two simultaneous confirmations onto two distinct
  hashed instances: one inserted 500 and one returned the intended 429/Retry-After contract;
- cleanup was extended to billing/firefighting outboxes after the dynamic residue guard found two rows in
  each. The corrected PostgreSQL 16 test and live pass succeeded. A delayed-projection second pass removed
  30 rows; the stabilized third pass removed zero. Reserved-range status and all dev Pub/Sub backlogs are
  zero;
- Cloud SQL is `STOPPED` on `db-f1-micro`, activation policy `NEVER`; all four relay schedulers are
  `PAUSED`; temporary relay/min/concurrency overrides were removed;
- V17 improved student stats/daily plans to 2.118/3.258 ms, but identical MixedMorning comparisons failed
  the sustained CPU gate on 4 vCPU and 8 vCPU. The 8-vCPU run improved p95/p99 to 453.03/936.02 ms but
  reached 99.45% CPU and had three failed checks. Vertical scale is not an approved cost answer.

The cost decision is therefore workload-specific: 4 vCPU remains the cheapest measured full
attendance-write-soak pass, while production mixed-read sizing is unresolved. Define a bounded school-day
arrival rate, enable approved query telemetry, optimize from evidence and rerun before committing spend.

This workstream does not claim that every school has 10,000 students. One hundred schools at that size
would be 1,000,000 students, which conflicts with the stated 200,000-300,000 fleet total. Capacity tests
must preserve both constraints: the total fleet shape and at least one 10,000-student tenant.

## Executive Decision

The current 500-row import is an acceptable low-fixed-cost mechanism for a supervised pilot. A
10,000-student school requires 20 batches. Each batch is auditable and a repeated confirmation of the
same preview token is safe. It is **not** an unattended or genuinely asynchronous onboarding system:
confirmation performs the inserts inside the HTTP request/one database transaction, progress is not
observable while that request is running, and there is no cross-batch onboarding session or resumable
photo phase.

The codebase is therefore ready for a small, operator-supervised pilot only after the production gates in
this document are closed. It is not ready to promise one-file, unattended, concurrent onboarding across
100-150 schools.

The most serious non-performance blockers are:

1. notification delivery does not enforce the student's/guardian's consent or notification preference;
2. permanent photo/student/school offboarding and data-export policies do not exist;
3. only bounded import usage attribution exists; cross-service cost attribution and invoice reconciliation do not;
4. database-backed per-school/two-fleet import admission is deployed and passed a live 500+500 proof on two
   distinct hashed Cloud Run instances; active previews and stale-preview expiry remain unbounded;
5. provider templates, DLT/consent classification, live MSG91 receipts, and commercial pricing are not approved;
6. the INR 5,000 budget is an alert, not a spending cap, and is below the production planning envelope;
7. SQL connection, Pub/Sub, job, and budget-specific alert coverage remains incomplete even though the
   general Monitoring policy inventory is healthy.

## Evidence Boundary

The initial evidence-boundary audit used the branch and live project `custoking` without starting/stopping
resources, changing IAM, changing a budget, deploying, or running production writes. Subsequent coordinated
work deployed only to dev and ran the bounded synthetic probe documented below; production was not invoked.

### Verified repository behavior

- Backend `StudentReadRepository.previewImport` rejects more than 500 rows.
- The frontend independently rejects more than 500 rows and uploads at most 50 MB.
- Preview persists the normalized/raw rows, validation result, school id, filename, SHA-256, size,
  content type, uploader, and private object path when storage is configured.
- Confirmation locks the batch `FOR UPDATE`, reuses its job id, and returns the prior completed result
  on retry. `StudentImportPhotoIntegrationTest.confirmImport_retryReturnsOriginalResultWithoutDuplicatingOrCorruptingBatch`
  covers the repeat-confirm case.
- Admission number is unique per school in PostgreSQL. Two different batches that race for the same
  admission number cannot both create a student; one row is recorded as skipped. Confirmation now also
  uses transaction-scoped PostgreSQL advisory locks for one active confirmation per school and two
  confirmations fleet-wide. It is now deployed and exercised through the dev gateway; the retained evidence
  does not identify distinct Cloud Run instances, so multi-replica certification remains open.
- Confirmation sets `RUNNING`/20%, then loops through as many as 500 rows and commits `DONE`/100% in the
  same transaction. The returned job id does not make this background work.
- Batch and row evidence can be read using `GET /api/v1/students/imports/batches` and
  `GET /api/v1/students/imports/rows`, but the current operator UI has no cross-batch onboarding ledger.
- Photo attachment from the workbook is a second browser-driven phase after students commit. It uses
  four concurrent requests per browser. A photo failure is reported but does not roll back the student.
  Closing the browser loses the staged photo work; there is no resumable attachment job for this path.
- The separate Drive photo-import workflow has persisted batch/row state and its own runbook, but is not
  a replacement for a 20-batch student-data onboarding session.
- School readiness is derived from an active administrator plus a `READY` current-year Drive folder.
  It does not include data-quality sign-off, consent completeness, import reconciliation, sender approval,
  billing setup, or a go-live approval.
- The API gateway has an in-memory token bucket keyed by bearer token (or client IP), default 50 RPS and
  burst 100. It is not school-aggregate protection and each gateway replica has its own bucket.
- Permanent student records are soft-deleted in the application. There is no school-offboarding workflow
  and no coordinated erase/export covering all schemas, projections, outboxes, backups, logs, photos,
  Drive folders, identities, and provider records.
- Consent is an append-only, school-scoped event ledger with `STUDENT_PHOTO`, `ID_CARD_PRODUCTION`,
  `SCHOOL_COMMUNICATIONS`, `APAAR_REGISTRATION`, and `DATA_CORRECTION` purposes. Photo upload and MSG91
  delivery do not consult the effective ledger. `student_guardians.receives_notifications` is also not
  checked by the delivery provider.

### Verified live GCP state at 2026-08-11

| Control | Verified state | Consequence |
| --- | --- | --- |
| Production Cloud SQL | PostgreSQL 16, `db-g1-small`, zonal, 10 GiB SSD, auto-resize, RUNNABLE | Low fixed cost, but production sizing/HA remains a business decision |
| Recovery policy | PITR enabled, 7 transaction-log days, 14 retained backups, deletion protection | Good baseline; retention/legal scope and drill cadence still need owners |
| SQL maintenance/insights | No maintenance window or Insights configuration returned in the live describe | Schedule and query-evidence gate remains open |
| Cloud Run | All service min instances 0; prod gateway max 3; other prod services max 2; concurrency 80 | Cost-aware, but HTTP concurrency is not proof of DB/import capacity |
| Database pools | Five Java services default to max 5 connections per instance/min idle 0 | Normal service ceiling is about 50 connections at prod max replicas before jobs/admin sessions; validate against the live DB ceiling |
| Billing export | `billing_export` has standard, detailed-resource, and pricing tables | Service/resource reporting exists; reporting latency is expected |
| Budget | INR 5,000/month, project scoped, excludes all credits; current 50/80/100% plus forecast 100% thresholds; no Pub/Sub topic and no explicit Monitoring recipient in the budget | Correct gross-cost behavior, but not a hard cap; it currently relies on default IAM email recipients and the runbook's 75/90% text is stale |
| Monitoring | 99 enabled policies; all reference the one enabled production operator-email channel | General policy wiring exists; specialized SQL connections/memory, Pub/Sub, jobs, trace, and cost/budget alerts remain gaps |
| Cloud Scheduler | API disabled | Scheduled work currently depends on GitHub Actions/manual execution; do not invent Scheduler-backed guarantees |
| Photo storage | Dev 10,296,118 bytes; prod 2,340,094,800 bytes; private/PAP/UBLA; 7-day soft delete | Permanent minor photos have no approved expiry/deletion policy |
| Photo temporary lifecycle | 14-day delete on `temporary/photo-imports/` and a few exact known legacy temporary batch prefixes | Good for the current isolated prefix, but GCS prefix matching has no wildcard: future `schools/<id>/student-imports/<batch>/` originals are not generically expired |
| Logs | `_Default` 7 days, `_Required` 400 days locked, India compliance bucket 180 days | Cost-aware operational logs; legal owner must approve the 180-day compliance selection |
| Artifact Registry | `custoking` is about 24.5 GB; delete any tag state older than 7 days and keep the latest 3 versions per package | Cost control exists, but there is no explicit keep rule for deployed/rollback tags; current prod digests exist and are tagged, yet a tag alone does not override the `ANY` delete condition |
| Platform notification delivery | `NOTIFICATION_DELIVERY_PROVIDER=logging`, `MSG91_DRY_RUN=true` in dev and prod | No claim of real MSG91 delivery is valid |
| Utility Cloud Run jobs | Most inspected SQL/seed/fixture jobs still use the default Compute service account | Least-privilege migration remains outside this workstream's live-write authority |

### Current billing snapshot

The detailed export query for invoice month `202608` returned approximately INR 2,854 gross at the time
of inspection: Cloud Run INR 1,215, Cloud SQL INR 1,194, Cloud Build INR 307, Artifact Registry INR 76,
Secret Manager INR 54, and small remaining services. Promotional credits offset the observed gross rows,
but the configured budget correctly excludes credits. Billing export has reporting latency and this is not
an invoice or forecast.

Most rows were `unlabelled`; only a small Cloud Run subset carried `env=prod`. Current Cloud Run resources
do have `app`, `env`, `service`, `owner`, and cost-center labels. Labels affect future supported billing
records and do not retroactively repair prior usage. More importantly, labels cannot split a shared Cloud
Run revision, shared Cloud SQL instance, or shared Pub/Sub topic by school.

## Repository Changes Completed In This Workstream

### IMPL-01 — Downloadable skipped-row reconciliation

`BulkImportPanel` now offers **Export skipped rows** after confirmation. The CSV includes row number,
name, admission number, class, section, phone, status, and failure reason; values are escaped and the file
has a UTF-8 BOM for Excel interoperability. A unit test covers quotes, commas, and line breaks.

This closes only the immediate failed-row handoff. It does not provide a 20-batch school-level ledger,
re-upload automation, or an administrative history screen.

### IMPL-02 — Remove sensitive MSG91 dry-run payload logging

Dry-run delivery previously logged the full provider body, potentially including a guardian phone/email,
student name, OTP, and fee/attendance variables. It now logs only event id and channel. The provider still
builds the payload in memory so configuration validation is retained.

This does not retroactively erase logs and does not implement consent enforcement or recipient suppression.

### IMPL-03 — Notification unit-cost scenario tool

`scripts/estimate-onboarding-notification-cost.ps1` models shared versus school-owned WhatsApp numbers,
utility/authentication/marketing messages, optional SMS, and an explicit tax percentage. Its defaults use
the public India rate snapshot checked on 2026-08-11, but every rate must be replaced by the contracted
provider rate before commercial commitment.

Example, deliberately with usage assumptions supplied by the operator:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/estimate-onboarding-notification-cost.ps1 `
  -SchoolCount 150 `
  -StudentCount 300000 `
  -UtilityMessagesPerStudentMonth 2 `
  -WhatsappNumberCount 1 `
  -TaxPercent 18
```

The tool does not guess how many messages the product will send.

### IMPL-04 — Database-backed import admission

`StudentImportAdmissionGuard` uses PostgreSQL transaction-scoped advisory locks to allow one active
confirmation per school and two active confirmations fleet-wide. It returns `429` with `Retry-After: 5`
when the school or fleet is busy. Locks release automatically on commit, rollback or connection loss; no
unbounded in-memory queue or new always-on service was added.

The guard is acquired after the batch row lock and completed-result replay, so same-token retries remain
durable and completed retries consume no admission slot. Local Testcontainers tests cover noisy-school
rejection, use of the second slot by another school, third-school rejection and rollback release. Dev
deployment and the same-school live contract are complete; distinct-instance evidence, preview expiry,
monitoring, fixture cleanup and retry UX remain open.

### IMPL-05 — Tenant isolation and scale certification harness

The opt-in `StudentOnboardingScaleCertificationIntegrationTest` exercises twenty real 500-row
preview/confirm transactions, exact reconciliation, completed and simultaneous same-token retries, two
concurrent schools, cross-school token/job denial and a synthetic school-core erase rehearsal. A separate
RLS test covers import batches/rows across one reused physical connection, missing tenant context and
cross-tenant update/delete attempts.

The exact opt-in post-V16 PostgreSQL 16 run passed all 4 tests in 201.5 seconds of test-class time and 3:26
Maven total. The 10,000-student path completed in 177,998 ms at a derived 56.18 students/second, with an
8,894.35 ms mean batch time, 10,191 ms p95, 10,225 ms slowest batch and 35,314,711-byte database after the
fixture. This supersedes the older local timing baseline. It is still local PostgreSQL evidence—not a Cloud
SQL/Cloud Run SLO or a substitute for the persistent school-level onboarding session. The later live guarded
10,000-student gateway proof passed in 527.372 seconds with exact ledger/usage reconciliation.

### IMPL-06 — Corrected, overrideable platform cost model

`scripts/estimate-scale-cost.ps1` now exposes Cloud SQL, Delhi Cloud Run Tier 2, Delhi Standard Storage,
exchange-rate, workload, photo and planning-floor inputs. It reports zonal/HA totals, ranges, per-school,
per-student and allocated 10,000-student-school values. The 2026-08-11 defaults use the checked billing
pricing export, including Cloud Run CPU USD 0.0000336/vCPU-second, memory USD 0.0000035/GiB-second and
Delhi Standard Storage USD 0.023/GiB-month. The SQL default is now the cheapest measured candidate that
passed the full target 300-VU soak, `db-custom-4-7680`; custom vCPU/RAM inputs remain overrideable and are validated
against Google's Enterprise custom-shape constraints. The output separately labels the rejected 300-VU
two-vCPU shape, its limited 200-VU comparison, incremental zonal/HA compute and savings versus the earlier
untested 4-vCPU/15-GiB assumption. These remain dated planning inputs, not a quote.

The first decisive reliability artifact was `morningburst-20260811015047`: the 4-vCPU/7.5-GiB database completed
the full 17m30s 300-VU profile with k6 exit 0/no abort, maximum CPU 58.29%, memory 48.4233%, connections 99,
276,923 requests and 0.013722% failures (38 HTTP 429 responses, no 5xx). Attendance-write p95/p99 were
115.817/262.703 ms. This established the short-burst candidate before the later full-soak pass; neither
artifact is a production SLO or failover test.

The separate `morningburst-20260811010210` 200-VU comparison on 2 vCPU/7.5 GiB stayed below resource
guards (maximum CPU 58%, memory 48.087%, connections 92) with two failures among 185,060 requests, but its
older wrapper recorded a null k6 exit code. It remains a lower-concurrency cost option only; it does not
override the same shape's 300-VU CPU failure, and VUs are not student totals.

The first full 300-VU soak on `db-custom-4-7680` stopped after 2h28m when three consecutive Cloud SQL CPU
samples reached 82.1477%, 81.3688% and 82.2645%. The subsequent mixed workload also failed with 13.0426%
HTTP failures and overall p95/p99 of 55.015/59.998 seconds. Source fixes are deployed and the exact synthetic
backlog was removed with an idempotent second cleanup pass. These failures remain historical evidence.

The corrective artifact `soak-20260811074848-evidence.json` passed the complete 4h10m 300-VU profile with
k6 exit 0, no abort, all thresholds passing, 2,088,063 completed/zero interrupted iterations, and 4,178,728
requests at 278.570 requests/second. Four requests returned 429/failed (0.0000957%); no attendance-write
server-error check failed. Overall p95/p99 were 108.536/238.370 ms and attendance-write p95/p99 were
74.591/116.766 ms. Maximum Cloud SQL CPU was 54.22%, memory 46.8998% and connections 81. This is the cheapest
measured full-soak pass and therefore the cost-model planning default, not production purchase, HA/SLA or
business approval. The rerun artifact `mixedmorning-20260811121235-evidence.json` subsequently **failed**:
the guard aborted with k6 exit 105 after three consecutive Cloud SQL CPU samples of 82.46%, 100% and 100%.
Before abort it completed 28,317 requests; 275 failed (0.971148%), including 227 4xx responses and one 5xx
response, while overall p95/p99 reached 8.217/20.247 seconds. Maximum memory was 47.0998% and connections 85.
This does not revoke the complete attendance-write soak pass; it leaves the representative mixed-read
capacity gate failed and production sizing unapproved. The guarded live 10,000-student and distinct-instance
runs later passed; final scoped cleanup and Cloud SQL downsize/stop also completed.

### IMPL-07 — Bounded import usage attribution and admission telemetry

`GET /api/v1/students/imports/usage` derives daily facts from the durable import ledger: opaque school id,
UTC day, previewed/completed/unfinished batches, attempted/inserted/skipped rows and original source bytes.
The lookback is clamped to 90 days and output to 20,000 rows. Runtime RLS still limits a caller that requests
fleet scope, and the response contains no token, filename, student, phone or row payload.

Direct and persistence-wrapped admission rejections now increment
`ims.student.import.admission.rejections`, tagged only by the closed reason code. Existing HTTP telemetry
covers successful confirmations. School ids are deliberately absent from custom metric dimensions; daily
ledger aggregation provides tenant attribution without high-cardinality Monitoring series. This completes
import attribution only—not the still-planned API/attendance/storage/provider ledger or exact tenant billing.

The endpoint and commit `467cd4f8`'s fleet-query null-parameter correction are deployed on school-core
revision `custoking-school-core-service-dev-00186-5pz`. The 40-route post-roll-forward and 40-route post-TLS
gateway suites passed, but neither suite directly calls `/imports/usage`; a retained direct endpoint and
metric-export artifact remains required before COST-02 is called dev-certified.

### IMPL-08 — Guarded dev admission verifier

`scripts/verify-dev-import-admission.ps1` is plan-only by default. Remote execution requires an HTTPS host
with a distinct dev label, exact expected-host match, explicit remote-write acknowledgement, a dedicated
synthetic school, two distinct 500-valid-row preview tokens and a short-lived school-admin token supplied
through an environment variable. It performs a read-only ledger preflight, starts both confirmations before
awaiting either, and accepts exactly one 2xx plus one `school_import_active` 429 with `Retry-After: 5`.

The artifact contains no tokens or student rows. Execution intentionally commits one synthetic batch, so
cleanup approval and reconciliation remain prerequisites. The harness was parser/guard tested locally and
then executed through the real dev gateway. Artifact
`artifacts/onboarding-certification/import-admission-live-20260811072816471.json` passed in 16.32 seconds:
one confirmation returned HTTP 200 with 500 inserted/zero skipped, and one returned HTTP 429
`school_import_active` with header/body retry values of five seconds. It contains no token or student PII and
sets `cleanupRequired=true`. Because it does not record instance identity, it is not multi-instance evidence.

### IMPL-09 — Guarded full 10,000-student gateway verifier

`scripts/verify-dev-onboarding-10000.ps1` is plan-only by default and has not been live-run. Remote writes
require `-Execute`, `-AllowRemoteDevWrites`, HTTPS, a distinct dev DNS label, exact expected-host equality
and a reserved school id of at least `900000000`. It accepts authentication only from named environment
variables containing either a short-lived token or a complete login email/password pair, never both. No
credential, token, individual row value or opaque batch/job identifier is written to evidence. A non-secret
synthetic run label is retained only for exact reconciliation and cleanup scoping.

Before any preview or import write, the verifier reads the target school's classes and active sections. It
requires all `Scale Class 1..12` and `Scale 0001..0250` names and rejects a duplicated required section name.
An incompatible target fails with the explicit fixture-preflight error before any import write. Successful
evidence contains only aggregate preflight counts/timing and records `writesPerformed=false` for that phase.
For the current 100-school fixture, full-10k verification must use school `900000000`, the only seeded school
with all 250 sections. School `900000001` has 2,930 students and only `Scale 0001..0074`, so it is incompatible
with the full-10k generator and is rejected before writes. It remains suitable for a separate 500+500 proof,
which requires only `Scale 0001..0013`.

The fixed workload is 20 sequential JSON previews and confirmations with exactly 500 unique synthetic rows
per batch, using existing SCALE fixture class/section names. Each preview must be 500 valid/zero errors/zero
warnings; each confirmation must be 500 inserted/zero skipped. The final batch receives a completed same-token
retry and must preserve its batch/job result. The verifier then matches all 20 generated batches as `DONE`
and requires exact `/imports/usage` deltas of 20 previews/completions and 10,000 attempted/inserted rows, with
zero unfinished, skipped or source bytes.

Evidence is created only after every assertion passes, using atomic create-new/no-overwrite semantics. It
contains only aggregate timings, counts, the reserved school id and explicit limitations, and always records
`cleanupRequired=true`. The verifier intentionally performs no cleanup and does not claim photo, empty-school,
parallel-school, distinct-instance, capacity or production certification. The narrow
`scripts/audit-dev-onboarding-verifier.ps1` parser/source/plan audit and four pre-network negative gates pass.

## Planned Changes

Each item states the evidence needed to close it. “Implemented” means merged, deployed to dev, and tested
unless the item explicitly says documentation-only. Local source changes alone are not completion.
Lettered subsections decompose one master-ledger item; they do not create new master IDs. The completion
ledger at the end of the certification report is the authoritative master-ID reconciliation.

### ONB-01a — School-level onboarding session (**production blocker**)

**Change:** add a school-scoped onboarding session that owns the expected batch count, source file names
and hashes, operator, start/end times, import batch ids, student counts, duplicate/skipped counts, photo
counts, reconciliation status, and approval history. Keep the 500-row atomic batch. Do not create a second
student store.

**Implementation shape:** a new `student.onboarding_sessions` plus `student.onboarding_session_batches`
schema, tenant RLS, unique `(school_id, source_hash)` or an explicit repeat policy, read/create/attach/close
APIs, and an operator UI. Closing a session must compare expected rows against inserted, intentionally
skipped, corrected, and unresolved rows.

**Acceptance:** a 10,000-row fixture completed as 20 batches produces exactly one signed-off session;
all rows reconcile; retries do not increase the total; a second school cannot read or attach a batch; an
unfinished session is visible after browser restart.

**Cost:** negligible database metadata. Avoid a new always-on service.

**Dependencies/blockers:** product must define who can close/reopen, whether repeat file hashes are allowed,
and the evidence retention duration.

### ONB-01b — Honest progress and optional background execution

**Change:** first rename current status as synchronous batch confirmation and show an indeterminate
“confirming batch” state. Add true background work only if the pilot proves the request exceeds the gateway
timeout or the business requires unattended one-file onboarding. The preferred measured upgrade is a
request-driven Cloud Run Job or Cloud Tasks worker with idempotent chunks, not an always-on worker fleet.

**Acceptance for current mode:** 500-row confirmation stays within the agreed p95/p99 timeout under two
concurrent school imports and reports a final durable batch; a request disconnect can safely be retried.

**Acceptance for future background mode:** durable queued/running/completed/failed states, heartbeat,
bounded retries, idempotent chunks, cancel semantics, dead-letter/replay, per-school concurrency one, global
concurrency configured from DB evidence, and restart recovery.

**Cost:** current mode adds none. Cloud Tasks/Run Jobs introduces request/execution charges but remains
scale-to-zero. Do not enable Cloud Scheduler just to simulate a worker.

**Dependencies/blockers:** pilot timing evidence and a product SLA. It is premature to add queueing only
because 10,000 is a large number.

### ONB-02 — Import admission control and noisy-tenant protection (**production blocker**)

**Current status:** the confirmation guard, direct/wrapped deterministic `429` contract, bounded-reason
rejection counter and guarded dev verifier are implemented, locally tested and deployed to dev. A live
same-school 500+500 contention probe passed against Cloud SQL. Remaining blockers are distinct-instance
evidence, live-fixture reconciliation/cleanup, preview expiry, route-level budgets, operator retry UX,
metric-export verification and an approved dashboard/alert.

**Change:** enforce at most one active confirmation per school and begin with at most two active imports
fleet-wide. Return `409`/`429` plus `Retry-After`; never queue unbounded work in application memory. Keep
the same-file `FOR UPDATE` protection. Add limits for active preview batches and stale preview expiry.

Gateway rate limiting must evolve from per-token/per-instance protection to a school-aware, distributed or
database-backed budget for expensive routes. Cheap reads and bulk writes require different limits.

**Acceptance:** one school launching many users/import tokens cannot exhaust the school-core five-connection
pool, cannot push another pilot school's p95 over the agreed threshold, and receives deterministic retries.
Admission control continues to work with two gateway/school-core replicas.

**Cost:** small database coordination cost. Avoid Redis until contention evidence shows PostgreSQL advisory
locks/leases are insufficient.

**Dependencies/blockers:** choose route weights, retry UX, and the allowed number of simultaneous onboarding
schools from a dev test, not intuition.

### ONB-01c — Resumable photo reconciliation

**Change:** require an effective `STUDENT_PHOTO` processing decision before upload; persist the planned
photo source, checksum, final object key, result, reason, and retry count. Resume attachments by import
batch/session without keeping image bytes only in browser memory. Separate missing consent, missing photo,
invalid image, source unreachable, and storage failure.

**Acceptance:** browser termination after 250/500 photos resumes without duplicate student-photo
misattachment; admission number and student id are revalidated within the same school; consent withdrawal
blocks new processing and initiates the approved retention action; every object is attributable to school,
student, source, consent/other lawful basis, and session.

**Cost:** normalized 512px JPEGs keep steady storage/egress low. Temporary sources remain on the isolated
14-day prefix. Permanent lifecycle must not be enabled until policy is approved.

**Dependencies/blockers:** legal/product decision on lawful basis, evidence and permanent-photo retention;
Google Drive ownership/offboarding rules.

### DATA-01a — Retention schedule and enforcement (**production blocker**)

**Change:** approve a data-class schedule for students, guardians, consent events, attendance, fees,
imports, original source files, current/old photos, notifications, audit events, app logs, compliance logs,
traces, backups, and support/release artifacts. Specify purpose, system of record, owner, trigger, active
retention, backup/log lag, legal hold, deletion/anonymisation action, proof, and exception process.

**Acceptance:** automated tests map each class to code/storage; lifecycle rules apply only to approved
prefixes; a dev deletion drill proves object/database/projection/provider coverage; backups and immutable
logs have documented delayed-erasure behavior; legal/security approve the schedule.

**Cost:** deleting temporary and obsolete approved data lowers storage/log cost. Legal holds and longer
retention increase it. No blanket permanent-photo lifecycle is safe without policy.

**Dependencies/blockers:** this is a legal/business decision. Engineering cannot infer a retention period
for minors' personal data.

### DATA-01b — School offboarding and data-subject operations (**production blocker**)

**Change:** implement a two-person, resumable state machine:

```text
REQUESTED -> EXPORTING -> ACCESS_FROZEN -> RETENTION_HOLD -> ERASING -> VERIFIED -> CLOSED
```

Inventory and handle identity assignments/tokens, school-core schemas, operations, billing, reporting
projections/inboxes/outboxes, notifications/provider records, photos/import objects, Drive folders, audit
evidence, logs, backups, and support artifacts. Never use an ad-hoc cross-schema delete script in prod.

**Acceptance:** a synthetic school export is readable and checksummed; access is revoked before erasure;
two approvals are recorded; the erasure is idempotent; post-erase tenant searches return zero active data;
retained legal/audit records are enumerated with expiry; object inventory and provider suppression are
verified; no other tenant changes.

**Cost:** request-driven job executions and temporary encrypted export storage. Apply short, approved export
TTL plus soft-delete implications. Do not retain exports indefinitely.

**Dependencies/blockers:** contract termination terms, controller/processor roles, legal-hold rules,
export format, custodian, and backup-erasure policy.

### DATA-01c — DPDP readiness for children and consent

The DPDP Act's main processing obligations, including children's-data provisions, are scheduled under the
official 13 November 2025 commencement notification for eighteen months after Gazette publication. That
phasing does not justify delaying design. Obtain counsel's interpretation rather than treating this document
as legal advice.

**Change:** versioned plain-language notices; guardian identity/verifiable-consent evidence where required;
purpose/lawful-basis decisions; withdrawal as easy as grant; rights-request intake and status; data
correction/export/erasure workflows; processor register; breach workflow; age/guardian rules; and consent
enforcement at photo/communication boundaries. A ledger that is never checked is evidence storage, not enforcement.

**Acceptance:** denied/withdrawn/expired consent blocks the applicable action; grant cannot be reused for a
different purpose; audit shows notice version and actor; rights requests meet counsel-approved timelines;
security/privacy complete a tabletop exercise before the effective date.

**Cost:** mostly development/operations. Verifiable guardian identity and support processes can have third-party costs.

**Dependencies/blockers:** qualified legal/privacy owner and policy decisions.

### COST-02 — Per-school usage ledger and allocation (**production blocker for commercial margin reporting**)

**Partial local status:** a tenant-safe daily import aggregation now exposes batch state, attempted/
inserted/skipped rows and source bytes for at most 90 days/20,000 result rows. It derives idempotently from
the durable import ledger and avoids school-id metric tags. API duration, attendance, object/photo and
provider facts, allocation versioning and invoice reconciliation remain open; the endpoint is not an exact
bill or a substitute for finance-approved allocation.

**Change:** write append-only, idempotent daily usage facts keyed by school and source event/request:

- active students and staff-days;
- API request count and measured request duration by route class;
- attendance writes and report exports;
- import rows/source bytes/photo bytes/object count;
- notification attempts, provider-accepted and delivered count by channel/category/template;
- support-intensive/manual operations where commercially relevant.

Generate a monthly allocation using explicit drivers: direct provider costs first; school-owned object
bytes next; shared Cloud Run/SQL/observability cost allocated by documented usage weights. Store allocation
model version and do not represent an allocation as an exact GCP invoice.

**Acceptance:** all facts are tenant-scoped and idempotent; provider invoice totals reconcile within an
approved tolerance; GCP allocated totals reconcile to the billing export; unallocated cost is visible;
backfill/recalculation preserves versions; finance approves the formula.

**Cost:** modest PostgreSQL/BigQuery storage and scheduled query cost. Aggregate daily; do not log one
high-cardinality metric series per school in Cloud Monitoring.

**Dependencies/blockers:** finance chooses allocation drivers and margin policy; billing export latency
means the report is not real time.

### COST-01 — Budget and forecast guardrails

**Change:** retain the gross-cost budget, correct the runbook to its live 50/80/100 plus forecast-100
thresholds or intentionally change both, verify recipient delivery, and add a forecast policy at the
approved production envelope/125% escalation. Add Cloud SQL and Cloud Run service views. Never auto-disable
billing for production student systems.

Google documents that alert budgets do not cap spend. A Cloud Run spend cap is currently Preview and does
not stop persistent SQL/storage cost; it is inappropriate as the primary production safety control without
an outage decision.

**Acceptance:** a test notification reaches engineering and the spending owner; owner/acknowledgement SLA
is recorded; forecast query uses gross cost; the response matrix says what can be throttled/stopped and what
must remain available; no action can stop production SQL automatically.

**Cost:** budget alerts are operational controls; BigQuery queries are expected to be small. Higher budget
amount changes alerts, not actual spend.

**Dependencies/blockers:** approve the real monthly envelope after production DB/HA decision and provider volume model.

### COST-01a — Storage, logs, traces, backup and build retention

**Change:** keep `_Default` at 7 days and the temporary photo lifecycle; sample traces at the lowest rate
that preserves incident diagnosis; expire release/build artifacts according to rollback policy; review
soft-deleted bytes; add permanent-object rules only after DATA-01. Classify the 180-day compliance bucket
filter and retention with legal/security. Review backup cost after steady data growth, not by disabling PITR.

Bulk student-import originals currently use `schools/<school>/student-imports/<batch>/...`; the bucket's
current exact legacy prefixes do not cover future dynamically named batches. Choose one implementable policy:
write all future disposable originals under a dedicated stable prefix such as `temporary/student-imports/`,
or have an evidence-driven cleanup job enumerate closed sessions and delete only approved source objects.
Do not broaden the lifecycle to `schools/`, because that prefix also contains durable student photos.

Artifact Registry must add a conditional KEEP policy for the exact release-tag convention (for example,
approved production/deployment tags) or otherwise record and protect all digests in the rollback window.
Google documents that a KEEP match overrides DELETE; the current latest-three rule alone does not prove an
older production rollback digest is retained after repeated dev builds. Test the revised policy in dry-run
before activation.

**Acceptance:** monthly inventory reports bytes/object count by bucket/prefix and log bucket; no unbounded
temporary source prefix; every deployed and approved rollback digest is matched by a KEEP rule and survives
a cleanup dry-run; restore/release windows still work; no sensitive payload is intentionally logged;
retention configuration matches the approved schedule.

**Cost:** lower variable storage/observability cost. Seven-day soft delete still charges and delays physical removal.

**Dependencies/blockers:** legal retention, rollback window, and recovery objectives.

### QUOTA-01 — Quota and capacity ledger

**Change:** check Cloud Run regional CPU/memory/instance/job quotas, Cloud SQL connection/storage/IO ceilings,
Pub/Sub publish/delivery quotas, Secret Manager and Storage API usage before each onboarding wave. Record
current use, limit, headroom, owner, increase lead time, and fail-safe. Cloud Run reports this project's
quota increase eligibility as `NOT_ENOUGH_USAGE_HISTORY`; a requested increase cannot be assumed.

**Acceptance:** peak model plus 50% headroom fits confirmed quotas; import/burst test stays within max
instances and DB connections; quota alert/playbook exists; required requests are approved before the wave.

**Cost:** quota increases do not themselves force spend, but higher autoscaling limits increase cost risk.

**Dependencies/blockers:** current effective quota values and usage history; do not copy generic defaults
from documentation into the project ledger.

### NOTIFY-01a — Consent-aware notification gateway (**production blocker**)

**Change:** before provider submission, resolve school, recipient, channel, purpose/category, active guardian
relationship, `receives_notifications`, effective consent/other approved basis, opt-out/suppression, template,
sender, quiet-hour/timezone policy, and school/provider allowance. OTP/security messages require a separately
approved classification rather than a blanket marketing consent rule. Remove or strictly allow-list raw
`msg91Body` passthrough.

TRAI's current sender guidance and consolidated TCCCPR framework require registered sender/header/content
template controls and distinguish service from promotional communication. Compliance/DLT owners must map
each IMS template; code must not infer the category from a display name.

**Acceptance:** withdrawn/denied/suppressed recipients are not submitted; every decision records reason and
policy version; template/category/sender are allow-listed per school; attempts and provider receipts are
tenant-scoped; a test proves no PII/OTP/provider body appears in application logs.

**Cost:** blocked duplicates/invalid sends reduce provider cost. Policy lookup must be indexed/cached without
creating stale-consent sends.

**Dependencies/blockers:** privacy/legal decision, TRAI/DLT registration, Meta/MSG91 approval, and product
classification for every template.

### NOTIFY-01b — Provider economics, reconciliation and live pilot

The checked public MSG91 snapshot lists INR 500/month per WhatsApp number after the initial two-month
discount and India per-message figures of INR 0.115 utility, INR 0.115 authentication, and INR 0.8631
marketing. MSG91 also states charging rules differ by processed/delivered/API-failed outcomes. Treat these as
a dated planning input, not a quote.

At 300,000 students, two utility messages per student per month would be 600,000 messages and about INR
69,000 before number subscription, tax, discounts, free windows, retries, or negotiation. One school-owned
number per 150 schools adds INR 75,000/month steady-state before messages; one shared number adds INR 500.
This sender decision can matter more than GCP variable cost.

**Change:** signed rate card; wallet/credit alerts; provider message id and status webhook; accepted/delivered/
failed/charged reconciliation; daily school/category cost; duplicate-send guard; template versioning; one
controlled non-parent test recipient followed by an approved pilot guardian.

**Acceptance:** invoice/provider dashboard reconciles to IMS facts; retry does not double-charge from an IMS
duplicate; consent and template evidence exist; delivery receipt and failure path are demonstrated; per-school
unit cost is included in margin approval.

**Cost:** use the estimator with approved volumes. No production activation while provider remains `logging`/dry-run.

**Dependencies/blockers:** commercial contract, tax treatment, templates, sender ownership, DLT/Meta approvals,
test recipients, notification Pub/Sub topology, and webhook security.

### OPS-01 — School operations, maintenance and support procedures (**production blocker**)

**Change:** create one operator checklist with named primary/backup roles for: onboarding approval, import
reconciliation, Drive/photo failures, provider failures, tenant isolation, DB saturation, cost forecast,
rights/offboarding requests, breach escalation, and rollback. Set a maintenance window outside school peak,
with school notice/approval, status updates, abort criteria, and post-change evidence. Do not publish private
student data in support tickets.

**Acceptance:** every severity has acknowledgement/update/escalation targets; a tabletop exercises a failed
batch plus provider outage plus a tenant-isolation alert; the test alert reaches the verified channel; one
maintenance notice/abort rehearsal passes; evidence storage and retention are approved.

**Cost:** operational staffing, not primarily GCP. Clear procedures reduce recovery time and repeated paid sends/builds.

**Dependencies/blockers:** business support hours, contact roster, status channel, school contractual SLA,
privacy/breach owner, and live channel verification.

### PILOT-01 — Controlled rollout to 100-150 schools

Use waves, not a single release:

| Wave | Schools | Required entry evidence | Required exit evidence |
| --- | ---: | --- | --- |
| Dev rehearsal | 2 synthetic, one at 10,000 | ONB-01/ONB-02 instrumentation; no production data | 20-batch reconciliation, retry/disconnect, concurrent-school and photo-resume evidence |
| Internal/pilot | 2-3 | all production blockers closed; signed data/provider decisions | 5 school days, zero Sev-1/2, all batches/provider costs reconciled |
| Wave 1 | +5 | SLO/cost headroom and pilot sign-off | 5 school days plus one morning peak |
| Wave 2 | +10 | no unresolved cross-tenant/data issue | 10 school days, forecast within approval |
| Wave 3 | +20 | SQL/connection/storage/provider headroom | 10 school days and recovery evidence current |
| Later waves | 20-25 each | repeat gate; adjust down when schools are near 10,000 | business/engineering/privacy sign-off |

Stop expansion on any tenant-isolation issue, unresolved student-count mismatch, permanent-photo consent
breach, failed backup/recovery evidence, provider duplicate/consent failure, Sev-1/2 incident, DB safety
breach, or gross forecast above the approved envelope.

## Per-School Onboarding Checklist

Before data:

- signed contract/data-processing roles and school owner contacts;
- school id/name/short code, timezone, locale, currency, academic/financial year and structure approved;
- administrator with MFA/credential handoff and least privilege;
- Drive folder readiness and ownership/offboarding rule;
- import source owner, row count, file hash, secure transfer, mapping and correction owner;
- notice/consent/lawful-basis decision for profile, photos, communications and any national identifiers;
- sender mode, templates, DLT/Meta/MSG91 status, rate owner, recipient suppression and test recipient;
- baseline capacity/cost/quotas green and a scheduled operator window.

During data:

- create one onboarding session; sequentially number the expected 20 batches for a 10,000-row school;
- only one confirmation per school; initially at most two school imports fleet-wide;
- preserve preview and confirm evidence; export/correct every skipped row;
- compare admission-number uniqueness, inserted counts and class/section totals after each batch;
- reconcile photo consent/source/result separately; never assume student success means photo success;
- pause if DB connection/CPU, latency, error, object growth, or cross-tenant signal breaches the gate.

Before go-live:

- expected = inserted + intentionally skipped/corrected/unresolved, with unresolved equal zero;
- school administrator validates counts and samples students/guardians/classes/photos;
- permissions/tenant-isolation smoke passes for at least two roles and another school;
- notification test uses the approved template/category/recipient and reconciles a provider result;
- dashboard/report projections and import outbox/inbox state reconcile;
- support, maintenance, rollback, cost and escalation contacts acknowledge ownership;
- sign-off records code revision, Cloud Run revisions/digests, schema versions, source hashes, counts and time.

## Source Notes

Primary sources checked on 2026-08-11:

- [PostgreSQL explicit/advisory locking](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS),
  [Spring `@Transactional`](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html),
  and [Spring transaction-bound JDBC connections](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html)
  — transaction lock lifetime, runtime-exception rollback and connection reuse semantics used by import admission.
- [Cloud Run concurrency](https://docs.cloud.google.com/run/docs/about-concurrency) — multiple requests can
  share instances and revisions autoscale, so a local/single-instance result is not multi-replica evidence.
- [Cloud SQL PostgreSQL machine-series constraints](https://docs.cloud.google.com/sql/docs/postgres/machine-series-overview)
  — Enterprise custom vCPU/memory bounds used to validate the 4-vCPU/7.5-GiB candidate.
- [Cloud Run pricing](https://cloud.google.com/run/pricing) and
  [Cloud Storage pricing](https://cloud.google.com/storage/pricing) — regional billing tiers, storage,
  operations, network and soft-delete cost boundaries; billing-account SKU exports govern the modeled rates.
- [Google Cloud Billing budgets](https://docs.cloud.google.com/billing/docs/how-to/budgets) — alerts-only
  budgets do not automatically cap usage/spend.
- [Google Cloud Billing export to BigQuery](https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery)
  and [billing table behavior](https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery-tables) —
  detailed resource data, labels, latency and non-retroactive regional behavior.
- [Cloud Run quotas and limits](https://docs.cloud.google.com/run/quotas) — project/region limits and quota requests.
- [Cloud Storage lifecycle](https://docs.cloud.google.com/storage/docs/lifecycle) — rule behavior, delayed
  configuration effect and soft-delete interaction.
- [Cloud Logging retention](https://docs.cloud.google.com/logging/quotas#logs_retention_periods) — default,
  required and configurable retention boundaries.
- [Artifact Registry cleanup policy behavior](https://docs.cloud.google.com/artifact-registry/docs/repositories/cleanup-policy-overview)
  and [cleanup configuration](https://docs.cloud.google.com/artifact-registry/docs/repositories/cleanup-policy) —
  KEEP precedence, tag-state behavior and periodic policy execution.
- [MSG91 WhatsApp pricing](https://msg91.com/help/whatsapp/whatsapp-pricing-) and
  [MSG91 charging guidance](https://msg91.com/help/all-service-deductions-) — dated public planning rates,
  subscription and processing/charging rules.
- [TRAI advice to senders](https://trai.gov.in/advice-to-senders) and the
  [current TCCCPR consolidated regulation page](https://trai.gov.in/node/3199) — sender/header/template and
  recipient-preference framework.
- [Digital Personal Data Protection Act, 2023](https://www.indiacode.nic.in/bitstream/123456789/22037/2/a2023-22.pdf),
  [MeitY enforcement timeline notification](https://www.meity.gov.in/static/uploads/2025/11/c56ceae6c383460ca69577428d36828b.pdf),
  and [DPDP Rules, 2025 publication page](https://www.meity.gov.in/documents/act-and-policies/digital-personal-data-protection-rules-2025-gDOxUjMtQWa?hl=en-US).

This is engineering planning, not legal advice or a provider quote.

## Validation Evidence

Initial workstream validation completed locally without deployment or live mutation:

- `npm.cmd test` in `frontend`: 28 files, 145 tests passed.
- `npm.cmd run build:test` in `frontend`: TypeScript and Vite test-mode build passed; existing large-chunk warnings remain.
- `mvnw.cmd -f services/platform-service/pom.xml -Dtest=Msg91NotificationDeliveryProviderTest test`
  with JDK 25: 7 tests passed and the dry-run log contained event id/channel only.
- Admission/API focused run: 4 tests passed in 14.97 seconds.
- Import/RLS/admission/regression focused run: 30 tests passed in 39.216 seconds.
- Post-audit admission/usage/RLS/import regression run: 47 tests passed with zero failures, errors or
  skips; Maven total was 30.279 seconds and wall-clock time was 32.6 seconds.
- Complete default school-core suite on JDK 25: 501 tests passed with zero failures, errors or skips;
  Maven reported `BUILD SUCCESS` in 2:37. A scheduled outbox task attempted one connection only after its
  Testcontainers database shut down, leaving a test-harness lifecycle warning but no test failure.
- `scripts/verify-dev-import-admission.ps1` parsed and its plan-only mode completed without a network
  request. Negative gates rejected a production-looking host, remote dev without explicit write opt-in,
  URI user-info, localhost execution without the required token, and an already-existing evidence path
  before any request was sent; the existing file remained byte-for-byte unchanged.
- `scripts/verify-dev-onboarding-10000.ps1` and its narrow audit parsed successfully. Guard tests failed
  unsafe hosts/schools/options before a network path; the subsequent live run passed 20 batches and exactly
  10,000 inserted rows with immutable PII/token-free evidence.
- Post-V16 opt-in onboarding certification against PostgreSQL 16: 4/4 tests passed; 201.5-second test-class
  time and 3:26 Maven total. The 10,000-student/20-batch path completed in 177,998 ms at a derived 56.18
  students/second; mean/p95/slowest batch times were 8,894.35/10,191/10,225 ms and the database was
  35,314,711 bytes after the fixture. This supersedes the older local timing baseline; later live evidence
  passed the guarded 10,000-student gateway path in 527.372 seconds.
- Same-token concurrency reported `attempts=2`, `students=100`, `duration=998 ms`, `sameJob=true` and
  `duplicates=0`. Two concurrent 500-row schools completed in 4,894 ms. The exercised school-core erase
  target counts all reached zero and the control tenant was preserved. This is not full production erasure
  evidence.
- PowerShell parsed and executed both cost tools. With the measured passing `db-custom-4-7680` planning
  default, platform outputs are INR 31,881 zonal / INR 52,084 HA for 100 schools and 200,000 students, and
  INR 37,396 zonal / INR 58,575 HA for 150 schools and 300,000 students. Relative to the rejected
  `db-custom-2-7680` compute, the increment is INR 6,926.32/month zonally or INR 13,852.64/month in the HA
  model. The separate two-vCPU override remained available and odd-vCPU/invalid-memory shapes were rejected.
  These are modeled—not invoiced—and exclude messaging, tax, support and other documented categories.

Subsequent coordinated dev-only validation:

- Release `rel-dev-d51750493546-1` deployed the admission/usage implementation. Commit `467cd4f8`'s
  fleet-query fix was rolled forward on school-core revision `00186-5pz`; the post-roll-forward gateway
  suite passed 40/40.
- The guarded admission proof passed with one 500-row success and one deterministic same-school 429 on two
  distinct hashed Cloud Run instances. The retained artifact is PII-free and final cleanup is complete.
- Dev Cloud SQL now enforces `ENCRYPTED_ONLY`. Fresh session evidence records 16/16 application clients
  encrypted and zero unencrypted, and the post-enforcement gateway suite passed 40/40.
- The exact SCALE-only outbox/inbox cleanup removed 1,604,136/238,063 rows while preserving outside scope;
  a second pass deleted zero rows. The corrective 4h10m/300-VU soak then passed on `db-custom-4-7680` with
  k6 exit 0, all thresholds passing, 54.22% maximum SQL CPU, 46.8998% memory and 81 connections. The later
  `mixedmorning-20260811121235-evidence.json` rerun failed on the CPU guard with k6 exit 105; it does not
  invalidate the complete attendance-write soak pass, but mixed-read capacity remains uncertified. Later
  4/8-vCPU reruns also failed the sustained CPU gate. Live full-10k and distinct-instance proofs passed;
  final stable-zero cleanup and Cloud SQL downsize/stop are complete.

See [ONBOARDING-CERTIFICATION-RESULTS-2026-08-11.md](./ONBOARDING-CERTIFICATION-RESULTS-2026-08-11.md)
for commands, exact reconciliation, erasure boundaries, scenario parameters and remaining gates.

The initial workstream itself performed no deployment or live mutation. The subsequent coordinated actions
above were dev-only; no production resource was modified by this onboarding/cost work.

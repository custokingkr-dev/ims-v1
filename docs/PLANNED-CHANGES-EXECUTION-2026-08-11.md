# Planned Changes and Execution Program — 2026-08-11

## 1. Objective

Prepare Custoking IMS for a controlled production rollout supporting 100–150 schools, approximately
200,000–300,000 student records, and schools as large as 10,000 students while minimizing fixed GCP
cost. This document is the execution ledger: a change is complete only when its acceptance evidence
is recorded. Repository implementation does not by itself close a live-infrastructure, performance,
recovery, product, legal, or business-acceptance gate.

Production must not be mutated merely because the maintenance-window time has passed. Promotion is
allowed only after every item marked **production blocker** is closed or an explicitly named business
owner accepts the residual risk in writing.

## 2. Evidence Rules

1. Prefer repository code, automated tests, live read-only GCP/GitHub inspection, and primary vendor
   documentation.
2. Never infer a live resource from source configuration. Record source intent and live state
   separately.
3. Never record a test as passed unless an artifact, workflow, operation ID, revision, metric query,
   or command result exists.
4. Never print or persist secret values or secret-bearing Pub/Sub URLs.
5. Synthetic dev data only for load/recovery work. Production writes require an approved canary.
6. A production change needs a rollback command/path, an operator, a monitoring window, and a stop
   condition before execution.
7. Cost estimates are planning values, not invoices. Re-query current pricing before commercial
   commitments.

## 3. Confirmed Starting State

Verified on 2026-08-10/11 against repository and live project `custoking`:

| Area | Confirmed state |
| --- | --- |
| Scale fixture | 100 schools, 300,000 students, largest school 10,000 students |
| Sustained write test | 300 VUs for 9 minutes; 117,838 requests; 0.01% errors; 122.91 ms p95; 235.06 ms p99 |
| 500-VU boundary | Stopped by safety guard after database CPU reached approximately 88% |
| Dev runtime IAM | Seven dedicated service identities; 40/40 authenticated checks passed |
| Dev reporting push | Dedicated OIDC identity; no query credential; canonical event returned HTTP 204 |
| Live subscriptions | Reporting push only: one dev and one production subscription |
| Notification topology | Topics and consumer code exist; no live notification subscription in either environment |
| Dev cost state | Cloud SQL `db-f1-micro`, `STOPPED`, activation policy `NEVER` after validation |
| Production database | `db-g1-small`, Enterprise, zonal, running; PITR enabled; 14 backups; 7 transaction-log days; deletion protection enabled |
| Database transport | Both private-IP Cloud SQL instances use `ALLOW_UNENCRYPTED_AND_ENCRYPTED`; all five deployed JVM clients omit `sslmode`; one prod and two dev SQL jobs explicitly disable TLS; active JVM-session encryption is unproven |
| Production runtime IAM | Seven services still use the broad default compute identity |
| Production reporting push | Default compute push identity and legacy query credential remain |
| Cloud Scheduler | API disabled; no scheduler jobs |
| Cloud Monitoring | 99 enabled alert policies and one enabled operator email channel; all 99 reference a notification channel and include documentation |
| Monitoring gaps | No live policy condition/name matched Cloud SQL, database connections, memory, Pub/Sub subscription, Cloud Run Job, trace export, cost, or budget |
| Billing budget | One INR 5,000 monthly alert-only budget, scoped to project `305630109861`; current-spend thresholds 50/80/100%, forecast threshold 100%; no Pub/Sub notification |
| Storage lifecycle | Snapshot/source/build buckets have age-based deletion; photo buckets delete the temporary root plus a few historical exact import prefixes, but do not generically cover future per-school import prefixes |
| Artifact Registry | Docker repository is approximately 24.5 GB; deletes versions older than 7 days while keeping the most recent 3 versions per package |
| Logging retention | `_Default` 7 days; locked `_Required` 400 days; regional `custoking-compliance-india` 180 days and not locked |
| Cloud Run public IAM | Only frontend and API gateway are public in dev/prod; the five Java backend services are private |
| GitHub governance | No repository rulesets; classic protection absent/inaccessible for both `main` and `dev` |
| WIF provider | Active; condition restricts repository only, not branch/workflow/environment |

The confirmed 300-VU result is a strong application baseline. It does not replace a four-hour soak,
long-history query validation, recovery evidence, production database selection, or real school-day
canary.

## 4. Workstream Ownership

| Workstream | Owns | Does not own |
| --- | --- | --- |
| Security and governance | Branch rules, WIF, deploy/runtime IAM, database transport, secret rotation, scanning, ingress decision | Load/recovery execution |
| Reliability, scale and recovery | Notification subscription, relay, DLQ/replay, soak/burst, query plans, PITR/restart, SLOs | Commercial/provider approval |
| Onboarding, cost and compliance | 10k-student process, reconciliation, tenant controls, cost attribution, retention, consent, pilot operations | Production deployment |
| Integration/release | Master plan, conflict resolution, full tests, dev deploy/evidence, production go/no-go | Business risk acceptance |

One owner controls each change type. Cross-workstream dependencies are recorded below rather than
implemented independently in conflicting files.

## 5. Detailed Change Register

### SEC-01 — Protect `main` and `dev` (**production blocker**)

- **Current evidence:** no rulesets; classic protection endpoint returns absent/inaccessible.
- **Change:** require pull requests, at least one independent approval, conversation resolution,
  strict required CI checks, no force push/deletion, and no administrator bypass for `main`. Apply
  equivalent checks to `dev`, allowing only the deployment workflow/bot path required by the chosen
  promotion model.
- **Prerequisites:** identify stable check names from recent successful workflows; confirm repository
  plan supports the required rules; identify emergency bypass owner.
- **Acceptance:** an intentionally failing PR cannot merge; direct/force push is rejected; a passing,
  reviewed PR can merge; emergency bypass produces an auditable event.
- **Rollback:** export the prior protection JSON; restore it through the GitHub API.
- **Cost:** no GCP cost; potentially more CI minutes.
- **Evidence:** exported before/after policy, test PR URLs, required-check list, named owner.

### SEC-02 — Restrict GitHub-to-GCP WIF (**production blocker**)

- **Current evidence:** provider condition is only
  `assertion.repository=='custokingkr-dev/ims-v1'`; mapping contains repository and subject.
- **Change:** add ref, workflow and environment mappings/conditions; separate dev and production
  principals or conditions; allow production impersonation only from the reviewed release workflow,
  protected `main`, and protected production environment.
- **Acceptance:** dev workflow authenticates only from permitted dev context; production workflow
  authenticates only from permitted main/environment context; fork, arbitrary branch and alternate
  workflow tokens are denied.
- **Rollback:** preserve provider JSON and restore the prior condition if the authorized workflow is
  unintentionally denied.
- **Cost:** none.
- **Evidence:** provider JSON, negative token-exchange tests, successful dev auth, production dry-run.

### SEC-03 — Reduce deployment service-account authority (**production blocker**)

- **Current evidence:** broad legacy predefined roles remain; repository custom-role intent is not
  the complete live policy.
- **Change:** derive permissions from successful workflow audit logs; split build, deploy, database
  migration and recovery duties where practical; replace project-wide `serviceAccountUser` and
  administrative roles with resource-level grants/custom roles.
- **Acceptance:** normal dev release and rollback pass; the deploy identity cannot alter unrelated
  IAM, secrets, buckets, databases, or Cloud Run services.
- **Rollback:** reapply exported IAM policy bindings for the deployment identity.
- **Cost:** none; custom-role maintenance overhead.
- **Evidence:** permission matrix, IAM diff, successful dev deployment/rollback, negative permission tests.

### SEC-04 — Promote per-service runtime IAM to production (**production blocker**)

- **Current evidence:** dev uses seven verified identities; production uses the default compute SA.
- **Change:** provision production counterparts with exact secret/topic/bucket/invoker grants; deploy
  through `5% -> 25% -> 50% -> stable`; retain old identity permissions during observation only.
- **Acceptance:** every service Ready at intended traffic; authenticated smoke 40/40; fresh outbox and
  photo paths pass; no permission-denied logs; runtime SA matches manifest.
- **Rollback:** send traffic to the prior revision; restore the old runtime identity target; do not
  delete old permissions until the observation window closes.
- **Cost:** IAM itself is free; one deployment/startup cycle and temporary parallel canary instances.
- **Evidence:** IAM audit, Cloud Deploy release/rollout IDs, revision table, logs and smoke artifacts.

### SEC-05 — Migrate production reporting push to dedicated OIDC (**production blocker**)

- **Current evidence:** production reporting push still uses default compute and a secret query value.
- **Change:** deploy token-optional application mode only behind private Cloud Run IAM; create a
  dedicated push SA; grant Pub/Sub service-agent token creation and service-scoped invoker; configure
  exact audience and query-free endpoint; rotate the legacy token after all callers migrate.
- **Acceptance:** canonical event returns 204; URL has no query; wrong audience/caller is denied;
  backlog and delivery latency stay within gates.
- **Rollback:** restore the endpoint internally from Secret Manager without printing it; return traffic
  to the token-required revision.
- **Cost:** negligible Pub/Sub/IAM; no always-on compute.
- **Evidence:** sanitized subscription description, IAM bindings, request log and rotation version.

### SEC-06 — Secret lifecycle and vulnerability closure

- **Change:** inventory owner/consumer/last rotation for every secret; rotate service tokens after
  OIDC/runtime migrations; establish rotation cadence and expiry alerts; remediate high/critical
  runtime dependency and container findings; record time-bounded exceptions.
- **Acceptance:** no unused secret version remains enabled beyond grace; consumers run on new versions;
  required scans fail closed; no unaccepted critical finding.
- **Rollback:** retain previous secret version during a bounded grace window; restart the prior revision.
- **Cost:** additional secret versions are minimal; CI/scanner minutes may rise.

### SEC-07 — Ingress/WAF decision

- **Current evidence:** only the frontend and API gateway grant `allUsers` invoker in each
  environment; identity, school core, operations, platform and billing are private. Live lists for
  URL maps, forwarding rules and Compute security policies are empty, so no load balancer/Cloud
  Armor policy fronts the four public services.
- **Change:** formally choose direct `run.app` exposure or external HTTPS load balancer + Cloud Armor.
  If direct exposure is retained, document compensating controls: Cloud Run IAM, gateway validation,
  rate limits, abuse alerts and origin restrictions.
- **Acceptance:** threat model and measured need justify the selection; only intended public services
  are unauthenticated.
- **Cost:** direct exposure is cheaper; load balancer/Cloud Armor adds fixed and request-processing cost.

### SEC-08 — Enforce and prove Cloud SQL transport security (**production blocker**)

- **Current evidence:** dev and prod are private-IP-only but both server policies allow plaintext. The
  five database-backed services use direct pgJDBC URLs with no explicit TLS mode; pgJDBC `prefer`
  permits fallback and does not prove active sessions are encrypted. Two dev SQL jobs and one prod
  SQL job explicitly use `PGSSLMODE=disable`. API gateway and frontend are not database clients.
- **Repository change:** require TLS on all ten runtime/Flyway JDBC URLs and all four checked-in psql
  job constructors; add a fail-closed audit of source, deployed clients, server policy and fresh
  aggregate `pg_stat_ssl` evidence. The two reusable job helpers now detect an existing weak job and
  merge only `PGSSLMODE=require` with `--update-env-vars`, failing before execution if reconciliation
  fails; all other job configuration is preserved. A dev-default evidence helper now executes the
  checksum-pinned aggregate SQL through the existing private-VPC job and emits only the seven-field
  audit envelope. Evidence filenames are UTC timestamped and never overwritten, and the temporary
  execution must be deleted and confirmed absent before finalization. Production requires a switch
  plus exact instance confirmation before cloud access.
  This compatibility phase uses `require`; verified endpoint identity remains a deliberate
  `verify-full`/Cloud SQL Java Connector phase.
- **Execution order:** deploy/update dev clients first; recycle pools; prove encrypted sessions; set
  dev `ENCRYPTED_ONLY`; rerun smoke/jobs/load and observe 24 hours. In the approved post-23:00 IST
  production window, update the existing prod SQL job and deploy services one by one, then enforce
  the server flag, recycle old sessions and capture fresh evidence. Never enforce the server first.
- **Acceptance:** server rejects plaintext; every deployed JDBC/psql caller requires encryption;
  `pg_stat_ssl` evidence is environment/project/instance matched, <=30 minutes old, non-empty, and
  has encrypted count equal to client count with zero unencrypted; migrations, smoke and jobs pass.
- **Rollback:** route to prior service revisions/restore prior job definitions. If required, restore
  only the affected instance to `ALLOW_UNENCRYPTED_AND_ENCRYPTED`, recycle clients and record the
  exception; that state remains a blocker. Preserve `ENCRYPTED_ONLY` when rolling identity validation
  from `verify-full` back to `require` is sufficient.
- **Cost:** no extra always-on resource for the server TLS policy; measure modest handshake/CPU and
  temporary canary overlap in dev. Prefer the Java Connector over a new proxy service if it meets the
  endpoint-identity design.
- **Evidence:** `docs/workstreams/SECURITY-GOVERNANCE-CHANGES-2026-08-11.md`, source/live audit JSON,
  redacted deployed configuration, fresh aggregate session evidence, smoke/job results and metrics.

### ASYNC-01 — Decide and wire notification-event delivery (**production blocker**)

- **Current evidence:** two notification topics exist; zero notification subscriptions exist.
- **Change:** either (A) create dev/prod OIDC push subscriptions to platform notification ingress,
  with retry/DLQ controls, or (B) retire topics/consumer code and document another delivery path.
- **Default recommendation:** option A because producer/topic/consumer code already exists, but only
  after a dev canonical event and duplicate-delivery test pass.
- **Acceptance:** dev event is acknowledged and stored once; duplicate is idempotent; invalid event
  reaches DLQ; production provisioning remains gated until dev evidence passes.
- **Rollback:** detach push configuration or delete only the newly created dev subscription after
  confirming no retained business messages.
- **Cost:** Pub/Sub is usage-based and expected to remain low; no minimum Cloud Run instance required.

### ASYNC-02 — Idle-safe outbox relay (**production blocker**)

- **Current evidence:** Cloud Scheduler API disabled and no scheduler jobs; scale-to-zero can pause
  purely background work.
- **Change:** package relay as a bounded Cloud Run Job or authenticated internal endpoint invoked by
  Cloud Scheduler; use a dedicated invoker/runtime SA; preserve database leasing/idempotency.
- **Location constraint:** Cloud Scheduler is not available in Delhi (`asia-south2`); use its Mumbai
  (`asia-south1`) control plane for OIDC HTTP triggers while keeping every runtime/data target in Delhi.
- **Acceptance:** with user traffic idle and all services at zero, a queued outbox event is published
  within the SLO; concurrent triggers do not duplicate effects; failed leases recover.
- **Rollback:** disable scheduler; retain current in-service relay until job evidence passes.
- **Cost:** Scheduler has a small job charge and Cloud Run Job is billed only while executing; compare
  against the higher fixed cost of `min-instances=1`.

### ASYNC-03 — Dead-letter, retry and replay

- **Change:** add per-environment DLQ topics/subscriptions, exponential retry policy, Pub/Sub service
  agent IAM, delivery-attempt observability, guarded inspect/replay tooling and an operator runbook.
- **Acceptance:** poison event is retried within configured bounds, forwarded to DLQ, inspected without
  leaking PII, corrected/replayed once, and removed only after successful processing.
- **Rollback:** clear dead-letter policy and retry settings without deleting retained DLQ data.
- **Cost:** retained messages/storage and replay requests are usage-based and normally negligible.

### PERF-01 — Four-hour 300-VU soak (**production blocker**)

- **Prerequisites:** synthetic 300k fixture; temporary approved 2-vCPU-or-larger dev database; cost
  guard disabled for the exact window; automatic cleanup and database downsize/stop; no production writes.
- **Gates:** HTTP error rate <1%; read p95 <800 ms; read p99 <2 s; attendance save p95 <1.5 s;
  database CPU not >80% for 15 continuous minutes; no memory exhaustion; connection headroom;
  oldest unacked/outbox age <2 minutes.
- **Acceptance:** complete four-hour artifact and one-minute metric series; no scheduled shutdown;
  fixture removed; dev SQL restored to low-cost state.
- **Stop conditions:** tenant/isolation error, destructive data drift, CPU safety breach, connection
  exhaustion, cost guard breach, or sustained error/latency failure.
- **Cost:** temporary dedicated SQL plus request-driven Cloud Run for approximately five hours including setup.

### PERF-02 — Morning-peak burst

- **Change:** model login, dashboard, attendance, fee/report reads and bounded onboarding concurrency;
  do not use the rejected 500-attendance-writer profile as a production target.
- **Acceptance:** stated burst duration/concurrency passes the same error and connection gates; record
  whether 2 or 4 vCPU is required.
- **Cost:** short temporary scale-up only.

### PERF-03 — Long-history query plans and retention (**production blocker**)

- **Change:** seed representative attendance/reporting history; capture `EXPLAIN (ANALYZE, BUFFERS)`
  for critical queries; add only evidence-backed indexes; decide time partitioning/retention before
  detail rows reach tens of millions.
- **Acceptance:** plans avoid accidental full scans for bounded tenant/date queries; write amplification
  remains acceptable; rollback SQL exists for every index/partition change.

### REL-01 — Controlled restart/reconnection

- **Change:** execute a dev Cloud SQL restart or controlled availability event during bounded traffic;
  measure error window, retry/backoff, pool recovery and outbox behavior.
- **Acceptance:** service recovers without manual revision restart or cross-tenant error; measured RTO
  is within the approved objective.
- **Rollback:** stop traffic, restore database state and previous service revisions.

### REL-02 — PITR restore drill (**production blocker**)

- **Current evidence:** production PITR is enabled; no recorded restore drill.
- **Change:** restore dev/sanitized source to a uniquely named temporary instance at a recorded timestamp;
  validate schema/row checksums and application read-only smoke; record RPO/RTO; delete the verified clone.
- **Acceptance:** target timestamp is inside retention; restoration reaches Ready; validation passes;
  cleanup is confirmed; source is untouched.
- **Cost:** temporary restored SQL compute/storage/backups for the drill window.

### DB-01 — Production database/availability decision (**production blocker**)

- **Current evidence:** production `db-g1-small`, zonal. Google excludes shared-core
  `db-f1-micro`/`db-g1-small` shapes from the Cloud SQL SLA, documents zonal as appropriate for
  test/development, and recommends regional HA for production availability requirements.
- **Decision:** choose measured 2-vCPU versus 4-vCPU shape and zonal versus regional HA; record business
  availability acceptance if selecting the cheaper zonal option.
- **Acceptance:** soak/burst evidence supports capacity; connection budget fits autoscaling maximums;
  monthly forecast and RTO/RPO meet approved objectives.
- **Rollback:** predefine scale-down window and restrictions; preserve backup/PITR settings.

### OBS-01 — SLOs, alerts and incident runbooks (**production blocker**)

- **Current evidence:** Monitoring API enumeration found 99 enabled policies on 2026-08-11. They
  cover service latency, 5xx, availability burn rates, maximum-instance saturation, production
  uptime, outbox state and notification inbox state. All 99 reference the sole enabled operator-email
  channel and contain documentation. No policy condition or name matched Cloud SQL, database
  connections, memory, Pub/Sub subscriptions, Cloud Run Jobs, trace export, cost or budgets.
- **Change:** preserve and test the existing application/SLO policies; add the confirmed gaps for SQL
  CPU/memory/connections, Pub/Sub backlog/DLQ, relay-job failure, trace-export health, storage growth
  and cost forecast; name primary/secondary owners.
- **Acceptance:** synthetic threshold breach reaches the intended channel; alert contains runbook link;
  recovery clears the alert; no alert depends only on a dashboard someone must watch.
- **Cost:** metrics/log volume and notification charges; use sampling/retention to bound spend.

### COST-01 — Budget and forecast guardrails (**production blocker**)

- **Current evidence:** one `Custoking Monthly Guardrail` budget is scoped to this project for INR
  5,000/month, excludes credits, and alerts at 50/80/100% actual plus 100% forecast. It has no
  programmatic Pub/Sub notification. Google explicitly documents that an alert-only budget does not
  cap usage or spending.
- **Change:** replace the INR 5,000 planning alert with approved warning/critical thresholds based on
  the selected fleet; notify at actual and forecast percentages; never represent a budget as a hard cap.
- **Acceptance:** billing-account scope, currency, recipients and Pub/Sub automation are verified;
  forecast >125% opens an incident/review.

### COST-02 — Per-school usage attribution

- **Change:** emit tenant-safe request/job/import/storage/message counters keyed by opaque school ID;
  combine with service cost allocation rather than claiming exact tenant billing from shared resources.
- **Acceptance:** dashboard identifies top tenants and noisy workloads without PII; sampled totals
  reconcile with platform totals; cardinality is bounded.
- **Cost:** custom metric cardinality can be expensive; aggregate hourly/daily and cap dimensions.

### DATA-01 — Lifecycle, privacy and offboarding (**production blocker for school launch**)

- **Current evidence:** both photo buckets delete `temporary/photo-imports/` after 14 days and retain
  soft-deleted objects for 7 days. A few historical per-school import prefixes are enumerated, but a
  GCS `matchesPrefix` rule cannot wildcard all future `schools/<id>/student-imports/<import>/`
  prefixes. The snapshot bucket deletes after 30 days; source/build buckets after 14 days. Artifact
  Registry deletes versions older than 7 days while keeping the most recent 3 per package; deployed
  and rollback digest preservation still needs an explicit safety check.
- **Log evidence:** `_Default` retains 7 days, locked `_Required` retains 400 days, and the regional
  compliance bucket retains 180 days but is not locked. Routing coverage and the legal owner for the
  180-day choice have not been evidenced here.
- **Change:** approve retention/deletion/export for students, photos, reports, audit logs, application
  logs, traces and backups; add bucket lifecycle where legally permitted; create school offboarding
  and legal-hold procedures; document guardian consent evidence. Delete future staged school-import
  objects through the import ledger/cleanup job (or move all temporary objects under a common
  lifecycle prefix) instead of relying on impossible wildcard lifecycle matching; verify and adjust
  the existing Artifact Registry policy so every deployed and approved rollback digest is retained.
- **Acceptance:** one synthetic school export/deletion is reconciled across primary data, projections,
  objects and retained backups; deletion exceptions are explicit.

### ONB-01 — 10,000-student onboarding operating model

- **Current evidence:** explicit 500-row retry-safe transactions imply 20 batches per 10k school.
- **Change:** provide batch manifest, progress ledger, failed-row export, duplicate/retry rules,
  reconciliation totals, operator handoff and rollback. Add unattended Jobs/Tasks only if a measured
  onboarding SLA requires them.
- **Acceptance:** synthetic 10k onboarding completes with exact counts; repeating a completed batch
  is idempotent; partial failure can resume; photo matching is reconciled.

### ONB-02 — Concurrent onboarding and noisy-tenant protection

- **Change:** test multiple schools importing while normal school-day traffic runs; add per-school
  concurrency/rate limits and fair queueing only where evidence shows contention.
- **Acceptance:** one large onboarding cannot exhaust connections or breach another school's latency
  or authorization boundary.

### NOTIFY-01 — Provider delivery, consent and unit economics (**production blocker for messaging**)

- **Change:** complete MSG91 template approval, sender/number setup, consent/opt-out, retry/status
  callbacks, delivery reconciliation and per-message cost model. Keep production dry-run until approved.
- **Acceptance:** consented test recipient gets one message; opt-out is enforced; status callback is
  correlated; secrets are not logged; monthly cost at expected volume is approved.

### PILOT-01 — Canary school and rollout waves (**production blocker**)

- **Change:** onboard a small representative cohort, observe a full school-day peak, reconcile core
  workflows and support incidents, then expand in controlled waves.
- **Acceptance:** no open severity-1/2 issue, tenant isolation incident, unreconciled financial data,
  or breached SLO before expanding the cohort.

## 6. Dependency Order

```text
Repository governance + WIF
        |
        +--> dev async/DLQ/relay evidence
        |          |
        |          +--> four-hour soak + burst + long-history plans
        |                          |
        |                          +--> DB shape/HA decision
        |
        +--> recovery/PITR evidence
        |
        +--> production runtime IAM + reporting OIDC canary
                                   |
                                   +--> production preflight/canary school
```

Onboarding, lifecycle, provider approval, budgets and incident ownership must close before the first
school receives production access even if the technical canary is healthy.

## 7. Seven-Day Execution Sequence

| Day | Work | Exit evidence |
| --- | --- | --- |
| 1 | Merge workstream tooling/docs; apply dev notification/DLQ/relay infrastructure only after tests | Local CI, guarded dry-runs, dev resource diff |
| 2 | Verify idle relay, poison event, DLQ inspect/replay, canonical notification delivery | Event IDs, 204 logs, DLQ/replay artifacts, zero backlog |
| 3 | Seed long-history fixture; capture plans; concurrent onboarding test | Plan bundle, row counts, reconciliation, connection metrics |
| 4 | Temporary SQL scale-up; four-hour 300-VU soak; cleanup/downsize/stop | k6 artifact, one-minute metrics, cleanup proof |
| 5 | Morning burst; controlled restart; PITR restore drill and clone cleanup | Burst artifact, RTO/RPO, operation IDs, restore validation |
| 6 | Decide SQL/HA/cost; configure governance; prepare production IAM/OIDC canary | Signed decisions, policy diffs, production preflight dry-run |
| 7 | Production canary only if every blocker passes; otherwise publish no-go ledger | Cloud Deploy 5/25/50/stable evidence or explicit no-go |

This sequence can fit within a week only if external owners are available for GitHub administration,
budget/HA acceptance, provider/consent approval and the canary-school window. Missing external approval
is a recorded blocker, not permission to guess.

## 8. Production Stop Conditions

Stop or roll back the canary on any of the following:

- cross-tenant access or authentication bypass;
- failed/non-backward-compatible migration;
- HTTP error rate >=1% outside an understood, bounded transient;
- read p95 >=800 ms, p99 >=2 s, or attendance-save p95 >=1.5 s for the gate interval;
- database CPU >80% for 15 minutes, memory exhaustion, or unsafe connection pressure;
- outbox/Pub/Sub oldest unprocessed age >2 minutes;
- DLQ growth without an identified poison event;
- unreconciled fee, attendance, student or notification data;
- monthly forecast >125% of approved envelope;
- failed rollback, backup or recovery prerequisite;
- Cloud SQL server/client transport audit fails or fresh database-side evidence finds any unencrypted
  client backend.

## 9. Evidence Index

- `docs/DEV-SCALE-VALIDATION-2026-08-10.md`
- `docs/SCALE-READINESS-AND-COST-PLAN-2026-08-10.md`
- `docs/current-state/gcp-infrastructure.md`
- `docs/current-state/gaps-and-drift.md`
- `docs/DB-SCALING-THRESHOLDS.md`
- `docs/GCP-COST-GUARDRAILS-RUNBOOK.md`
- Workstream documents under `docs/workstreams/` produced by this execution program.

## 10. Primary References

- Cloud SQL availability and HA:
  https://docs.cloud.google.com/sql/docs/postgres/availability
- Cloud SQL instance settings and PITR:
  https://docs.cloud.google.com/sql/docs/postgres/instance-settings
- Cloud SQL pricing:
  https://cloud.google.com/sql/pricing
- Cloud Run pricing and billing settings:
  https://cloud.google.com/run/pricing
  https://docs.cloud.google.com/run/docs/configuring/billing-settings
- Pub/Sub authenticated push:
  https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions
- Pub/Sub dead-letter topics:
  https://docs.cloud.google.com/pubsub/docs/dead-letter-topics
- Pub/Sub retry policy:
  https://docs.cloud.google.com/pubsub/docs/subscription-retry-policy
- Pub/Sub pricing:
  https://cloud.google.com/pubsub/pricing
- Cloud Run Jobs scheduled with Cloud Scheduler:
  https://docs.cloud.google.com/run/docs/execute-jobs-on-schedule
- Cloud Run quotas:
  https://docs.cloud.google.com/run/quotas
- Cloud Billing budgets (including the warning that alert-only budgets are not hard caps):
  https://docs.cloud.google.com/billing/docs/how-to/budgets
- Cloud Logging distribution and counter metrics:
  https://docs.cloud.google.com/logging/docs/logs-based-metrics
- Cloud Monitoring alert behavior and auto-close:
  https://docs.cloud.google.com/monitoring/alerts
- Google Cloud metric descriptors for Cloud SQL and Cloud Storage:
  https://docs.cloud.google.com/monitoring/api/metrics_gcp_c
  https://docs.cloud.google.com/monitoring/api/metrics_gcp_p_z
- Cloud SQL PostgreSQL high-memory guidance:
  https://docs.cloud.google.com/sql/docs/postgres/optimize-high-memory-usage
- Cloud SQL PostgreSQL SSL/TLS enforcement and direct authorization:
  https://docs.cloud.google.com/sql/docs/postgres/configure-ssl-instance
  https://docs.cloud.google.com/sql/docs/postgres/authorize-ssl
- pgJDBC connection and SSL modes:
  https://jdbc.postgresql.org/documentation/use/
  https://jdbc.postgresql.org/documentation/ssl/
- Cloud Storage pricing:
  https://cloud.google.com/storage/pricing
- Cloud Armor pricing:
  https://cloud.google.com/armor/pricing
- Google Cloud Workload Identity Federation:
  https://docs.cloud.google.com/iam/docs/workload-identity-federation
- GitHub protected branches:
  https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches
- GitHub OIDC with Google Cloud:
  https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-google-cloud-platform
- React Router release and migration changelog:
  https://reactrouter.com/home/changelog

## 11. Completion Ledger

Repository integration evidence on 2026-08-11: the full service catalog completed successfully.
Surefire reports contain 1,027 regular Java tests (zero failures/errors/skips), and the separate
opt-in 10,000-student certification adds 4 passing tests, for 1,031 distinct Java tests. API gateway
has 58 passing tests and frontend has 146 passing tests. The production frontend build, Terraform formatting and
validation, governance audit, all new PowerShell parser checks, and all guarded dev dry-runs passed.
Frontend React Router was also upgraded to 7.18.2 after its v7 prerequisites were verified: 146 tests,
both Vite builds, and the frontend/gateway npm audits pass with zero reported vulnerabilities. These
results validate source implementation; they do not replace the live/time-bound gates below.

| ID | Repository implementation | Dev live validation | Production validation | Status |
| --- | --- | --- | --- | --- |
| SEC-01 | Guarded branch-protection tool + stable required-check list complete | n/a | Apply/negative merge tests pending | Production blocked |
| SEC-02 | Immutable repository/owner IDs + exact paired ref/workflow claims + workflow-specific impersonation prepared | 3-allow/6-deny dry-run passed; live token tests pending | Apply/verify pending | Production blocked |
| SEC-03 | Read-only authority audit and migration order documented | Deploy/rollback negative tests pending | Least-privilege cutover pending | Production blocked |
| SEC-04 | Dev implementation complete | Passed | Pending | Production blocked |
| SEC-05 | Dev implementation complete | Passed | Pending | Production blocked |
| SEC-06 | Gates complete; 4 Java CVEs and React Router advisories remediated; all 52 external Actions invocations pinned to verified terminal commits; 44-secret proposal reconciles with no payload access | Five rebuilt Java images have zero HIGH/CRITICAL and both npm trees are clean; fresh GitHub scan required after push | Admin enables GitHub security settings; named owners approve rotations | Production blocked |
| SEC-07 | Public/private IAM verified; direct-vs-Armor decision documented | n/a | Owner/cost decision pending | Open |
| SEC-08 | All ten runtime/Flyway URLs and four checked-in psql constructors require TLS; reusable helpers merge only the TLS key on existing jobs; guarded PII-free session capture and fail-closed audit added | Live report-only audit confirms server/client/job blockers; capture helper was source-validated but deliberately not run; client-first rollout and 24-hour dev proof pending | Existing prod job/service configs, server enforcement and guarded fresh session proof pending | Production blocked |
| ASYNC-01 | OIDC-only ingress + guarded notification provisioning complete | OIDC/DLQ applied; duplicate canonical event returned 204 twice and delivered once | Pending | Production blocked |
| ASYNC-02 | OIDC scheduler drains + transactional relay retries complete | Cold idle drain passed for school, operations and billing: each outbox row published once and reporting processed 3/3 | Pending | Dev passed; production blocked |
| ASYNC-03 | Retry state, DLQ provisioning and guarded replay complete | Dry-run preserved the DLQ event; guarded apply replayed and acknowledged it once; reporting processed 1/1 | Pending | Dev passed; production blocked |
| PERF-01 | Guarded, pinned-image four-hour harness complete | First 4-vCPU soak failed the CPU guard after 2h28m; two measured SQL defects are fixed locally and a full same-shape rerun is mandatory | n/a | Failed; corrective rerun pending |
| PERF-02 | 15-minute morning-burst profile complete | 4-vCPU/7.5-GiB full 300-VU burst passed; 2-vCPU shape rejected after repeated 83.52% CPU samples | n/a | Dev passed |
| PERF-03 | Read-only long-history plan capture and certification threshold complete | 7.59M-row isolated clone passed bounded tenant/date and student plans; aggregate used a parallel sequential scan and still needs production-like distributed-history validation | n/a | Partially passed; production blocked |
| REL-01 | Guarded restart drill and RTO evidence tool complete | Restart command completed in 19.71s; all five Java services returned 200 within 63.52s without revision restart | n/a | Dev passed |
| REL-02 | PITR helper records RPO/RTO and cleanup evidence | PITR clone runnable in 539.49s; 65,248,345-byte synthetic export validated at 582.57s; clone/object/IAM cleanup confirmed | n/a | Dev passed; production blocked |
| DB-01 | Cost/threshold/connection-budget tooling complete | `db-custom-4-7680` remains the cheapest measured burst pass, but is not yet a soak pass; corrected full soak must pass before the cost model selects it | Shape/HA business decision pending | Production blocked |
| OBS-01 | SQL/Pub/Sub/Scheduler/trace/storage alert IaC validates | 110 policies live; Scheduler/trace incidents opened and recovered; mailbox receipt pending | Pending | Production blocked |
| COST-01 | Live budget reconciled; Delhi-SKU platform and messaging models executable | Models rerun; budget automation and owner approval pending | Pending | Production blocked |
| COST-02 | PII-free, RLS-covered daily import usage endpoint and closed-cardinality rejection metric implemented | Local query bounds/RLS/reconciliation tests passed; dev deploy pending | Pending | Partially passed |
| DATA-01 | Retention/offboarding gaps and exact procedures documented | In-memory checksummed export + school-core erase/control rehearsal passed; full-system workflow absent | Legal owner approval pending | School launch blocked |
| ONB-01 | Reconciliation CSV + bounded import complete; local exact 10k/20-batch certification passed | Real gateway/Cloud SQL/multi-replica onboarding pending | n/a | Production blocked |
| ONB-02 | PostgreSQL one-per-school/two-fleet admission + HTTP 429 implemented | Local concurrency/idempotency/RLS tests passed; dev deploy pending | n/a | Production blocked |
| NOTIFY-01 | PII-safe dry-run logging and configurable economics tool complete | Real consented provider test pending | Provider/legal approval pending | Messaging blocked |
| PILOT-01 | Per-school checklist and rollout waves documented | n/a | Named canary/full-day evidence pending | Production blocked |

Exact local timings, counts, checksums, Delhi SKU evidence, allocation boundaries, and remaining
privacy/provider gates are recorded in
`docs/workstreams/ONBOARDING-CERTIFICATION-RESULTS-2026-08-11.md`. Security claim tests, CVEs,
remediation versions, live-versus-proposed IAM state, and administrator-owned gates are recorded in
`docs/workstreams/SECURITY-GOVERNANCE-CHANGES-2026-08-11.md`.

## 12. Dev Deployment and Validation Evidence

Dev execution completed on 2026-08-11 without modifying production:

- commit `4d0a56bf6f753a9012e3ead5af761ee6b58d7914` passed CodeQL run
  `31435682010` for Java and JavaScript/TypeScript;
- dev deployment run `31435682086` and Cloud Deploy release
  `rel-dev-4d0a56bf6f75-1` succeeded for all seven services;
- live inspection confirmed seven immutable image digests, seven Ready revisions, and 100% traffic on
  each new revision;
- the deployment gateway-health smoke passed, and direct-service smoke execution
  `ims-direct-service-smoke-x6p8j` completed with one successful task and no failed task;
- the guarded deployed regression passed 40/40 authenticated checks with zero failures, including
  photo upload and routes across every private backend. The real-environment preflight returned
  `Ready=true` with zero blockers, and its temporary smoke users/student were retired;
- notification and reporting OIDC/DLQ topology was applied with dedicated identities, exact audiences,
  query-free URLs, 10–600 second retry, and ten delivery attempts;
- synthetic notification event `dev-notification-smoke-20260811T040001Z-rest` returned HTTP 204 twice
  but produced one delivery record, proving idempotency. Dev used the logging provider with MSG91 dry
  run enabled, so no external message was sent;
- a malformed synthetic event exercised the notification DLQ and the resulting test message was
  acknowledged after inspection; correction/replay remains an explicit gate;
- Cloud Scheduler required `asia-south1` because the service is unavailable in `asia-south2`. Four
  authenticated jobs targeted the Delhi Cloud Run services, were triggered successfully, and were
  paused after the test;
- the first full observability plan proposed five unrelated uptime-IAM deletions and was rejected.
  A machine-checked `9 add, 0 change, 0 destroy` plan was applied instead. A second targeted plan then
  added two log metrics and three operational policies (`5 add, 0 change, 0 destroy`) for authenticated
  Scheduler failures, trace-export failures, and sustained photo-bucket growth. Live Monitoring now has
  110 policies total; all three new policies are enabled and each has the existing operator channel;
- synthetic dev Scheduler and trace-export failures produced metric samples and opened incidents
  `0.oba9gu0758a2` and `0.oba9lyhl6cy5`; both recovered automatically after the signal stopped. No
  application request, Scheduler target, or trace exporter was invoked. The channel is enabled, but
  mailbox receipt remains an operator verification because the Monitoring API does not expose it;
- the previous Cloud SQL memory policy was proven noisy on `db-f1-micro`: all 53 utilization points were
  100% because `memory/usage` and `memory/quota` were both 643,825,664 bytes, while the documented
  `memory/components` Usage signal ranged from 9.71% to 33.49%. A targeted `0 add, 1 change, 0 destroy`
  plan corrected the dev policy to component Usage above 90%, matching Google Cloud OOM guidance. The
  false-positive incident closed at `2026-08-10T23:49:51Z`; eight initial `db-custom-2-7680` samples
  then measured 39.53-40.11% component Usage and 45.73-46.48% legacy utilization;
- the dev photo bucket contained 106 objects totaling 10,296,118 bytes at inspection time. The new
  100-GiB sustained-day threshold is a configurable growth guardrail, not a cost cap or retention policy;
- a cold-start async drain published one school, operations and billing outbox row exactly once and
  reporting processed all three. Guarded DLQ dry-run/apply then preserved, replayed and acknowledged
  one synthetic event; reporting processed the corrected event once;
- a controlled Cloud SQL restart completed in 19.71 seconds. The five Java services recovered to
  HTTP 200 in 44.18-63.48 seconds without a Cloud Run revision restart;
- an isolated long-history clone held 7.59 million attendance rows, including exactly 7.3 million
  for the 10,000-student school over 730 days. Bounded detail/student plans completed in
  0.272-21.728 ms; the reporting aggregate completed in 71.244 ms using a parallel sequential scan,
  so no unsupported index claim or speculative index was added;
- the 2-vCPU database shape was rejected for the target 300-VU burst after CPU exceeded the 80%
  guard in three samples. The full 4-vCPU/7.5-GiB rerun passed 17m30s with 276,923 requests,
  0.013722% HTTP failures (all deliberate 429 responses and no 5xx), 110.543 ms p95, 262.731 ms
  p99, 58.29% maximum database CPU, 48.4233% maximum Usage memory and 99 connections;
- a dev-only PITR drill restored timestamp `2026-08-11T02:14:20Z`: the clone was runnable in
  539.49 seconds and a non-empty 65,248,345-byte synthetic export was validated at 582.57 seconds.
  The evidence correctly records that rows were not checksum-validated and reached `PASSED` only
  after independent absence checks for the clone, export object and temporary bucket IAM binding;
- the certification-created backups were removed and backup/PITR returned to its original disabled
  dev configuration. The first four-hour 300-VU soak on `db-custom-4-7680` did **not** pass: the
  harness stopped at 2h28m after three database CPU samples of 82.1477%, 81.3688% and 82.2645%.
  Before the abort, k6 issued 2,405,050 HTTP requests with 121 deliberate 429 responses, zero 5xx,
  0.005031% total HTTP failures, 281.77 ms p95 and 552.72 ms p99. Memory peaked at 48.089% and
  connections at 94. This artifact is failure evidence, not a capacity pass;
- the immediately following deployed MixedMorning workload also failed: 34,395 requests included
  2,433 5xx/timeouts and 7,944 4xx responses (including 429), for 13.0426% HTTP failures; several
  flows reached 52.58-60 second p95/p99, database CPU reached 84.63%, memory 48.84% and connections
  109. The evidence is preserved under `artifacts/load-certification`;
- investigation found two reproducible database defects. Daily attendance summary executed a
  per-section student count inside its row mapper (an N+1 query), and the school/operations outbox
  relays sorted the text `id` select alias instead of the indexed numeric column. Source now uses one
  school-scoped grouped count and `ORDER BY o.id`; focused tests pass, and PostgreSQL 16 plan evidence
  shows the relay returning to `idx_ts_outbox_ready` with startup cost 0.43. A guarded cleanup was
  added for the 1,413,673 stale pending SCALE-only events after ownership verification. These fixes
  are local until Commit A deploys; the same complete 4h10 soak and MixedMorning workload must be
  rerun on 4 vCPU before any supported-size or cost conclusion. All four Scheduler jobs remain
  `PAUSED`; final fixture cleanup and database downsize/stop are mandatory acceptance steps;
- a fresh GitHub API check confirmed immutable repository ID `1207086249`, owner ID `274906704`,
  current-account push access but no maintain/admin access, zero rulesets, and no classic protection
  on `dev` or `main`. Source now pins all 52 external action uses to verified terminal commit SHAs,
  but applying and negatively testing branch governance remains an administrator-owned blocker;
- a read-only transport audit confirmed both Cloud SQL servers still permit plaintext, all five
  deployed dev services omit an explicit JDBC TLS mode, two of six discovered dev SQL jobs disable
  TLS, and no fresh `pg_stat_ssl` envelope exists. Source now requires TLS and will merge only that
  key into the reusable existing jobs on their next authorized invocation. The checksum-pinned,
  seven-field evidence capture helper passed source/parse safety checks but was deliberately not run;
  no live client or server configuration was changed, and the staged dev rollout plus 24-hour proof
  remain pending.

The production decision remains **NO-GO** until the ledger's production blockers are closed. The
highest-value remaining evidence is the four-hour soak, operator mailbox receipt,
production IAM/WIF/branch controls, Cloud SQL transport enforcement/session proof, and a
named canary school with business/legal approvals.

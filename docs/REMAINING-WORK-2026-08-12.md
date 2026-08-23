# Remaining Work and Production Launch Gates

Evidence cutoff: 2026-08-24 IST (the 12 August baseline is retained below as historical evidence)

Scope: Custoking IMS at 100-150 schools, 200,000-300,000 total student records, and up to 10,000
students in one school. This is the authoritative remaining-work register. It contains no secret values or
student-level data.

Migration reconciliation (24 August 2026): development moved to `custoking-dev` before the production
rehearsal, and production moved to `custoking-prod` on 19 August. The production integrity gate matched all
107 relations (38,677 rows, identical digest), object counts matched, durable event queues were drained,
and the repaired Cloud Deploy release path was subsequently proven. MIG-01 is therefore complete. The
The legacy source project was observed in `DELETE_REQUESTED` state on 24 August; this execution did not
request, cancel, or modify that deletion. The production school-core workload uses service-account Drive
access rather than the legacy OAuth client, and a temporary job under its real runtime identity proved that
the configured Drive root can be read, listed, and written. Billing-export, backup/log-retention, and formal
source-deletion custody still require named owners. The former in-place runbook is retained only as
superseded historical analysis.

## 2026-08-24 production-blocker execution delta

- **ASYNC-01 infrastructure is complete.** Four authenticated production relay schedules return HTTP 200;
  reporting and notification push subscriptions are `ACTIVE` with dedicated OIDC identities, ten delivery
  attempts, 10-600 second retries, seven-day retention, no expiry, and guarded DLQ inspection. Backlogs and
  DLQs were zero, so no unknown production item was manufactured or replayed.
- **Recovery execution passed.** A production PITR clone became `RUNNABLE`, exported a schema-only validation
  artifact, met a 603.96-second validation RTO, and then removed the clone, temporary object, and temporary
  bucket IAM grant. Independent cleanup checks were zero.
- **PRIV-01 technical controls pass.** Counts-only current-tree/history/release scans found no sensitive
  tracked artifact, and CI now runs both repository-boundary and synthetic multi-tenant export/erasure
  controls. DATA-02 remains open only for policy/ownership and full-system production offboarding.
- **NOTIFY-01 code controls pass locally.** Guardian delivery now fails closed unless the active verified
  preference and current guardian-specific consent are present; platform suppression is terminal and
  audited. The change is tested but not committed, reviewed, released, or enabled against a real provider.
- **PERF-01 is cost-blocked before load.** The 300,000-student seed passed on a two-vCPU development SQL
  shape with Query Insights. The governed cost preflight found development month-to-date gross cost already
  above its INR 2,000 budget, while log-volume headroom passed; therefore neither of the two required load
  runs was started without explicit spending-owner approval. Development SQL was then restored to the
  stopped `db-f1-micro` baseline with Query Insights disabled.
- **DATA-01 production-volume rehearsal passes.** A durable PostgreSQL 16 rehearsal migrated 25,000,000
  source rows into four partitions and reconstructed the unpartitioned rollback shape in 761.52 seconds.
  Source, target, and rollback counts were identical; forward/rollback checksums, global uniqueness, FK,
  check, RLS/bypass, pruning, default-partition, index, and cleanup gates all passed. The SQL remains a
  prototype owned by the school-core attendance Flyway track; production threshold monitoring and reviewed
  online rollout DDL still remain.
- **GOV-01 remains authority-blocked.** Exact required check names and desired branch/environment controls are
  documented, but the authenticated GitHub principal has write/triage rather than admin authority. A guarded
  apply refused before mutation; repository visibility and production self-review remain owner decisions.
- **PILOT-01 and real provider delivery remain business-blocked.** No named school, acceptance/support owner,
  production smoke credential, approved consented recipient, sender/template, or commercial approval was
  supplied. Production remains on the logging provider with MSG91 dry-run enabled.

## 2026-08-14 reconciliation delta

- The OpenTelemetry background-flush correction is deployed to all five Java services in dev and production.
  Production commit `754f0417` passed release run `31820051376`; fresh authenticated checks produced exact
  current-revision traces for the gateway and all five Java services with zero exporter errors. One full
  school-day stability window remains.
- The primary operator confirmed receipt of the alert email. A named backup recipient and a complete
  primary/backup delivery test still remain.
- The governed cost-control workflow run `31823448382` succeeded from production commit `754f0417` on
  14 August. Direct inspection showed `custoking-db-dev` `STOPPED`, activation policy `NEVER`; the release
  workflow also skipped SQL startup for a frontend-only dev deployment.
- Production promotion is complete for the operator student export, permanent-delete behavior, clean Temurin
  runtime, dependency batch, cost guard, and OpenTelemetry fix. All seven live endpoints returned HTTP 200.
- Dependabot alerts remain disabled and require repository-admin authority. Branch protection and production
  self-review controls remain GOV-01 administrator work.
- The 12 August baseline table below remains immutable evidence for its cutoff; this delta supersedes only
  the operational claims explicitly listed above.

## Current decision

The deployed system is healthy enough for controlled testing and a named, bounded school canary. It is
not yet certified for simultaneous onboarding of 100-150 schools. Record count alone is not the limiting
factor: the 300,501-student synthetic fleet and exact 10,000-student import passed. The unresolved risk is
concentrated school-day concurrency, query behavior, async execution, data governance, and production
recovery/governance choices.

## Verified baseline

| Area | Evidence at cutoff | Interpretation |
| --- | --- | --- |
| Automated tests | Java 1,032 passed; gateway 61 passed; frontend 147 passed; frontend production build passed | Strong unit/integration baseline; not a substitute for browser E2E or arrival-rate capacity proof |
| Runtime health | 14/14 Cloud Run services Ready; frontend/gateway HTTP 200; no prod HTTP 5xx in the last-day review | Healthy current deployment |
| Runtime identity | every Cloud Run service has a dedicated per-service service account | Completed for services; jobs/targets still drift |
| Production database | PostgreSQL 16, `db-g1-small`, zonal, private IP, encrypted-only, backups/PITR and deletion protection, `max_connections=200` | Safe low-traffic baseline; no HA/capacity certification for broad onboarding |
| Scale data | 300,501 synthetic students; largest school 10,000; exact import completed in 527.372 seconds and was idempotent | Record volume and bulk import are proven in dev |
| Write soak | corrected 4h10m/300-VU attendance soak passed: 4,178,728 requests, zero 5xx, SQL CPU 54.22% | Target write path has strong dev evidence |
| Mixed morning | read-heavy test saturated SQL at 100% on 4 vCPU and 99.45% on 8 vCPU; query telemetry was unavailable | Broad production capacity is not proven; simply buying more CPU is not justified |
| Async | Production relay schedules and reporting/notification OIDC push subscriptions, retry and DLQ topology are active; current backlog is zero | Infrastructure is ready; a governed real-event/DLQ observation remains |
| Cost | Development export through 2026-08-23: INR 2,601.07 gross against an INR 2,000 budget | Further certification load is fail-closed pending explicit spending-owner approval |
| Security | Trivy: 0 High/Critical, 239 Medium, 30 Low; public GitHub repo has no visible main/dev branch protection | No critical image finding, but governance/backlog remains |
| Observability | 110 enabled alert policies, eight uptime checks, one enabled email channel | Coverage exists; human notification receipt and query telemetry remain unproven |

## Priority definitions

- **P0 — launch gate:** close before broad or simultaneous school onboarding.
- **P1 — controlled-canary hardening:** may proceed beside a named canary if the risk owner records an
  exception, owner, expiry, and rollback condition.
- **P2 — maintainability/product improvement:** schedule after P0, unless it blocks a selected school flow.

## P0 launch gates

| ID | Remaining work | Done when | Cost control |
| --- | --- | --- | --- |
| MIG-01 — complete 2026-08-19 | Build and rehearse the approved two-project GCP migration | Development was migrated and rehearsed first; production cut over separately; the 107-relation/38,677-row digest and object/event integrity gates passed; the release path was re-proven | Source retained until its separate continuity/deletion gates close |
| PRIV-01 — technical controls complete 2026-08-24 | Keep student/photographer exports out of Git and define handling/expiry | CI and counts-only current/history/release scans prove no sensitive tracked artifact; recipient custody/expiry belongs to DATA-02 policy ownership | No GCP cost; prevents an expensive privacy incident |
| GOV-01 | Protect `main` and `dev`; prevent prod self-approval; review public visibility | required reviews/checks and force-push/deletion restrictions active; direct-push negative test fails; prod self-review disabled; visibility decision recorded | No runtime cost |
| PERF-01 | Replace the closed-loop MixedMorning test with an approved arrival-rate school-day model and capture query telemetry | workload assumptions signed; Query Insights or equivalent statement telemetry works; test passes twice at the chosen shape without guardrail breach | same-region runner; dev SQL only for test window; cost preflight required |
| DB-01 | Choose production SQL shape, availability, RTO/RPO and connection budget from PERF-01 evidence | written decision names tier, zonal/regional HA, RTO, RPO, pool math, max instances, backup/restore test, monthly envelope and rollback | no resize/HA purchase before evidence and approval |
| DATA-01 — repository controls complete; live approval pending | Design and rehearse attendance partitioning before growth makes it emergency work | 25M-row forward/rollback correctness is proven; guarded maintenance operator stages, schema tests, and disabled-by-default row/index/scan monitoring are reviewable | restored-clone/operator approval, write-freeze window, explicit monitoring apply, and production execution remain; no production DDL or alerts were applied |
| ASYNC-01 — infrastructure complete 2026-08-24 | Complete production async execution and failure handling | relay, push, retry, DLQ and guarded inspection are active; retain a governed real-event/replay observation when an event exists | min scale stays 0; minute cadence is enabled on four relay targets |
| NOTIFY-01 | Add consent/preference enforcement and prove a bounded real provider send | authoritative consent checked before enqueue/send; approved sender/template/commercials; dry-run then one consented recipient canary; provider receipt and audit rows reconciled | keep logging/dry-run until business approval; cap canary volume |
| DATA-02 | Approve retention, export, erasure, offboarding and incident ownership | policy maps each data class to owner/retention/legal basis; school export and verified deletion drill pass; bucket/database/log/backup implications documented | lifecycle/retention changes costed before activation |
| PILOT-01 | Complete one named-school full-day canary | school acceptance signed; morning attendance, imports, fees, reporting and recovery observed; zero unresolved Sev-1/2; rollback and support contacts exercised | one school, bounded users, daily gross-cost review |

### PRIV-01 — local student export control

Verified gap: `outputs/` contains local photographer/student material and was previously unignored in a
public repository worktree. It is untracked in the normal branch, but local checkpoint refs may retain
copies. This change adds `outputs/` to `.gitignore`; it does not move or delete user data.

Required actions:

1. move approved deliverables to an access-controlled location outside the repository;
2. record the business owner, photographer recipient, lawful purpose, expiry date, and deletion date;
3. verify `git log --all -- objects` and the hosting service's normal branches/releases contain no export;
4. decide whether local Codex/checkpoint refs require expiry or cleanup using a recoverable procedure; and
5. add an automated pre-commit/CI filename and large-binary guard without scanning or printing student data.

Acceptance evidence: clean `git status`, secret/PII-oriented repository scan report, recipient transfer
record, and deletion/retention record. Never paste credentials or student rows into the evidence.

### GOV-01 — repository and production approval controls

Verified gap: repository `custokingkr-dev/ims-v1` is public. GitHub APIs show no visible ruleset or classic
protection for `main` or `dev`. The `prod` Environment requires reviewers and disallows admin bypass, but
`prevent_self_review=false`.

Required controls:

- require pull-request review on `main` and `dev`;
- require the actual CI/security summary check names after a fresh pull request proves them;
- block force pushes and branch deletion; require conversation resolution;
- disable self-review for `prod` and retain `can_admins_bypass=false`;
- perform negative tests for direct and force pushes; and
- record an explicit public/private repository decision after checking whether public visibility is needed.

Do not guess required check names. Capture them from a successful current workflow run first.

### PERF-01 and DB-01 — capacity and production database decision

Why it remains: a successful write soak and failed closed-loop read-heavy test measure different systems.
Closed-loop VUs slow their own arrival rate when responses become slow, so they cannot certify a real
morning arrival burst. The 8-vCPU failure and missing query telemetry mean a larger tier cannot yet be
recommended honestly.

Workload model inputs that require product/business confirmation:

- active schools in each onboarding wave;
- users per school and simultaneous login/attendance windows;
- requests per user action and read/write mix;
- expected peak interval, acceptable p95/p99 and error rate;
- per-school fairness behavior; and
- background reporting/import/notification load during the same window.

Implementation and test sequence:

1. enable bounded Query Insights or `pg_stat_statements`-equivalent telemetry in dev;
2. reproduce the MixedMorning bottleneck and identify the top statements by total time, calls and p95;
3. fix query/index/N+1/cache issues before changing the database tier;
4. convert the scenario to constant/ramping arrival rate with explicit dropped-iteration thresholds;
5. run from an approved runner in the same GCP region to avoid external egress distortion;
6. run twice at the cheapest candidate shape that meets CPU, memory, connections, latency and error gates;
7. calculate Cloud Run instance/pool worst case against `max_connections=200`; and
8. decide zonal versus regional HA using approved RTO/RPO, not generic best practice.

Minimum acceptance gate: two reproducible passes; no unexplained 5xx/timeouts; no sustained SQL CPU above
the approved guard; connection ceiling respected; top queries captured; projected monthly gross cost
approved. Restore dev to stopped `db-f1-micro` after evidence capture.

### DATA-01 — attendance history growth

At 200,000-300,000 students, one daily attendance row per student implies roughly 44-66 million rows per
220-day academic year. The current attendance history is unpartitioned. Existing guidance says prepare at
10-20 million rows and execute before 25 million; broad onboarding can reach that range quickly.

Required design:

- select time-based partition key and interval using actual query patterns;
- prove school/date pruning for roster, daily summary and history queries;
- preserve uniqueness, foreign keys, RLS policies and retention semantics;
- define default/future partition creation and late-arriving data behavior;
- rehearse online/backfill migration, rollback and Flyway ordering on production-like volume; and
- alert on row count, largest relation, sequential scans and index growth.

Repository implementation now uses a guarded maintenance-window sequence instead of an application-startup
Flyway migration. That is an intentional safety boundary: the current write path has no dual-write/catch-up
protocol, and pretending the table rewrite is online would risk lost or divergent attendance rows. See
`docs/runbooks/ATTENDANCE-DATA01-PARTITION-ROLLOUT.md` and `docs/DB-SCALING-THRESHOLDS.md`. The application
reporter and `deploy/gcp/observability/attendance_growth.tf` are committed but the Terraform feature flag
defaults off; activation remains a separately approved dev-then-prod plan/apply.

Do not deploy partition DDL directly to production before the rehearsal and restore test pass.

### ASYNC-01 and NOTIFY-01 — production event completion

Verified live topology:

- reporting push: dev/prod active with dedicated OIDC identities; only dev has a DLQ;
- notification push: dev active with dedicated OIDC plus DLQ; production has only a topic;
- four dev Scheduler relay jobs are paused; no production relay schedules exist;
- notification provider remains `logging`, `MSG91_DRY_RUN=true` in production.

Required sequence:

1. decide the relay execution service and region; use authenticated Scheduler/Cloud Run endpoints or jobs;
2. apply least-privilege caller identities and retry/dead-letter policies;
3. prove outbox lease, retry, abandonment, replay, duplicate handling and oldest-age alerting;
4. add production reporting DLQ and guarded replay, then observe one real reporting event end to end;
5. identify a real, consent-checked notification producer before creating prod notification resources;
6. dry-run a canonical notification, then send only to a consented test recipient after approval; and
7. reconcile provider acceptance/delivery with notification attempt and audit records.

### DATA-02 and PILOT-01 — operational launch

Engineering cannot infer legal basis, data retention, message consent, support ownership, or a pilot school.
Named decision owners must record these choices. The canary exit review must include business acceptance,
not just healthy infrastructure.

## P1 controlled-canary hardening

| ID | Work | Verified gap | Acceptance |
| --- | --- | --- | --- |
| IAM-01 | Remove default-Compute dependencies | eight regular Cloud Run jobs and all live dev Cloud Deploy targets use default Compute; one temporary OTel probe uses a platform identity; broad project roles and legacy platform invoker remain | each workload gets least privilege; one successful execution/rollback; legacy bindings removed; temporary probe removed; access diff reviewed |
| SEC-01 | Establish secret lifecycle | 44 secrets have one enabled version but no rotation schedules | owner/rotation period per secret family; dual-version rollout and rollback rehearsed; stale access reviewed |
| SEC-02 | Own Medium/Low vulnerabilities | 239 Medium and 30 Low Trivy findings remain | deduplicated finding-to-package inventory, owner/SLA/exception expiry, scheduled scan remains green for High/Critical |
| IMG-01 | Run gateway/frontend containers rootless | current image posture still needs explicit non-root enforcement | non-root UID, read-only-compatible filesystem, health and proxy tests pass in CI/dev |
| OBS-01 | Promote the dev-proven trace fix and finish alert/stability proof | dev exporter fix and primary email receipt proven; prod promotion, full school-day stability, and backup route remain | zero recurring exporter errors over one school day in each promoted environment; named primary and backup both receive the test incident |
| IAC-01 | Expand declarative ownership | Terraform covers observability/IAM selectively; Cloud SQL, Pub/Sub and full Cloud Run desired state are incomplete | import/adopt resources; plan has no destructive surprise; drift check runs in CI; rollback documented |
| REL-01 | Distribute expensive-route limits | gateway token bucket is process-local; scaling creates independent budgets | per-school/shared budget for expensive routes; cheap reads/bulk writes separated; 429 semantics/load tests pass |
| ONB-01 | Finish resumable onboarding operator UX | backend jobs are more capable than end-to-end operator workflow | resume/retry/cancel/status UX, school-scoped concurrency, idempotency and audit evidence pass |

IAM-01 must be staged. First reconcile dev Cloud Deploy targets to `clouddeploy-dev-deployer`; then create
job-specific identities and prove each job; only then remove default-Compute roles or the legacy platform
invoker. Removing permissions first risks breaking recovery and smoke operations.

## P2 maintainability and product improvements

| ID | Work | Evidence/target |
| --- | --- | --- |
| FE-01 | Reduce frontend chunks | workspace chunk about 913 KB and ExcelJS chunk about 940 KB; lazy-load spreadsheet/export and heavy workspace routes |
| TEST-01 | Raise coverage quality | frontend threshold is 10%; Java has no enforced coverage report; add risk-based thresholds rather than a cosmetic global percentage |
| TEST-02 | Add authenticated browser E2E | cover login/refresh, school scope, 10k import operator flow, attendance, fees, approval and rollback-safe smoke |
| CODE-01 | Split oversized components | `StudentReadRepository` is about 3,374 lines and several files exceed 1,000 lines; split by query/use-case without changing ownership |
| AUDIT-01 | Normalize audit semantics | reconcile actor, school, correlation ID, redaction and immutable event conventions across services |
| DOC-01 | Keep documentation current | this index now classifies historical material; future releases must update current-state and remaining-work records |
| PROD-01 | Enterprise access options | SSO, configurable support contact/mailto and localization remain product decisions, not assumed requirements |
| JAVA-01 | Resolve Java 25 dependency warnings | inventory warnings against upstream support and upgrade only with full service tests |

## One-week execution order

This is a sequencing plan, not a claim that external approvals can be completed within a week.

| Day | Parallel lanes | Exit evidence |
| --- | --- | --- |
| 1 | privacy/export control; repository governance configuration; enable dev query telemetry; create async/IAM change plans | no export eligible for commit; exact required checks known; telemetry query returns useful rows; reviewed non-destructive plans |
| 2 | reproduce/profile MixedMorning; design arrival-rate model; dev Cloud Deploy identity reconciliation; reporting DLQ plan | top SQL statements identified; signed workload draft; target diff and rollback ready |
| 3 | query/index fixes; arrival-rate run 1; attendance partition prototype; job identity creation in dev | test artifact, query plan deltas, partition correctness tests, successful least-privilege job smoke |
| 4 | arrival-rate run 2; restore drill; production async changes in an approved window; alert receipt test | repeatable capacity result, measured restore time, DLQ/replay evidence, human alert receipt |
| 5 | production SQL/HA/cost decision; consented notification decision/canary if approved; named-school canary rehearsal | signed decisions, bounded notification evidence or explicit block, canary checklist and rollback owners |
| 6-7 | one-school-day observation where calendar permits; document reconciliation and go/no-go review | metrics/cost review, open incident list, approval or explicit blocked items |

Tasks requiring repository admin, spending-owner, legal/privacy, messaging-provider, or named-school approval
remain blocked on those owners even if engineering preparation finishes.

## Cost rules for all remaining work

1. Keep all Cloud Run minimum instances at zero unless cold-start evidence and a spending owner justify a
   service-specific exception.
2. Keep dev SQL stopped and relay Scheduler jobs paused outside an approved test/deploy window.
3. Every load run must pass the repository gross-budget preflight; an overrun needs explicit approval and
   recorded estimated cost.
4. Prefer a same-region runner for volume tests to avoid the external egress that inflated the August 11
   dev gateway cost.
5. Optimize queries before buying a larger database. Do not enable regional HA until RTO/RPO and the
   monthly envelope are approved.
6. Keep Artifact Registry cleanup/rollback evidence; do not manually purge deployed digests for a small
   saving. Current repository size is approximately 6.13 GB.
7. Budgets are alerts, not hard caps. Monitor gross cost because promotional credits are temporary.
8. Keep the migrated `custoking-dev` and `custoking-prod` projects authoritative. The source is already
   `DELETE_REQUESTED`; do not change that state without owner authority. Preserve the 24 August Drive
   runtime-identity evidence and assign billing-export, backup/log-retention, and deletion-custody owners.

## Broad-onboarding go/no-go checklist

Broad onboarding is **GO** only when all are true:

- [x] MIG-01 destination projects, dev rehearsal, rollback, and source/destination integrity gates passed.
- [ ] PRIV-01 export handling and repository/history verification complete.
- [ ] GOV-01 branch/environment controls pass negative tests.
- [ ] PERF-01 arrival-rate mixed workload passes twice with query evidence.
- [ ] DB-01 production tier, HA, RTO/RPO, connections and cost are approved and deployed/tested.
- [ ] DATA-01 staged migration has passed the approved restored-clone/operator drill and threshold monitoring
  has been explicitly activated in dev then production. Repository controls and the 25M rehearsal are complete;
  no production DDL or alerting change has been applied.
- [ ] ASYNC-01 production relays, reporting DLQ/replay and end-to-end reporting event are proven.
- [ ] NOTIFY-01 consent enforcement and provider posture are approved; otherwise notification-dependent
  product flows remain disabled.
- [ ] DATA-02 retention/export/erasure/offboarding policy and drill are approved.
- [ ] PILOT-01 named-school full-day canary exits without unresolved Sev-1/2 issues.
- [ ] OBS-01 alert receipt and trace-export stability are proven for one school day.
- [ ] Spending owner approves the resulting gross monthly envelope without relying on credits.

If any box is incomplete, onboarding may continue only as a specifically bounded canary whose traffic,
features, duration, owner, rollback condition and accepted risk are written down.

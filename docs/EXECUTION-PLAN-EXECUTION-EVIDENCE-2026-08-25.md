# Execution Plan — Repository Execution Evidence — 2026-08-25

Status: repository changes implemented, promoted, and selectively applied to production; the continuation
batch passed the normal dev-to-production promotion path and is live; governed gates remain.

This is the execution companion to `PROJECT-DEEP-ANALYSIS-AND-EXECUTION-PLAN-2026-08-25.md`.
It records what was completed in the repository, what was promoted through the production canary, what was
selectively applied to GCP, and which acceptance gates still require authority, spending, destructive-change
approval, or a real operating window.

## Executive result

The repository-safe implementation batch is complete and the full local verification matrix is green.
The work established the Java build foundation, generated an auditable API route contract, added
non-destructive database consolidation and repair paths, hardened the live-dashboard authentication code,
improved billing/observability evidence, and decomposed representative frontend and backend hotspots.

The full multi-week program is not operationally complete. First-delivery verification for the newly enabled
Cloud Billing exports, Cloud SQL availability, repository governance, provider canaries, destructive database
retirement, and a representative school-day certification require external authority or evidence that
engineering cannot invent. Those gates are listed explicitly below.

## Phase status

| Phase | Repository result | Remaining gate |
| --- | --- | --- |
| 0 — Baseline | Complete for this batch | Run generated read-only evidence against each promotion candidate |
| 1 — Production blockers | Repository-owned fixes, production promotion, and Billing export enablement complete | First Billing table delivery/reconciliation, SQL RTO/RPO decision, GitHub admin controls, provider canary, approvals, school-day observation |
| 2 — Engineering foundation | Root reactor, parent, versions, Enforcer, convergence and CI/build catalog complete | Shared-source extraction remains deferred because the candidates alter Spring discovery/type identity; the drift guard remains active |
| 3 — API contracts | Machine-readable inventory, ownership checks, compatibility telemetry and deprecation headers complete | OpenAPI/typed-client migration and route deletion require staged client migration plus a measured zero-traffic window |
| 4 — Database consolidation | Non-destructive bridges, ledgers, parity views, projection repair and evidence queries complete | Production backfill review, retention window, reconciliation evidence and separately approved destructive DDL |
| 5 — Code/client performance | Representative frontend/backend hotspots decomposed; spreadsheet actions remain lazy | Remaining hotspot backlog and Web Worker migration require exact embedded-image/workbook browser-worker fixtures |
| 6 — GCP/observability | Dashboard, exporter, billing panels and corrected storage alert applied | Incident-owner testing, Cloud Asset API/permission, VPC design approval; Google API normalization leaves a non-functional dashboard JSON plan diff |
| 7 — Dependencies | Upgrade families and rollback boundaries audited | Execute one major family per isolated pull request; not mixed into the database/API batch |
| 8 — Certification | Automated local portions pass | Restore/PITR, optional HA failover, provider delivery, named-school canary and full school-day observation |

## Continuation execution — 2026-08-25

The next execution pass converted several earlier assumptions into live evidence and closed the safe
repository-owned blockers:

- Enabled the Cloud Asset Inventory API in `custoking-prod` and captured a canonical read-only snapshot of
  1,182 assets. The first snapshot established the baseline with zero computed drift. It included 131
  Artifact Registry images, 73 Cloud Run revisions, 308 Cloud Run job executions, 73 alert policies and 11
  custom dashboard resources. The `custoking` Artifact Registry repository held approximately 4.47 GB and
  already had a seven-day delete policy plus a keep-most-recent-three rule.
- Took on-demand Cloud SQL backup `1787656491610` successfully before the consolidation evidence run and
  changed production `custoking-db-prod` to `ENCRYPTED_ONLY`. The gateway health check and all 31 gateway
  routes passed after the change; no SSL or connection errors appeared in the verification window.
- Hardened recovery automation so its bucket is always the explicit project-scoped
  `<project_id>-db-snapshots` bucket. A new fail-fast prerequisite audit verifies the exact recovery identity,
  source instance, conditioned custom-role binding, project permissions and bucket permissions before any
  clone is created. Recovery IAM remains disabled pending security-owner approval.
- Added a production-only, disposable Cloud Run evidence runner that requires the dedicated migration
  operator, obtains the database password only from Secret Manager, requires TLS, and independently enforces
  both session and transaction read-only modes. The runner submits a temporary manifest so the aggregate SQL
  never crosses Windows' command-line limit. Its production execution completed, its temporary manifest and
  job were deleted, and the migration operator was disabled again.
- The read-only database evidence found zero billing migration issues, zero catalog rows requiring mapping,
  1,498 student rows with 1,036 exact guardian matches, one missing reporting student projection, four
  operations outbox rows and 1,591 school-core outbox rows older than 30 days, and one duplicate identity
  index definition pair. `pg_stat_statements` is not enabled. No rows or indexes were changed.
- Guardian mismatch anatomy is now explicit: 385 father names and 376 father contacts are present only in
  legacy columns; 51 father names and 33 contacts differ on both sides; one father name and one contact are
  present only in the normalized model; and 11 mother names are present only in legacy columns. There are 203
  normalized father identities linked to multiple students, 25 of which have conflicting sibling legacy
  values; the largest shared group has four students. Those 25 groups are excluded from automatic repair.
- Added forward synchronization from student create, update and spreadsheet-import writes into normalized
  guardian relationships. Existing guardian/link identities, consent references, permissions and primary
  flags are preserved, and normalized mother contact data is not erased by the legacy API. The complete
  school-core suite passed with 569 tests; separate PostgreSQL 16 integration tests prove exact parity on
  all three write paths. PR 157 merged as `2a16e1df40344a7fdf2ab44ed356a160381b6705`; production release
  `rel-prod-2a16e1df4034-1` serves 100% on revision
  `custoking-school-core-service-prod-mt8m8se6`. Production CodeQL, Trivy, Cloud Deploy verification and an
  independent 31-route gateway smoke all passed, with no post-release school-core error logs in the checked
  window.
- Closed the remaining shared-guardian write-path drift in both directions. Global identity changes now fan
  out to every linked student's legacy projection, while relationship, primary, permissions, link versions,
  guardian IDs and consent history stay student/link scoped. Optimistic locking now checks the per-student
  link version separately from the global guardian version. PR 159 merged as
  `42ac65257394c2084004f7b5dab6adef12ac2c19`; production release `rel-prod-42ac65257394-1` completed both
  canary rollouts. School-core revision `custoking-school-core-service-prod-mt8omlnl` serves runtime digest
  `sha256:43d6c3279ea459fbf9f88c8a1a3d8631c1b32f181578c270c98248fa2a21b0df`, and frontend revision
  `custoking-frontend-prod-mt8or9qo` serves runtime digest
  `sha256:2e7d988b2009ace88b90e15342612e475c8a51822b96425886c4caeb93ee0f1b`; both are Ready at 100% traffic.
  Main-branch CodeQL, exact-digest Trivy, workflow gateway health, an independent 31-route smoke, and
  exact-revision error/5xx queries all passed.
- Added a privacy-safe guardian repair classifier and then hardened it to classify only whole-student,
  zero-link creation candidates. It rejects inactive/missing schools, unrepresentable legacy values,
  guardian-bound or anomalous consent, deterministic-ID collisions, same-school identity candidates,
  repeated legacy identity clusters, global shared-identity hazards, and every non-effective link shape.
  Output is aggregate counts plus deterministic full/safe-create hashes only; no PII is emitted and the
  classifier cannot mutate the database.
- Centralized student review invalidation across guardian consent, manual student edits, photo import and
  photo recovery. Profile changes preserve photo verification; photo changes preserve profile verification;
  every invalidation emits the existing `student-review-item.upserted.v1` contract atomically in the caller
  transaction. PR 163 merged as `243c2ef113f7eeacafdd386eb00cb955d18dc2db`; 30 focused tests and the
  complete release gates passed. Production release `rel-prod-243c2ef113f7-1` completed 5/25/50/stable
  canaries and serves 100% on school-core revision `custoking-school-core-service-prod-mt8vr65k`, image
  index digest `sha256:ef4e00f85e781ab9a343e0f59217365510fd00eb9084bfcd3d92278709b0555b`.
- The expanded SQL initially exceeded Cloud Run's single environment-value envelope at 36,872 raw-base64
  characters. PR 165 (`3726d4ccb7e7b825bee7ca7ecef2381d50ebdf06`) gzip-compressed it to 6,720
  characters, added a 30,000-character pre-resource guard, and retained exact gzip round-trip coverage.
  Production read-only job `ims-db-evidence-20260825164648-3628c1fc` then exited 0 and was deleted. It found
  13 eligible whole students, 14 guardian relationships and 15 legacy fields for possible safe creation;
  the full plan hash is `425d085c2c8fe00c4fca554b0124e6d05e1f16e570226d08307f0e7337cbaf38` and the
  safe-create hash is `fe0425a615d15a1444cd8cbd9b3bbe64a5360a6b8a3a9f33e5b6110be7684492`.
  No guardian row was written; the migration operator is disabled and zero evidence jobs remain.
- Added the governed guardian safe-create capability without executing it. V25 is the single owner-only
  planner used by both evidence and execution; its semantic contract pins deterministic IDs, create-only
  behavior, least-privilege links, no consent changes, profile-only review invalidation, exact outbox events,
  locking, and ledger replay. V26 adds the owner-only atomic repair and audit ledger. It requires a
  `SERIALIZABLE` transaction, a matching contract/plan/count tuple, a transaction advisory lock, and
  fail-fast `SHARE MODE NOWAIT` locks across every classifier and review source. The disposable Cloud Run
  runner defaults to local validation, uses the disabled migration operator and owner credential only,
  pins the exact region/private host/database/secret/network/subnet and PostgreSQL image digest, sets
  `maxRetries=0`, deletes its job, and disables the operator after success or failure. The database function
  itself compiles the old reviewed hash/count tuple, so direct owner invocation and the runner both remain
  inert after V25 changes the fingerprint. A later reviewed migration must replace that pin from fresh
  post-deployment read-only evidence. The operational ledger records approval reference, deployed source
  revision, runner payload digest, Cloud Run job name and database user; Cloud Logging and repository
  approvals remain the immutable provenance because the database owner can administer owner-held tables.
  The final repository contract digest is
  `fa0ca25fd6c2f2e63f9040cebeb3899481415540ca3cc61a331624836012b641`; no production guardian row was
  mutated by this implementation pass.
- Removed an unnecessary `packages: write` grant from the GCP image build and added an enforced CI policy
  that rejects promotions to `main` from any branch other than `dev`. Live GitHub evidence still shows no
  branch protections or rulesets; the current operator has repository `WRITE`, not `ADMIN`, so the final
  server-side ruleset cannot be applied from this identity.
- Added a read-only, immutable-SHA GitHub governance verifier that proves the three reviewed check names
  succeeded and exactly match strict classic-protection or active-ruleset contexts on both `main` and
  `dev`. Fixture coverage fails closed on stale SHAs, failed checks, case/typing drift, extra contexts,
  non-strict policy, excluded rulesets and `evaluate`-only rulesets; no GitHub setting is changed.

Cloud SQL utilization does not justify a larger instance for capacity: CPU averaged 6.72% and peaked at
15.91%, memory averaged 43.96% and peaked at 50.83%, hourly p95 connections peaked at nine, and disk usage
peaked at 0.178 GiB. Availability is the unresolved issue. The current shared-core, single-zone instance is
approximately INR 3,128/month; the minimum observed dedicated regional-HA option is approximately INR
11,708/month. That spend/RTO choice is intentionally not made by automation.

## Implemented repository changes

### Java build and delivery foundation

- Added a root Maven parent/reactor for all five Spring services.
- Centralized inherited versions and plugins and enforced Maven 3.9+ and Java 25.
- Enabled dependency convergence, retaining only six documented pre-existing conflict baselines.
- Updated service Dockerfiles, Compose, CI/release/security workflows, Dependabot coverage, and shared
  build/deployment audit scripts for the root build context.
- Preserved per-service deployment boundaries and affected-service promotion behavior.

### API ownership and compatibility controls

- Added a reproducible route-inventory generator and checked-in inventory.
- Inventoried 383 Spring endpoints across 38 controllers: 287 canonical, 90 compatibility, and six internal.
- Cross-checked 67 gateway matchers, 12 diagnostic aliases, and 90 frontend compatibility calls in 19
  production frontend files.
- Added method-aware compatibility classification, ownership/freshness tests, structured gateway request
  telemetry and trace attributes.
- Added RFC 9745 `Deprecation` headers, validated optional `Sunset`, and successor `Link` only where a
  successor is proven. No compatibility route was removed.

### Database consolidation and consistency

- Added an idempotent legacy-superadmin-invoice bridge into canonical billing customer, invoice, item and
  payment structures. Rows without a school remain explicit issues instead of being assigned to a guessed
  tenant.
- Added an owner-controlled mapping ledger for legacy supply orders and annual-plan entries whose source
  structures do not contain enough tenant identity to migrate safely without an explicit mapping.
- Added guardian legacy/canonical parity evidence.
- Added reporting student-dimension reconciliation and a guarded latest-event requeue repair path that
  cannot replay an older event ahead of newer pending work.
- Added a read-only consolidation evidence query for parity, projection issues, outbox retention candidates,
  table/index size, zero-scan indexes, duplicate definitions, and `pg_stat_statements` availability.
- Added an operator runbook. No table, column, index, or source row is dropped by this batch.

### Billing, GCP observability and dashboard security

- Hardened dashboard OAuth with encrypted browser-bound single-use state, S256 PKCE, OIDC nonce verification,
  upstream status/timeout/size checks, pinned public callback behavior, and logout regression coverage.
- Released the hardened dashboard as scanned image `v11`; the production Cloud Run revision is ready at
  100% traffic and its authorization redirect exposes only the encrypted browser-flow cookie.
- Documented that logout clears browser cookies and revokes the session only in the current process; rotating
  `SESSION_SECRET` derives the authenticated-encryption key and is the current global invalidation mechanism.
- Corrected Terraform's photo storage alert target to `custoking-{env}-student-photos`.
- Added billing export grade, configured capability, availability, export lag and usage lag signals,
  distinguishing estimated run-rate from actually usable invoice-grade standard/detailed rows.
- Added read-only billing export health, Monitoring resource/filter validation, and Cloud Asset drift tools
  with JSON/Markdown output and fixtures.
- Added weekly read-only GCP governance automation for Cloud Asset drift and Monitoring dashboard/alert
  filter validation. Its checked-in baseline is a privacy-safe reviewed digest with explicit volatile-type
  exclusions; scheduled runs cannot generate or approve a replacement. The workflow fails closed on drift,
  missing resources and Monitoring query/API errors, uses a dedicated exact-workflow WIF identity and
  read-only custom role, and retains redacted evidence. The Terraform identity/WIF change and repository
  variable remain unapplied external setup; no GCP resource was mutated by this repository batch.
- Executed the production cost-metric exporter successfully and published fresh grade/availability/lag
  series. The exporter truthfully reported grade `0`, standard `0`, detailed `0`, and both usage/export lag
  as `-1` because no usage table exists yet.
- Applied the billing dashboard panels and replaced the stale storage-growth alert with policy
  `12758936125964704877`, filtered on the live `custoking-prod-student-photos` bucket.

Initial read-only production evidence found 12 dashboards, 73 enabled alert policies, 107 unique Monitoring
filters, 95 filters with series, 12 valid filters with no data, and zero filter query errors. The corrected
alert and billing dashboard are now live. Standard and detailed usage cost exports were enabled on 2026-08-25,
both targeting `custoking-prod.billing_export`. BigQuery subsequently created both
`gcp_billing_export_v1_014C0A_C6B9AF_5FABC0` and
`gcp_billing_export_resource_v1_014C0A_C6B9AF_5FABC0`. The first reconciliation found zero rows in both
tables, which is expected during the asynchronous initial population and does not constitute usable cost
delivery. PR 164 (`3535416c844bd5f9d1072faca45f0f7c46b261b1`) separated configured capability
from observed evidence in both the report and Monitoring exporter. The live read-only result is now
capability grade `2`, evidence grade `0`, standard capability `1`, detailed capability `1`, availability
`0`, `NO_MATCHING_PROJECT_ROWS`, and both lag values unavailable until a scoped usage row arrives. Partial
query failures preserve any usable sibling-table evidence while still failing the job for investigation.

### Frontend and backend hotspot work

- Split photo-import model, batch summary, review table, and dialogs from `PhotoImportPanel` with direct
  tests. The panel fell from 1,219 to 775 lines.
- Reduced the initial photo-import chunk from 30.61 KB/8.82 KB gzip to 24.65 KB/7.86 KB gzip. SheetJS and
  ExcelJS remain separate action-lazy chunks because they serve different tested workflows.
- Extracted receipt lookup and PDF rendering from `FeeReadRepository` into focused Spring collaborators,
  preserving all four receipt APIs, SQL behavior, response shapes, and the existing test constructor.
- Kept Web Worker migration gated: current tests prove embedded-image extraction and workbook generation on
  the main browser path but do not yet prove binary parity in a worker environment.

## Verification evidence

| Verification | Result |
| --- | --- |
| Root Maven reactor, JDK 25 | 1,105 tests; zero failures, errors, or skips; build success |
| Billing service | 56 tests, including PostgreSQL 16 legacy invoice migration |
| Identity service | 118 tests |
| Operations service | 125 tests |
| Platform service | 244 tests, including PostgreSQL 16 projection reconciliation |
| School-core service | 572 tests, including PostgreSQL 16 planner/repair migrations and shared-guardian synchronization |
| API gateway | 78 tests; contract inventory current |
| Frontend | 160 tests across 31 files; TypeScript and production build passed |
| Live dashboard | 6 authentication/server tests; CodeQL Java/Kotlin and JavaScript/TypeScript passed |
| GCP audit Python tests | 3 tests |
| Billing health PowerShell tests | Passed |
| Cost exporter shell tests | Passed with Git Bash, including both-empty and all partial-query-failure directions |
| Guardian classifier PostgreSQL 16 fixture | Passed; deterministic full and safe-create hashes |
| Guardian repair PostgreSQL 16 fixture | Passed; stale-plan and concurrent-writer rejection, least-privilege create, atomic outbox/review/ledger, runtime denial and replay |
| Guardian repair Cloud Run fixture | Passed; dry-run isolation, exact digest/count gates, compressed transport, failure cleanup and operator disablement |
| Cloud SQL evidence transport fixture | Passed; exact gzip round-trip and guarded payload envelope |
| Terraform | Recursive format check and observability validation passed |
| Docker Compose | Configuration validation passed |
| npm production audits | Frontend and gateway: zero reported vulnerabilities |
| Deployment/microservice audits | Passed, including runtime schema, authorization, package, promotion and rollback guards |
| Git whitespace | Passed; line-ending conversion warnings only |

## Dependency execution boundaries

Safe patch candidates observed were `@testing-library/user-event` 14.6.4 → 14.6.6 and `lucide-react`
1.31.0 → 1.34.0. Major families must remain atomic: React/types 18 → 19, Vite/plugin 6/4 → 8/6,
Vitest/coverage 3 → 4, jsdom 26 → 30, and TypeScript 5.9 → 7. Java major candidates such as Google
authentication libraries, OpenPDF, and the Logstash encoder likewise remain separate compatibility work.
No major dependency was mixed into this batch.

## Governed production actions still required

1. Standard and detailed tables now exist in `custoking-prod.billing_export`, but both remain empty. Verify
   the first non-empty delivery, reconcile standard and detailed row windows and totals, and confirm the
   exporter changes availability to `1` with non-negative lag. Because this is a first-time export into a
   US multi-region dataset, Google backfills from the start of the previous month in chronological order;
   recent usage can take up to five days to appear. Re-enabled exports or a move to a new dataset do not
   automatically fill the intervening historical gap.
2. The production owner must approve and fund either regional HA on a supported dedicated-core Cloud SQL
   tier or temporary pilot-risk acceptance with explicit RTO/RPO and tested restore evidence.
3. A GitHub repository administrator must apply branch/ruleset and Environment controls using exact check
   names confirmed by a fresh pull request.
4. Future application changes must continue through the normal canary path; the execution-plan batch is
   already deployed and its seven application services, dashboard `v11`, exporter, billing dashboard and
   corrected storage alert were independently verified in production.
5. The first Cloud Asset Inventory baseline is now captured. Future runs must compare against the approved
   baseline and alert on unexplained additions, removals or configuration changes.
6. A real notification canary needs an approved sender, template, recipient and business authorization.
7. DATA-02 must supply the retention/legal-basis decision before processed outbox rows, legacy source tables,
   guardian columns, compatibility routes, or candidate indexes are removed.
8. Named-school owners must schedule the canary, representative school day, restore/PITR exercise, photo
   profile/ID-card/export validation, and rollback observation.
9. Guardian forward synchronization, shared-identity fan-out, the shared planner, atomic owner-only repair
   ledger and disposable runner are implemented. The last deployed read-only classification found 13 whole
   students / 14 relationships / 15 fields, while the other 843 planned fields remain excluded by explicit
   hazards. Promote V25/V26 through the normal canary, then run the aggregate evidence again in read-only
   mode. Because the contract is now part of each fingerprint, review and pin the new safe-plan hash before
   any write; the runner deliberately retains the old hash so it currently fails closed. A separately
   recorded production-write approval is still required after the exact contract digest, new hash and counts
   are shown. The 25 divergent shared groups and every other review bucket remain manual; never rewrite
   shared guardians or consent events. Require parity to reach zero before any legacy parent column
   retirement.
10. Review the single missing reporting projection by identifier in the protected evidence channel before
    invoking the existing one-student idempotent requeue function. Do not bulk requeue projections.
11. Processed outbox retention and the duplicate identity index remain observation-only evidence until
    DATA-02 and a representative query/index window authorize deletion.

Production mutations were limited to reviewed releases; the targeted observability resources; Cloud Asset
Inventory API enablement; on-demand backup `1787656491610`; and Cloud SQL `ENCRYPTED_ONLY` enforcement.
The dedicated migration operator was returned to disabled state after each read-only evidence attempt; the
successful classifier job exited 0 and its disposable job was deleted. No
external notification message was sent, no recovery IAM was enabled, and no database row or index was
deleted by this continuation batch.

# Execution Plan — Repository Execution Evidence — 2026-08-25

Status: repository changes implemented, promoted, and selectively applied to production; the continuation
batch is locally verified and awaiting the normal dev-to-production promotion path; governed gates remain.

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
  both session and transaction read-only modes. Its production execution completed, its temporary job was
  deleted and the migration operator was disabled again.
- The read-only database evidence found zero billing migration issues, zero catalog rows requiring mapping,
  1,498 student rows with 1,036 exact guardian matches, one missing reporting student projection, four
  operations outbox rows and 1,591 school-core outbox rows older than 30 days, and one duplicate identity
  index definition pair. `pg_stat_statements` is not enabled. No rows or indexes were changed.
- Added forward synchronization from student create, update and spreadsheet-import writes into normalized
  guardian relationships. Existing guardian/link identities, consent references, permissions and primary
  flags are preserved, and normalized mother contact data is not erased by the legacy API. The complete
  school-core suite passed with 565 tests; a separate PostgreSQL 16 integration test proves exact parity on
  all three write paths.
- Removed an unnecessary `packages: write` grant from the GCP image build and added an enforced CI policy
  that rejects promotions to `main` from any branch other than `dev`. Live GitHub evidence still shows no
  branch protections or rulesets; the current operator has repository `WRITE`, not `ADMIN`, so the final
  server-side ruleset cannot be applied from this identity.

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
- Added billing export grade, availability, export lag and usage lag signals, distinguishing estimated
  run-rate from invoice-grade standard/detailed usage export.
- Added read-only billing export health, Monitoring resource/filter validation, and Cloud Asset drift tools
  with JSON/Markdown output and fixtures.
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
delivery. The enabled hourly exporter discovered both tables and published grade `2`, standard `1`, detailed
`1`, while correctly retaining availability `0` and both lag values at `-1` until a usage row arrives.

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
| School-core service | 565 tests, including PostgreSQL 16 catalog consolidation and guardian forward-sync |
| API gateway | 78 tests; contract inventory current |
| Frontend | 160 tests across 31 files; TypeScript and production build passed |
| Live dashboard | 6 authentication/server tests; CodeQL Java/Kotlin and JavaScript/TypeScript passed |
| GCP audit Python tests | 3 tests |
| Billing health PowerShell tests | Passed |
| Cost exporter shell tests | Passed with Git Bash |
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
   exporter changes availability to `1` with non-negative lag. New exports do not backfill earlier history.
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
9. Deploy the guardian forward-sync continuation batch, then execute a separately reviewed repair of the 462
   existing mismatched students. Preserve normalized guardian IDs, consent references and normalized-only
   fields; require parity to reach zero before any legacy parent column retirement.
10. Review the single missing reporting projection by identifier in the protected evidence channel before
    invoking the existing one-student idempotent requeue function. Do not bulk requeue projections.
11. Processed outbox retention and the duplicate identity index remain observation-only evidence until
    DATA-02 and a representative query/index window authorize deletion.

Production mutations were limited to the reviewed release and the four targeted observability resources
described above. No external notification message was sent.

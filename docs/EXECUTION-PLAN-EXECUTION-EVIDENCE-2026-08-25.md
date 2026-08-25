# Execution Plan — Repository Execution Evidence — 2026-08-25

Status: repository changes implemented and locally verified; not committed, deployed, or applied to GCP.

This is the execution companion to `PROJECT-DEEP-ANALYSIS-AND-EXECUTION-PLAN-2026-08-25.md`.
It records what was completed in the repository, what was verified against read-only live inventory, and
which acceptance gates still require authority, spending, destructive-change approval, or a real operating
window. It must not be used as evidence that production was changed.

## Executive result

The repository-safe implementation batch is complete and the full local verification matrix is green.
The work established the Java build foundation, generated an auditable API route contract, added
non-destructive database consolidation and repair paths, hardened the live-dashboard authentication code,
improved billing/observability evidence, and decomposed representative frontend and backend hotspots.

The full multi-week program is not operationally complete. Cloud Billing export, Cloud SQL availability,
repository governance, provider canaries, production deployment, destructive database retirement, and a
representative school-day certification require external authority or evidence that engineering cannot
invent. Those gates are listed explicitly below.

## Phase status

| Phase | Repository result | Remaining gate |
| --- | --- | --- |
| 0 — Baseline | Complete for this batch | Run generated read-only evidence against each promotion candidate |
| 1 — Production blockers | Repository-owned fixes complete | Billing-account export, SQL RTO/RPO decision, GitHub admin controls, provider canary, approvals, school-day observation, deployment |
| 2 — Engineering foundation | Root reactor, parent, versions, Enforcer, convergence and CI/build catalog complete | Shared-source extraction remains deferred because the candidates alter Spring discovery/type identity; the drift guard remains active |
| 3 — API contracts | Machine-readable inventory, ownership checks, compatibility telemetry and deprecation headers complete | OpenAPI/typed-client migration and route deletion require staged client migration plus a measured zero-traffic window |
| 4 — Database consolidation | Non-destructive bridges, ledgers, parity views, projection repair and evidence queries complete | Production backfill review, retention window, reconciliation evidence and separately approved destructive DDL |
| 5 — Code/client performance | Representative frontend/backend hotspots decomposed; spreadsheet actions remain lazy | Remaining hotspot backlog and Web Worker migration require exact embedded-image/workbook browser-worker fixtures |
| 6 — GCP/observability | Terraform correction and read-only validation/reporting tools complete | Reviewed apply/deployment, incident-owner testing, Cloud Asset API/permission, VPC design approval |
| 7 — Dependencies | Upgrade families and rollback boundaries audited | Execute one major family per isolated pull request; not mixed into the database/API batch |
| 8 — Certification | Automated local portions pass | Restore/PITR, optional HA failover, provider delivery, named-school canary and full school-day observation |

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

Read-only production evidence at execution time found 12 dashboards, 73 enabled alert policies, 107 unique
Monitoring filters, 95 filters with series, 12 valid filters with no data, and zero filter query errors. The
deployed alert still references the nonexistent `custoking-student-photos-prod` bucket until Terraform is
reviewed and applied. Billing is `ESTIMATED_ONLY`: pricing export exists, but standard and detailed usage
export tables do not.

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
| School-core service | 562 tests, including PostgreSQL 16 catalog consolidation |
| API gateway | 78 tests; contract inventory current |
| Frontend | 160 tests across 31 files; TypeScript and production build passed |
| Live dashboard | 5 authentication/server tests |
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

1. A Billing Account Administrator/Costs Manager must enable and verify standard or detailed Cloud Billing
   usage export. New exports do not backfill earlier history.
2. The production owner must approve and fund either regional HA on a supported dedicated-core Cloud SQL
   tier or temporary pilot-risk acceptance with explicit RTO/RPO and tested restore evidence.
3. A GitHub repository administrator must apply branch/ruleset and Environment controls using exact check
   names confirmed by a fresh pull request.
4. A release owner must review/apply Terraform and deploy/promote the dashboard, exporter, gateway, frontend,
   Java services, and database migrations through the normal canary path.
5. Cloud Asset API enablement and organization/project permission are required before canonical live drift
   snapshots can be generated.
6. A real notification canary needs an approved sender, template, recipient and business authorization.
7. DATA-02 must supply the retention/legal-basis decision before processed outbox rows, legacy source tables,
   guardian columns, compatibility routes, or candidate indexes are removed.
8. Named-school owners must schedule the canary, representative school day, restore/PITR exercise, photo
   profile/ID-card/export validation, and rollback observation.

No production resource was mutated and no external message was sent while producing this evidence.

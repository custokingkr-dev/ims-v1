# Project Deep Analysis and Execution Plan — 2026-08-25

Status: dated research, verified observations, and proposed execution plan.

This document records the repository, database, API, dependency, live GCP, billing, observability,
and production-readiness analysis performed on 2026-08-25. It is not a permanently authoritative
inventory. When a live-state assertion conflicts with newer evidence, follow the precedence in
`DOCUMENTATION-INDEX.md`.

No application code, database object, or cloud resource was changed during this analysis.

## Executive conclusion

Do not merge more runtime services or physical databases now. The system has already reached a
reasonable deployment topology: a React frontend, a Node gateway, five Spring Boot domain services,
and one PostgreSQL database divided into service-owned schemas.

The highest-value work is:

1. close the remaining production governance and business-approval gates;
2. repair invoice-grade Cloud Billing export;
3. make an explicit Cloud SQL availability/RTO/RPO decision;
4. harden the live dashboard OAuth flow and correct stale alert filters;
5. introduce API contracts and retire compatibility routes safely;
6. consolidate only the verified legacy/canonical table pairs;
7. extract narrowly scoped shared infrastructure modules; and
8. decompose oversized repositories and frontend panels without changing service boundaries.

Service consolidation would produce negligible infrastructure savings at the observed traffic level.
Cloud SQL, not the number of Cloud Run services, is the dominant cost and availability decision.

## Scope and method

The analysis covered:

- repository topology, file counts, code volume, large modules, tests, and build output;
- Maven and npm dependency state;
- controller routes, compatibility APIs, frontend usage, and gateway routing;
- database migrations, schemas, tables, indexes, constraints, RLS policies, and data ledgers;
- live Cloud Run, Cloud SQL, Scheduler, Pub/Sub, Monitoring, IAM, Artifact Registry, Storage,
  networking, Cloud Deploy, and BigQuery billing resources;
- current and historical repository documentation; and
- two recursive research passes against official Google Cloud and PostgreSQL documentation.

Local repository claims were verified from the checked-out `dev` branch and available live-project
inventory. Web research used primary documentation only.

## Verification baseline

| Verification | Result |
| --- | --- |
| Billing service tests | 54 passed |
| Identity service tests | 118 passed |
| Operations service tests | 125 passed |
| Platform service tests | 242 passed |
| School-core service tests | 554 passed |
| Total backend tests | 1,093 passed; zero failures, errors, or skips |
| Gateway tests | 70 passed |
| Frontend tests | 156 passed across 29 files |
| Frontend production build | Passed |
| Runtime boundary audit | Passed |
| npm production audits | Zero reported vulnerabilities in frontend and gateway |
| Database boundary audit | Not executed locally because the local PostgreSQL container was not running |
| Worktree after analysis | Clean |

The unavailable local PostgreSQL audit is an environment limitation, not evidence of an application
failure. It should be rerun in a configured CI or local integration-test environment.

## Repository and architecture analysis

### Current deployment topology

The monorepo contains seven principal application deployables plus the live dashboard:

- React frontend;
- Node.js API gateway;
- identity service;
- school-core service;
- operations service;
- platform service;
- billing service; and
- custom live operations dashboard.

The previous twelve-service topology has already been consolidated into five Spring Boot domain
services. That consolidation is supported by the current domain and schema ownership boundaries.
Further runtime merging is not justified by cost, traffic, or code coupling evidence.

### Repository scale

| Area | Files / approximate size |
| --- | ---: |
| `services/` | 628 files; 70,793 lines |
| `frontend/` | 156 files; 27,673 lines |
| `scripts/` | 148 files; 19,393 lines |
| `docs/` | 92 files at analysis time; 18,619 lines |
| `deploy/` | 50 files; 7,156 lines |
| `infra/` | 11 files; 863 lines |
| Java | 454 files |
| SQL | 169 files |
| PowerShell | 123 files |
| TypeScript/TSX | 135 files |

Service code and test distribution:

| Service | Java files | SQL files | Test suites |
| --- | ---: | ---: | ---: |
| billing | 38 | 6 | 13 |
| identity | 51 | 6 | 17 |
| operations | 58 | 16 | 20 |
| platform | 120 | 39 | 37 |
| school-core | 187 | 74 | 87 |

### Oversized modules

The largest maintainability hotspots include:

| Module | Approximate lines |
| --- | ---: |
| `StudentReadRepository` | 3,436 |
| `FeeReadRepository` | 1,964 |
| `HomePanel.tsx` | 1,365 |
| `PhotoImportRepository` | 1,362 |
| `StudentsPanel.tsx` | 1,344 |
| `TimetableRepository` | 1,308 |
| `AttendanceReadRepository` | 1,208 |
| `ReportingReadRepository` | 1,181 |
| `.github/workflows/build-release.yml` | 1,173 |
| `PhotoImportPanel.tsx` | 1,161 |
| `SchoolManagementPage.tsx` | 1,121 |
| Gateway `server.js` | 858 |

These should be decomposed by query responsibility, workflow, hook, component, and generated route
metadata. They do not justify another service split.

### Shared-code redundancy

Exact or near-exact infrastructure duplication exists across the services:

- `GcpOtlpTraceExporterAuthConfig` in five services;
- `ValidationExceptionHandler` in five services;
- `RequestCorrelationFilter` in five services;
- `TenantContext`, tenant filters, and tenant scopes in five services;
- tenant-aware datasource and datasource configuration in four services;
- event envelopes, publishers, and outbox configuration in three services; and
- runtime database-role guards in two services.

Recommended shared modules:

- `ims-observability-starter`;
- `ims-tenant-jdbc-starter`; and
- `ims-outbox-core`.

These modules must remain infrastructure-only. Domain authorization and the service-specific extensions
to tenant scope should remain in their owning service.

The services also duplicate Maven dependency and plugin configuration. Introduce a root Maven parent and
aggregator with Maven Enforcer rules for Java/Maven versions and dependency convergence. Do not update all
Spring-managed dependencies independently of the Spring Boot BOM.

## Dependency and frontend analysis

The runtime baseline is Spring Boot 4.1.1 with Java 25 for the Java services, and Node 24/npm 11 for the
gateway and frontend. The frontend is currently React 18.3.1 with React Router 7.18.2, Vite 6.4.3,
Vitest 3.2.7, and TypeScript 5.9.3.

Available major upgrades include React 19, Vite 8, Vitest 4, a TypeScript major version, Google auth
libraries, OpenPDF 3, and the Logstash encoder 9. These are modernization work, not immediate production
blockers. Each major change should be isolated from API and database migrations.

The production frontend build produced the following notable bundles:

- application CSS: approximately 211 KB;
- main JavaScript: approximately 245 KB, 83 KB gzip;
- SheetJS: approximately 500 KB, 163 KB gzip;
- ExcelJS: approximately 940 KB, 271 KB gzip; and
- `HomePanel`: approximately 110 KB.

Both spreadsheet libraries appear to serve distinct import/export workflows. Do not remove one until
format and feature parity is proven. Instead:

- lazy-load spreadsheet features;
- move parsing and workbook generation to Web Workers;
- split panels by route and workflow;
- reuse a common job-progress/error-summary component; and
- measure input latency and memory on representative operator devices.

## API structure and legacy-route analysis

The codebase contains approximately 446 controller mapping annotations, ten compatibility controllers,
and 83 compatibility mapping annotations. The gateway maintains a large hand-written route table, while
no authoritative OpenAPI or AsyncAPI contract was found.

The frontend still actively calls compatibility surfaces including fee structure, fee assignments,
supply orders, dashboards, and superadmin invoices. Removing these endpoints now would break the product.

The safe retirement process is:

1. define canonical OpenAPI contracts;
2. generate service clients and gateway route metadata;
3. instrument canonical and compatibility route usage;
4. migrate the frontend and operational clients;
5. add `Deprecation` and `Sunset` headers;
6. observe zero legacy traffic for an agreed period; and
7. remove aliases, compatibility controllers, and obsolete gateway prefixes.

The gateway's `nginx.conf.template` and `render-nginx.sh` are not part of its active Node container path,
but at least one observability audit still reads the template. Replace that audit dependency before
deleting the old Nginx assets.

## Live dashboard security analysis

The custom dashboard verifies OAuth/JWT signatures, issuer, audience, expiry, email, and a signed session
cookie. Its OAuth `state`, however, is functioning primarily as a destination rather than a unique,
per-login nonce stored and validated against the initiating browser.

Required hardening:

- generate a cryptographically random, short-lived state nonce;
- bind it to the initiating session and validate it exactly once;
- use PKCE;
- add upstream token/JWKS request timeouts and status checks;
- implement explicit logout/session invalidation; and
- add authentication regression tests.

The dashboard is read-only, which lowers impact, but this is still production authentication code.

## Database analysis

### Current physical and logical structure

Production already uses one physical Cloud SQL PostgreSQL 16 database. Its thirteen logical schemas are:

- `identity`;
- `tenant_school`;
- `student`;
- `attendance`;
- `fee`;
- `catalog`;
- `workflow`;
- `firefighting`;
- `reporting`;
- `notification`;
- `audit`;
- `billing`; and
- `public`.

The verified post-cutover inventory recorded 107 relations, 37 sequences, 339 indexes, 243 constraints,
66 RLS policies, 36 routines, and approximately 38,677 application rows. Repository migrations matched
production in the latest migration evidence, and the legacy public-schema monolith tables had already
been retired.

School-core intentionally owns the tenant, student, attendance, fee, and catalog schemas. Platform's
cross-schema reporting reads are also deliberate. A schema is a logical ownership boundary, not evidence
that a separate physical database is required.

### Row-level security

The separate `app_rt` runtime role and runtime-role guard are essential. PostgreSQL table owners and roles
with `BYPASSRLS` can bypass row-level policies, so production application traffic must never run as a
schema owner. See [PostgreSQL row security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html).

### Table consolidation decisions

| Structures | Decision | Migration requirement |
| --- | --- | --- |
| `billing.superadmin_invoices` and canonical billing invoices/items/payments/customers | Consolidate into canonical model | Preserve legacy ID, map school/customer, parse dates, reconcile quantity/rate/totals/status, shadow-read, then retire |
| `catalog.supply_orders` and `catalog.catalog_orders` | Consolidate | Backfill tenant/order fields, migrate compatibility API and frontend, then retire legacy table |
| `catalog.annual_plan_entries` and `catalog.annual_plan_items` | Consolidate | Backfill tenant/year model and migrate readers before retirement |
| Legacy father/mother/contact columns and guardians relationships | Normalize | Backfill, compare exports/API responses, migrate writers/readers, then deprecate columns |
| Attendance daily headers and student records | Keep | Intentional header/detail model |
| Fee payment records and billing payments | Keep separate | Different business domains: school fees versus platform SaaS billing |
| Reporting dimensions/facts and billing read models | Keep | Intentional projections; add repair and parity checks |
| Per-service outbox tables | Keep service-owned | Standardize envelope/schema and add processed-row retention |
| Notification logs and delivery-attempt tables | Clarify before changing | Similar names but distinct business, recipient, provider-attempt semantics |
| Student import/photo import/export records | Keep raw histories separate | Unify status/progress/percentage API, not raw workflow tables |

Every consolidation must use an idempotent backfill, row and value reconciliation, shadow or dual reads,
a retention period, rollback SQL, and separate approval before destructive DDL.

### Projections and data consistency

The latest ledger found four stale reporting student rows corresponding to old soft-deletes, not source
data loss. Add a scheduled projection reconciliation and repair process that can:

- detect missing, stale, and extra dimension rows;
- rebuild one tenant or entity safely;
- apply tombstones consistently; and
- expose reconciliation age and mismatch counts as metrics.

### Index analysis

The 339 indexes may be excessive for the current data volume, but migration DDL cannot prove an index is
unused. Gather at least 30 representative days of:

- Cloud SQL Query Insights;
- `pg_stat_statements`;
- `pg_stat_user_indexes`;
- index size and bloat;
- foreign-key coverage; and
- `EXPLAIN (ANALYZE, BUFFERS)` for high-cost queries.

Only remove an index when it is unused, duplicated, not constraint-supporting, and its removal is rehearsed.
References: [Cloud SQL Query Insights](https://docs.cloud.google.com/sql/docs/postgres/using-query-insights)
and [PostgreSQL `pg_stat_statements`](https://www.postgresql.org/docs/16/pgstatstatements.html).

### Partitioning

The attendance partitioning prototype passed its 25-million-row forward and rollback rehearsal, but
present production volume does not justify immediate partitioning. PostgreSQL recommends partitioning
primarily for genuinely large tables, with a common rule of thumb being that the table exceeds physical
memory. See [PostgreSQL partitioning](https://www.postgresql.org/docs/16/ddl-partitioning.html).

Keep the tested migration as a future threshold-triggered runbook rather than applying it prematurely.

## Live GCP analysis

### Projects and Cloud Run

The account could see `custoking-dev` and `custoking-prod`. The former `custoking` project was not visible;
dated documentation records it as `DELETE_REQUESTED` on 2026-08-24. Absence from the current account is
not, by itself, proof of completed deletion.

Production runs gateway, billing, dashboard, frontend, identity, operations, platform, and school-core on
Cloud Run. Minimum instances are zero. Maximum instances are three for gateway and two for the remaining
services, with concurrency 80. Gateway, frontend, and dashboard are public; the five Spring services
require IAM invocation.

This is appropriate for current traffic. Minimum instances trade cold-start latency for a baseline cost,
and maximum instances provide a traffic/cost cap. See
[Cloud Run minimum instances](https://docs.cloud.google.com/run/docs/configuring/min-instances) and
[Cloud Run service configuration](https://docs.cloud.google.com/run/docs/configuring).

Private services currently permit Cloud Run ingress `all` but remain IAM-private. Tightening ingress is a
valid hardening step only after internal service routing and deployment probes are tested.

### Cost profile

The six observed daily estimates were approximately US$1.22–US$1.37 per day. Cloud SQL contributed about
US$7 over the six-day period; all Cloud Run services combined contributed less than approximately US$0.60.

These are estimator results rather than invoice-grade exported usage. They nevertheless show that another
service merge would not materially reduce cost. The optimization focus should be Cloud SQL availability
and tier, billing accuracy, build churn, and log/metric volume.

### Cloud SQL production decision

Production Cloud SQL is PostgreSQL 16 on `db-g1-small`, Enterprise edition, zonal, with 10 GB SSD,
private IP, backups, PITR, deletion protection, seven-day logs, and Query Insights. It has no configured
maintenance window. Seven-day utilization was low: CPU about 6 percent, memory roughly 43–48 percent,
and disk use below 200 MB.

Do not downsize further. Google documents that shared-core tiers such as `db-g1-small` are not covered by
the Cloud SQL SLA. Google also recommends regional availability for production and zonal availability
primarily for development/testing:

- [Cloud SQL pricing and shared-core SLA note](https://cloud.google.com/sql/pricing)
- [Cloud SQL instance settings](https://docs.cloud.google.com/sql/docs/postgres/instance-settings)
- [Cloud SQL high availability](https://docs.cloud.google.com/sql/docs/postgres/high-availability)
- [Configure Cloud SQL HA](https://docs.cloud.google.com/sql/docs/postgres/configure-ha)
- [Cloud SQL disaster recovery](https://docs.cloud.google.com/sql/docs/postgres/intro-to-cloud-sql-disaster-recovery)

The production owner must choose between:

1. a small dedicated-core, regional HA configuration with a service availability objective; or
2. temporary acceptance of the current pilot risk with documented RTO/RPO and tested restore procedures.

### Billing export

The `billing_export` dataset contained pricing export but no `gcp_billing_export_v1_*` standard/detailed
usage tables. The application's current cost report is therefore an estimate, not an invoice-grade live
usage report.

Google configures standard usage, detailed usage, and pricing exports separately. Appropriate billing
account permissions are required, and switching datasets does not backfill earlier data:

- [Cloud Billing export setup](https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery-setup)
- [Standard usage export schema](https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery-tables/standard-usage)

The embedded job history records repeated generic failures while pricing export succeeded. The next action
requires a Billing Account Administrator/Costs Manager or Google Cloud support; the repository cannot
repair an account-level export configuration that is not exposed by the project APIs.

### Monitoring and alerting

Production and development each had twelve Monitoring dashboards. An exact audit evaluated 124 chart
filters representing 85 unique filters, and all 85 returned time-series data during the previous seven
days. The dashboards are wired.

The issue is operational complexity:

- 73 enabled production alert policies;
- 71 enabled development policies;
- two enabled production email channels; and
- overlapping uptime, 5xx, latency, burn-rate, saturation, and per-service alerts.

One concrete defect exists: the storage-growth alert watches the old bucket
`custoking-student-photos-prod`, while the live bucket is `custoking-prod-student-photos`. Correct the
Terraform default/explicit bucket list and automatically validate that every monitored resource exists
and has recent data.

Use four primary role-based views:

1. Live Operations;
2. Product and Usage;
3. Engineering and Infrastructure, including asynchronous health; and
4. Billing and Cost.

Service drill-down dashboards may remain, but alert policies should be retained only when they are
actionable and have a tested owner/escalation path.

### Scheduler and asynchronous processing

Production had four one-minute asynchronous relay jobs, a school-hours product-liveness job, an hourly
cost-metric job, and a daily cost-analysis job. Pub/Sub reporting and notification topics, push
subscriptions, inspection subscriptions, and DLQs existed in both environments. Current queues and DLQs
were healthy at the point of inspection.

Some current-state documentation still describes an earlier scheduler/subscription topology and should
be regenerated from live inventory rather than manually edited as an ever-growing narrative.

### Artifact Registry and storage

Artifact Registry contained approximately 117 production versions/3.59 GB and 129 development
versions/5.82 GB. Cleanup policies delete eligible artifacts older than seven days while retaining recent
versions. The policy was active, not dry-run.

Cleanup is asynchronous and keep policies override deletion, so recent build churn does not prove the
policy is broken. See
[Artifact Registry cleanup policies](https://docs.cloud.google.com/artifact-registry/docs/repositories/cleanup-policy-overview).

Production buckets were small except for the restored photo bucket, approximately 174 MiB at inspection.
That growth is expected after photo recovery/import work. Validate lifecycle, soft-delete, retention, and
reconciliation rules by bucket purpose rather than applying a blanket deletion policy.

### Network and IAM

Both projects use the auto-mode default VPC, creating unused global default subnets while only the target
region is used. This is governance and attack-surface debt, not a material present cost. Plan a custom-mode,
regional VPC migration; do not destructively alter the current network in place.

The inspected application jobs use dedicated service accounts, and no service-account owner/editor/viewer
binding was found. Continue with a permission-by-permission audit. Cloud Asset Inventory was disabled and
should be considered for generated inventory and drift reporting.

### Delivery governance

Cloud Deploy pipelines and canary targets are active. They should remain. The repository is public, but
the inspected main/dev branches had no branch protection or repository ruleset. The operating principal
had write rather than administrator authority, so applying protection requires a repository administrator.

Required checks should be confirmed from a fresh pull request before protection is applied, so incorrect
check names do not deadlock releases.

## Prioritized execution plan

### Phase 0 — Preserve the verified baseline

Estimated duration: 1–2 days.

- Generate machine-readable service, route, schema, dashboard, alert, and GCP inventories.
- Mark stale current-state narrative sections as historical.
- Preserve the passing test/build baseline.
- Run the database boundary audit in a configured PostgreSQL environment.
- Capture Query Insights, index, query, and storage baselines.
- Add the live resource/filter validation audit to CI or scheduled operations.

Acceptance gate: the inventory can be reproduced and the verification suite is green.

Rollback: documentation/inventory additions are reversible and must not mutate runtime resources.

### Phase 1 — Close production blockers

Estimated duration: 1–3 weeks, partly dependent on owner/admin/business authority.

- Enable and verify standard and detailed Cloud Billing export.
- Make and fund the Cloud SQL regional-HA/RTO/RPO decision.
- Add GitHub branch protections/rulesets and exact required checks.
- harden dashboard OAuth state, PKCE, timeouts, logout, and tests.
- Correct the stale photo-bucket alert.
- Complete a real notification-provider canary.
- Obtain the outstanding data-policy and named-school pilot approvals.
- Execute a representative full school-day observation.

Acceptance gate: billing freshness is visible, SQL risk is approved, repository protections are active,
OAuth regression tests pass, test incidents reach both channels, and no P0 gate lacks an owner.

Rollback: Cloud SQL changes require a rehearsed restore/failback plan; auth changes retain the prior
revision for immediate Cloud Run rollback.

### Phase 2 — Establish the shared engineering foundation

Estimated duration: 1–2 weeks.

- Introduce a root Maven parent/aggregator.
- Add Maven Enforcer and dependency-convergence rules.
- Extract the observability, tenant JDBC, and outbox infrastructure modules.
- Add architecture tests preventing forbidden cross-domain dependencies.
- Preserve service-specific tenant authorization extension points.

Acceptance gate: no API/database behavior change and all existing tests remain green.

Rollback: publish/version shared modules immutably and allow services to revert independently.

### Phase 3 — Normalize API contracts

Estimated duration: 2–4 weeks.

- Author OpenAPI contracts for all five services.
- Generate typed frontend clients and gateway route metadata.
- Add route ownership and compatibility-usage telemetry.
- Migrate frontend calls to canonical endpoints.
- Add deprecation/sunset response headers.
- Remove compatibility surfaces only after the agreed zero-traffic window.

Acceptance gate: contract tests pass, generated clients build, and removed aliases have zero measured use.

Rollback: retain aliases for at least one release after clients migrate.

### Phase 4 — Consolidate verified database redundancies

Estimated duration: 3–6 weeks, executed as separate migrations.

Recommended order:

1. legacy superadmin invoices into canonical billing;
2. supply orders into canonical catalog orders;
3. annual-plan entries into annual-plan items;
4. legacy parent/contact fields into guardians relationships;
5. reporting tombstone/reconciliation repair;
6. processed outbox retention; and
7. evidence-based index cleanup.

Acceptance gate for every migration: row counts, financial totals, dates, statuses, tenant ownership, and
API/export results reconcile exactly. A retention window expires before any destructive DDL.

Rollback: idempotent backfill, reverse mapping, retained source table, and prior application revision.

### Phase 5 — Decompose code and improve client performance

Estimated duration: 2–4 weeks.

- Split read repositories by aggregate/query responsibility.
- Split the large React panels by route and workflow.
- Lazy-load SheetJS and ExcelJS.
- Move workbook work to Web Workers.
- Replace the hand-written gateway route table with generated metadata.
- Add comprehensive tests for the live dashboard.
- Standardize import/export/photo-import progress and error contracts.

Acceptance gate: import/export formats, photo display, permissions, and APIs remain compatible; bundle and
interaction metrics improve measurably.

Rollback: preserve old route components and processing path behind short-lived feature flags where risk
justifies it.

### Phase 6 — Clean up GCP and observability

Estimated duration: 1–3 weeks.

- Consolidate dashboard navigation without immediately deleting drill-down dashboards.
- Rationalize alerts around gateway/user SLOs and actionable dependency symptoms.
- Test incident delivery and escalation ownership.
- Validate bucket lifecycle, retention, and recovery rules.
- Measure Artifact Registry churn before tightening development retention.
- Audit dedicated service-account permissions.
- Establish Cloud Asset Inventory-based drift reports.
- Design, cost, and rehearse a custom-mode regional VPC migration.

Acceptance gate: all enabled alerts have a tested action/owner, dashboard filters are automatically
validated, and infrastructure cleanup has a recorded rollback path.

### Phase 7 — Modernize dependencies

Estimated duration: 1–2 weeks.

- Upgrade React, Vite, Vitest, and TypeScript in isolated pull requests.
- Upgrade Google authentication dependencies separately.
- Upgrade OpenPDF and Logstash encoder with output/log compatibility tests.
- Keep Spring Boot BOM-managed updates within a tested Boot release.

Acceptance gate: tests, production build, contract tests, import/export fixtures, PDF output, and logging
parsers all pass.

Rollback: one major dependency family per pull request.

### Phase 8 — Production certification

Estimated duration: at least one representative school day plus controlled recovery exercises.

- Run arrival-rate performance tests.
- Exercise restore and point-in-time recovery.
- Exercise HA/failover if regional HA is selected.
- Exercise Pub/Sub retry and DLQ recovery.
- Reconcile billing export against invoice/console totals.
- Test import/export progress, cancellation, retries, and failure summaries.
- Validate student-photo recovery, profile rendering, ID-card rendering, and export/import behavior.
- Run the named-school canary with a rehearsed rollback.

Acceptance gate: all launch gates have named approvers and immutable evidence.

## Work explicitly deferred

- Do not merge the five domain services again.
- Do not create a separate physical database for every schema.
- Do not drop compatibility routes before measuring their consumers.
- Do not drop indexes based solely on DDL or naming.
- Do not partition attendance tables before threshold evidence exists.
- Do not remove a spreadsheet library until import/export parity is proven.
- Do not delete legacy tables before reconciliation, retention, and approval gates pass.
- Do not optimize Cloud Run for negligible savings while Cloud SQL dominates the cost/risk profile.
- Do not destructively replace the default VPC without a tested staged migration.

## Recommended program order

`production blockers → shared foundation → API contracts → staged database consolidation → code
decomposition → GCP/observability cleanup → dependency upgrades → production certification`

Parallel work is safe where ownership does not overlap:

- billing-export escalation can run alongside dashboard OAuth hardening;
- GitHub governance can run alongside Cloud SQL decision preparation;
- OpenAPI authoring can start while query/index evidence accumulates; and
- frontend decomposition can follow contract stabilization while database shadow reads run.

Database destructive steps, service contract removals, and production network/SQL changes must remain
serialized behind their acceptance gates.

## Primary external references

- [Cloud Billing export setup](https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery-setup)
- [Cloud Billing standard usage schema](https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery-tables/standard-usage)
- [Artifact Registry cleanup policies](https://docs.cloud.google.com/artifact-registry/docs/repositories/cleanup-policy-overview)
- [Cloud Run minimum instances](https://docs.cloud.google.com/run/docs/configuring/min-instances)
- [Cloud SQL Query Insights](https://docs.cloud.google.com/sql/docs/postgres/using-query-insights)
- [Cloud SQL high availability](https://docs.cloud.google.com/sql/docs/postgres/high-availability)
- [Cloud SQL instance settings](https://docs.cloud.google.com/sql/docs/postgres/instance-settings)
- [Cloud SQL pricing](https://cloud.google.com/sql/pricing)
- [Cloud SQL disaster recovery](https://docs.cloud.google.com/sql/docs/postgres/intro-to-cloud-sql-disaster-recovery)
- [PostgreSQL row security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- [PostgreSQL partitioning](https://www.postgresql.org/docs/16/ddl-partitioning.html)
- [PostgreSQL `pg_stat_statements`](https://www.postgresql.org/docs/16/pgstatstatements.html)

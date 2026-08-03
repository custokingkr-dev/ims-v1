# GCP Cost Optimization Plan - Production School Onboarding

Last updated: 2026-08-03  
Project inspected: `custoking`  
Region inspected: `asia-south2`  
Primary goal: reduce fixed monthly GCP cost while keeping production safe as more schools are onboarded.

## Executive Summary

The current architecture is already using several good cost-control choices: Cloud Run request-driven services, min instances effectively set to `0`, Direct VPC egress instead of a Serverless VPC Access connector, small Cloud SQL shared-core instances, short Cloud Logging retention, Artifact Registry cleanup policies, and lifecycle rules on build/source buckets.

The biggest remaining cost risk is not raw traffic from new schools. It is fixed baseline spend and premature "always on" infrastructure:

1. Cloud SQL is the main unavoidable fixed component. Prod is `db-g1-small`, zonal, 10 GB PD-SSD, backups enabled. This is intentionally small; do not scale it up until metrics force it.
2. Cloud Run min instances are currently `0`. Keep that default. Turning on `min-instances=1` across the gateway plus five Java services would create continuous idle spend.
3. The current outbox relays are `@Scheduled` pollers inside request-based Cloud Run services. With min instances `0`, those pollers only run while a service is awake. This is a product-latency/correctness tradeoff, not just a cost setting.
4. Dev and prod are both deployed in the same project. Dev Cloud SQL is small, but still `activationPolicy=ALWAYS`. Dev should become schedulable/off-hours rather than always running.
5. Cost attribution by school is not available from GCP billing alone because schools share the same services, database, buckets, and topics. Per-school unit economics must be added at the application metrics layer.

Recommended direction:

- Keep production Cloud Run `min-instances=0` by default.
- Do not buy committed use discounts yet; collect 30-60 days of production spend and utilization after onboarding.
- Replace always-on outbox polling pressure with scheduled Cloud Run Jobs or another request-triggered relay pattern before deciding to spend on hot Java instances.
- Add cost guardrails now: budgets, billing export, labels, and a weekly cost review query.
- Right-size only after load tests and Cloud Monitoring evidence.

## Verified Current State

This section is based on live `gcloud.cmd` checks on 2026-08-03 plus repo files.

### Runtime Topology

```text
Browser
  -> custoking-frontend-<env>       Cloud Run, public
  -> custoking-api-gateway-<env>    Cloud Run, public
  -> domain services                Cloud Run, private IAM
  -> Cloud SQL PostgreSQL
  -> Pub/Sub push to platform-service
  -> Cloud Storage buckets
```

Live Cloud Run services:

| Env | Services |
| --- | --- |
| dev | frontend, api-gateway, identity, school-core, operations, platform, billing |
| prod | frontend, api-gateway, identity, school-core, operations, platform, billing |

Domain consolidation has already happened:

| Runtime service | Consolidated domains |
| --- | --- |
| `school-core-service` | tenant school, student, attendance, fee, catalog |
| `operations-service` | workflow, firefighting |
| `platform-service` | reporting, notification, audit |
| `identity-service` | identity/RBAC |
| `billing-service` | superadmin billing |

### Cloud Run

Current live shape:

| Service class | CPU | Memory | Concurrency | Prod max | Min |
| --- | ---: | ---: | ---: | ---: | ---: |
| frontend | 1 vCPU | 512 MiB | 80 | 2 | unset = 0 |
| api-gateway | 1 vCPU | 512 MiB | 80 | 3 | unset = 0 |
| Java domain services | 1 vCPU | 768 MiB | 80 | 2 | unset = 0 |

Other relevant facts:

- Startup CPU boost is enabled everywhere.
- Java services use Direct VPC egress with `private-ranges-only`.
- Gateway and frontend do not attach to the VPC.
- `cloudbuild.yaml` defaults `_DOMAIN_MIN_INSTANCES=0` and `_GATEWAY_MIN_INSTANCES=0`.
- GitHub `prod` Environment currently has no `CLOUD_RUN_DOMAIN_MIN_INSTANCES` or `CLOUD_RUN_GATEWAY_MIN_INSTANCES`, so future prod deploys keep min at `0`.

Important drift:

- `docs/current-state/gcp-infrastructure.md` still claims several services have min instances `1`. That is stale. Live Cloud Run and deployment source both show `0`.

### Cloud SQL

| Instance | Env | Tier | Version | Region/zone | Storage | Backup | HA |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `custoking-db-dev` | dev | `db-f1-micro` | PostgreSQL 16 | `asia-south2-b` | 10 GB PD-SSD | disabled | zonal |
| `custoking-db-prod` | prod | `db-g1-small` | PostgreSQL 16 | `asia-south2-c` | 10 GB PD-SSD | enabled, 7 retained | zonal |

Prod has:

- private IP only: `10.92.0.5`
- `max_connections=200`
- storage auto-resize enabled
- deletion protection enabled
- no overprovisioned Cloud SQL recommender result at inspection time

The existing repo threshold doc says prod table volumes were tiny on 2026-07-04 and additional partitioning/indexing was deferred until real thresholds are reached.

### Pub/Sub

Topics:

- `ims-reporting-events-v1-dev`
- `ims-reporting-events-v1-prod`
- `ims-notifications-events-v1-dev`
- `ims-notifications-events-v1-prod`

Subscriptions:

- push subscriptions to `platform-service`
- OIDC configured with the default compute service account
- retention 7 days
- ack deadline 30 seconds

Pub/Sub itself should not be a near-term cost driver. The operational issue is the publisher side: school-core, operations, and billing publish by polling local outbox tables on scheduled background threads.

### Storage

Live bucket sizes at inspection time:

| Bucket | Size | Lifecycle |
| --- | ---: | --- |
| `custoking-student-photos-prod` | ~1.5 MB | none |
| `custoking-student-photos-dev` | ~0.37 MB | none |
| `custoking-db-snapshots` | ~0.27 MB | delete after 30 days |
| `custoking-github-deploy-source` | ~106 MB | delete after 14 days |
| `custoking_cloudbuild` | ~17.8 MB | delete after 14 days |
| `custoking-terraform-state` | ~0.73 MB | versioning enabled |

The student-photo buckets are tiny today, but they are the storage surface most likely to grow with school onboarding.

### Artifact Registry

Repository:

```text
asia-south2-docker.pkg.dev/custoking/custoking
```

Current size: ~8.1 GB.

Cleanup policies are applied:

- delete images older than 7 days
- keep last 3 versions per service

This is not a major cost driver now, but it should stay monitored because image churn can grow quickly during active development.

### Observability

Cloud Logging buckets:

| Bucket | Retention |
| --- | ---: |
| `_Default` | 7 days |
| `_Required` | 400 days, locked |

OTel:

- traces exporter enabled
- logs and metrics exporters disabled
- live services showed `OTEL_TRACES_SAMPLER_ARG=0.05`

The 7-day default log retention is already cost-aware.

## Research Findings Applied To This Project

### Cloud Run

Google's cost guidance for Cloud Run is directly relevant here:

- Higher concurrency can reduce costs because fewer instances can serve the same traffic, assuming the app handles concurrent work safely.
- Co-locating Cloud Run, Cloud SQL, and Cloud Storage in the same region avoids avoidable transfer cost.
- Direct VPC egress can scale to zero and avoids the baseline compute overhead of Serverless VPC Access connectors.
- Cloud CDN can reduce cost for highly cacheable/static assets, but the fixed load-balancer architecture must be justified by real traffic.

Minimum instances are the most dangerous cost lever. Google documents that with request-based billing, min instances are billed at an idle rate, and when min is `0`, idle instances are not billed. It also recommends aligning min instances with actual typical traffic rather than overprovisioning.

Practitioner experience on Cloud Run lines up with that: the common pattern is to keep min instances at `0` unless cold starts are a hard product requirement, and to first verify request-based billing and idle CPU allocation before assuming Cloud Run is expensive.

### Java On Cloud Run

Google's Java guidance matters because this project runs Spring Boot services:

- Cloud Run sends a real user request to a cold instance, so slow startup is user-visible.
- Java startup work should be reduced or parallelized.
- Startup probes prevent requests being sent before the app is ready.
- Background timers and message receivers are not reliable under request-based billing when no requests are active.
- Connection counts must be calculated as `max instances * pool size`, and Cloud Run max instances should stay below database connection capacity.

This project already uses startup CPU boost, startup probes, Hikari `minimum-idle=0`, and low max instances. The remaining Java-specific work is to reduce startup latency without switching the entire platform to always-on.

### Cloud SQL

Cloud SQL is charged while the instance is on; storage is charged whether the instance is on or off, and backups are charged at the instance storage rate. That means stopping dev can save compute, but not storage. For prod, stopping is not acceptable.

Google's Cloud SQL CUDs are useful for predictable regional usage over one or three years, but shared-core instances are already small and the project is still in onboarding mode. A CUD decision should wait until the steady-state shape is known.

Cloud SQL recommenders are useful as an ongoing guardrail. At inspection time there were no overprovisioned recommendations for `asia-south2`.

### Pub/Sub And Background Work

Pub/Sub push to Cloud Run is a good fit for waking a consumer on demand. The project already does this for platform-service consumers.

The weaker part is publisher-side outbox polling. Timers inside request-based Cloud Run services may not run when there are no active requests. The repo comments already acknowledge that outbox relays only poll while the service is awake when domain min instances are `0`.

Therefore, the cost-optimized answer is not "turn on min instances everywhere." It is to make outbox publishing request-triggered or scheduled as a short-lived job.

## Cost Strategy

### Principle 1 - Keep Fixed Spend Low

Do not add fixed spend unless it buys a clearly measured product outcome. For this project:

- Default `min-instances=0`.
- Keep Cloud SQL prod on `db-g1-small` until metrics prove it is too small.
- Keep prod zonal for now unless the business explicitly buys higher availability.
- Stop or schedule dev resources outside active use.
- Avoid new always-on middleware such as PgBouncer VMs, Redis, GKE, or a VPC connector until thresholds are hit.

### Principle 2 - Use Metrics Before Upgrades

Before changing Cloud Run CPU/memory or Cloud SQL tier, collect:

- Cloud Run p50/p95/p99 latency by service.
- cold-start frequency and startup duration.
- Cloud Run CPU and memory utilization under peak school-day traffic.
- Cloud SQL CPU, memory, storage growth, connections, lock waits, slow queries.
- Pub/Sub backlog and oldest unacked message age.
- outbox pending count and oldest pending age.

### Principle 3 - Optimize The Architecture Before Paying For Warmth

If schools experience cold starts or delayed dashboard projections, first reduce startup and decouple background relays. Only then choose selective min instances.

### Principle 4 - Attribute Cost Per School Outside GCP Billing

GCP billing can tell us service and SKU cost. It cannot split shared Cloud Run/Cloud SQL spend by school. Add app-level usage counters:

- requests per school and endpoint group
- rows created per school by domain
- student photo object count and bytes per school
- Pub/Sub events per school
- notification sends per school/provider/channel
- storage bytes per school/month

Use those counters to compute per-school unit economics from the monthly GCP bill.

## Prioritized Plan

### P0 - This Week

#### 1. Fix Documentation Drift

Update stale current-state docs to reflect:

- Cloud Run min instances are currently `0`.
- Runtime `OTEL_TRACES_SAMPLER_ARG` is currently `0.05` in live services unless workflow vars override it.
- Artifact Registry cleanup policies are applied.

Why:

- New-school onboarding decisions should not be based on stale min-instance assumptions.

#### 2. Add Billing Guardrails

Create:

- a monthly project budget
- alert thresholds at 50%, 75%, 90%, 100%
- a forecasted threshold alert
- a separate budget filtered to Cloud SQL if the billing account allows it
- a budget notification channel that reaches engineering and business owners

Budget alerts do not cap spend by themselves; they only notify. Treat them as escalation signals.

#### 3. Enable Billing Export

Enable detailed Cloud Billing export to BigQuery in a billing/admin project or a dedicated dataset.

Minimum useful queries:

```sql
-- Daily cost by service description/SKU.
SELECT
  DATE(usage_start_time) AS day,
  service.description AS service,
  sku.description AS sku,
  SUM(cost) AS cost,
  SUM(IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0)) AS credits
FROM `BILLING_DATASET.gcp_billing_export_resource_v1_*`
WHERE project.id = 'custoking'
GROUP BY day, service, sku
ORDER BY day DESC, cost DESC;
```

```sql
-- Month-to-date cost by resource label, once labels are added.
SELECT
  invoice.month,
  project.id,
  (SELECT value FROM UNNEST(labels) WHERE key = 'env') AS env,
  service.description AS service,
  SUM(cost) AS gross_cost
FROM `BILLING_DATASET.gcp_billing_export_resource_v1_*`
WHERE project.id = 'custoking'
GROUP BY invoice.month, project.id, env, service
ORDER BY invoice.month DESC, gross_cost DESC;
```

#### 4. Add Resource Labels

Apply labels consistently:

| Label | Example |
| --- | --- |
| `app` | `custoking-ims` |
| `env` | `prod`, `dev` |
| `component` | `cloud-run`, `cloud-sql`, `storage`, `pubsub` |
| `service` | `school-core`, `api-gateway` |
| `owner` | `engineering` |
| `cost-center` | `school-saas` |

Add labels to:

- Cloud Run services
- Cloud SQL instances
- buckets
- Artifact Registry repository
- Pub/Sub topics/subscriptions where supported

#### 5. Keep Prod Min Instances At Zero

Current verified setting is already cost-minimal.

Do not set these GitHub Environment variables unless a measured product requirement justifies it:

- `CLOUD_RUN_DOMAIN_MIN_INSTANCES`
- `CLOUD_RUN_GATEWAY_MIN_INSTANCES`

If cold starts hurt demos or school-day usage, use service-level min instances only for the narrow window and service that needs it. Do not apply min instances fleet-wide.

### P1 - Before Onboarding The Next Batch Of Schools

#### 6. Replace Always-On Outbox Pressure

Current issue:

- `school-core-service`, `operations-service`, and `billing-service` use scheduled outbox relays every 10 seconds.
- With request-based Cloud Run and min `0`, these scheduled relays may not run while no request is active.
- Setting min `1` for all outbox-producing services would solve latency but adds fixed idle cost.

Preferred cost-optimized design:

1. Keep outbox writes transactional in the domain service.
2. Add a Cloud Run Job or small relay service that wakes on schedule.
3. The relay polls unpublished outbox rows and publishes to Pub/Sub.
4. Schedule every 1-5 minutes during school operating hours and every 15-30 minutes off-hours.
5. Keep service min instances at `0`.

Candidate shapes:

| Option | Cost | Latency | Operational risk | Recommendation |
| --- | --- | --- | --- | --- |
| Current poller, min `0` | lowest | unpredictable when idle | medium | acceptable only for low urgency |
| Current poller, min `1` on 3 services | high fixed spend | near real-time | low | use only if business buys latency |
| Cloud Scheduler -> Cloud Run Job relay | low | scheduled latency | medium | preferred next architecture |
| Publish directly after DB commit | low | immediate | medium-high | possible, but weakens outbox semantics if not designed carefully |
| Worker pool / always-on worker | high fixed spend | near real-time | medium | defer |

Implementation note:

- A single relay image can poll the three outbox tables if it uses the existing Pub/Sub envelope contract and `appuser`/`app_rt` permissions are correct.
- Keep idempotency by using the outbox row id/event id.
- Add a lock/lease mechanism or `FOR UPDATE SKIP LOCKED` so overlapping jobs cannot double-publish.

#### 7. Build A Cold-Start Scorecard

Add a weekly report:

- requests that triggered new instance startup
- startup latency per service
- p95 user request latency after idle gaps
- service-specific cold-start impact during school hours

Decision rule:

- If only gateway cold starts hurt, consider `api-gateway` min `1` during school hours.
- If Java cold starts hurt only after login, optimize identity and school-core startup first.
- If school-core remains bad after startup work, consider school-core min `1` during school hours only.
- Do not warm billing/platform/operations unless their user-visible paths prove it.

#### 8. Java Startup And Memory Right-Sizing

Current Java services are 1 vCPU / 768 MiB with `MaxRAMPercentage=75`.

Test changes in dev under a realistic seed:

- `JAVA_OPTS=-XX:TieredStopAtLevel=1`
- Spring lazy initialization for non-critical beans only, if it does not hide startup failures
- reduce unnecessary classpath scanning
- keep startup probes
- measure actual RSS/memory utilization
- evaluate whether any service can move from 768 MiB to 640 MiB or 512 MiB without OOM risk

Do not blindly lower CPU below 1 vCPU. Cloud Run sub-1-vCPU settings carry constraints and can reduce concurrency; for Java/Spring the latency hit may outweigh active-time savings.

#### 9. Cloud SQL Connection And Pool Policy

Current worst-case application pool headroom is acceptable:

```text
5 Java services * max 2 prod instances * Hikari max 5 ~= 50 app connections
Cloud SQL max_connections = 200
```

Policy:

- Keep `DB_POOL_MAX=5`, `DB_POOL_MIN=0`.
- Keep prod `max-instances=2` on Java services until metrics require more.
- If raising max instances, recalculate connection ceiling first.
- Introduce PgBouncer only near the documented threshold, and only after converting RLS GUC handling to transaction-local semantics.

#### 10. Dev Environment Scheduling

Dev Cloud SQL is `db-f1-micro` but still always on. Add a controlled schedule:

- stop dev Cloud SQL outside active hours
- start before the expected development window
- pause dev Cloud Run smoke/deploy jobs while DB is stopped or make the workflow start DB first

Do not stop prod.

Expected effect:

- saves dev Cloud SQL compute hours
- storage cost remains
- avoids changing prod architecture

#### 11. Student Photo Storage Policy

Before real photo imports scale:

- define object prefixes: `original/`, `normalized/`, `thumbnail/`, `import-staging/`
- add lifecycle delete for `import-staging/` after 7-14 days
- keep normalized current photo objects in Standard storage
- create thumbnails to reduce repeated full-image reads
- track bytes per school in the app database

Do not add lifecycle deletion to current production photos without a product retention policy.

### P2 - After 30-60 Days Of Production Data

#### 12. Cloud SQL Steady-State Decision

Every month, inspect:

- CPU p95 and max
- memory pressure
- connections
- storage growth
- slow query patterns
- backup storage growth

Decision rules:

- Stay on `db-g1-small` if CPU/memory are healthy and latency is acceptable.
- Move up only when sustained metrics require it.
- Consider a one-year Cloud SQL CUD only after production usage is stable and predictable.
- Do not enable HA until the business accepts roughly higher fixed database cost for higher availability.

#### 13. Cloud Run Resource Right-Sizing

For each service, compare:

- memory utilization p95 vs configured memory
- CPU utilization p95 vs configured CPU
- p95 latency
- OOM/restart count
- max-instance saturation

Candidate outcomes:

- frontend: explicitly set lower memory if nginx SPA is stable.
- gateway: lower memory only if Node/nginx process stays low under auth load.
- platform-service: may not need same memory as school-core if reporting projections are light.
- billing-service: likely low traffic; keep max instances low and min `0`.

#### 14. Frontend Hosting Decision

Current frontend on Cloud Run is acceptable at low scale. Revisit when:

- static asset egress grows
- frontend requests dominate Cloud Run request count
- cold starts on frontend are visible

Options:

| Option | Pros | Cons |
| --- | --- | --- |
| Keep Cloud Run frontend | simple, existing deploy path | pays Cloud Run per active request |
| Cloud Storage static hosting + CDN/LB | static-asset optimized | load balancer/CDN complexity and possible fixed cost |
| Firebase Hosting | simple static hosting/CDN path | new product surface and deploy path |

Do not move this before measuring traffic; premature CDN/load-balancer setup can cost more than it saves at small scale.

#### 15. Committed Use Discounts

Only consider CUDs after:

- billing export has at least 30-60 days of clean data
- school onboarding rate is known
- dev scheduling is implemented
- Cloud SQL tier decision is stable
- Cloud Run min-instance policy is stable

Likely first candidate:

- Cloud SQL production baseline, if it remains always on and predictable.

Cloud Run CUDs are less urgent while services run request-based with min `0`.

## Cost Guardrail Checklist

Before each school onboarding wave:

- [ ] Check month-to-date GCP cost vs budget.
- [ ] Check Cloud SQL CPU/memory/connections for last 7 and 30 days.
- [ ] Check Cloud Run p95 latency and cold-start rate for gateway, identity, school-core.
- [ ] Check max-instance saturation.
- [ ] Check outbox pending count and oldest pending age.
- [ ] Check Pub/Sub oldest unacked message age.
- [ ] Check student photo bucket growth.
- [ ] Check Artifact Registry size.
- [ ] Check logs volume/retention.
- [ ] Recalculate expected per-school gross margin.

## Target Architecture For Low-Cost Scale

```text
Browser
  -> public Cloud Run gateway/frontend, min 0
  -> private Cloud Run domain APIs, min 0 by default
  -> Cloud SQL prod, right-sized, zonal unless HA is purchased
  -> transactional outbox tables
  -> scheduled Cloud Run Job relay, short-lived
  -> Pub/Sub push to platform-service, wakes on demand
  -> Cloud Storage for photos with prefix lifecycle for temporary objects
  -> billing export + labels + app-level school usage counters
```

This keeps the expensive baseline to Cloud SQL and avoids paying continuously for Java containers just to run background polling.

## Decisions To Make Explicit

| Decision | Default recommendation | When to change |
| --- | --- | --- |
| Prod Cloud Run min instances | `0` | Only if measured cold starts hurt school operations |
| Gateway min instance | `0` | Possibly `1` during school hours if login/navigation p95 is bad |
| Domain min instance | `0` | Only for school-core if measured, not fleet-wide |
| Outbox relay | Move to scheduled job | Use min `1` only if near-real-time is required and paid for |
| Prod Cloud SQL tier | stay `db-g1-small` | Upgrade on CPU/memory/latency evidence |
| Prod Cloud SQL HA | off/zonal | Enable only with paid availability requirement |
| Dev Cloud SQL | scheduled stop/start | Keep always-on only during active delivery periods |
| CUDs | wait | Buy after 30-60 days of stable baseline |
| Static frontend move | wait | Move when static traffic cost is material |

## Follow-Up Work Items

1. Patch stale docs:
   - `docs/current-state/gcp-infrastructure.md`
   - `docs/current-state/deployment-cicd.md`
2. Use the cost guardrails runbook: `docs/GCP-COST-GUARDRAILS-RUNBOOK.md`.
3. Add labels in `cloudbuild.yaml` deploy commands and remaining non-Cloud-Run GCP resources.
4. Design and implement scheduled outbox relay job.
5. Add per-school usage counters.
6. Add weekly cost review SQL and dashboard.
7. Add dev Cloud SQL start/stop automation.
8. Run Java service cold-start/right-size experiments in dev.

## Source Notes

Primary Google sources:

- Cloud Run cost optimization: https://docs.cloud.google.com/run/docs/tips/services-cost-optimization
- Cloud Run pricing: https://cloud.google.com/run/pricing
- Cloud Run minimum instances: https://docs.cloud.google.com/run/docs/configuring/min-instances
- Cloud Run billing settings: https://docs.cloud.google.com/run/docs/configuring/billing-settings
- Cloud Run Java optimization: https://docs.cloud.google.com/run/docs/tips/java
- Startup CPU boost: https://cloud.google.com/blog/products/serverless/announcing-startup-cpu-boost-for-cloud-run--cloud-functions
- Cloud Run with Pub/Sub push: https://docs.cloud.google.com/run/docs/tutorials/pubsub
- Cloud SQL pricing: https://cloud.google.com/sql/pricing
- Cloud SQL CUDs: https://docs.cloud.google.com/sql/cud
- Cloud SQL FAQ: https://docs.cloud.google.com/sql/docs/postgres/faq
- Cloud SQL overprovisioned recommender: https://docs.cloud.google.com/sql/docs/mysql/recommender-sql-overprovisioned
- Cloud Billing export: https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery
- Cloud Billing budgets: https://docs.cloud.google.com/billing/docs/how-to/budgets
- Cloud Logging pricing: https://cloud.google.com/products/observability/pricing
- Artifact Registry cleanup policies: https://docs.cloud.google.com/artifact-registry/docs/repositories/cleanup-policy-overview
- Cloud Storage lifecycle management: https://docs.cloud.google.com/storage/docs/lifecycle

Practitioner/community sources used as directional input, not as authoritative product documentation:

- Ahmet Alp Balkan's community Cloud Run FAQ: https://github.com/ahmetb/cloud-run-faq
- Reddit discussion on Cloud Run request-based billing/min instances: https://www.reddit.com/r/googlecloud/comments/1tbhlgu/gcp_cloud_run_simple_api_instance_costs/
- Reddit discussion on Cloud Run cold-start tradeoffs: https://www.reddit.com/r/googlecloud/comments/nq2811/cloud_run_reducing_cold_start_latency_options/
- OneUptime Java/Spring Cloud Run cold-start article: https://oneuptime.com/blog/post/2026-02-17-how-to-optimize-cloud-run-cold-start-latency-for-java-and-spring-boot-applications/view

# GCP Cost Optimization Plan - Production School Onboarding

> **Scale-target override (2026-08-10):** This document describes the low-traffic baseline that
> existed before the 100-150 school / 200,000-300,000 student target was defined. For that target,
> [SCALE-READINESS-AND-COST-PLAN-2026-08-10.md](SCALE-READINESS-AND-COST-PLAN-2026-08-10.md)
> supersedes every recommendation below to retain production on `db-g1-small`. The shared-core
> instance remains the observed current state, not the production target.

> **Live-state reconciliation (2026-08-12):** dev SQL is stopped on `db-f1-micro`; all four dev relay
> Scheduler jobs are paused; all 14 Cloud Run services have minimum scale 0; production SQL remains
> zonal `db-g1-small`; Artifact Registry is approximately 6.13 GB after cleanup; and the standard billing
> export is INR 5,558.30 gross through 2026-08-11 19:00 UTC. Use
> [REMAINING-WORK-2026-08-12.md](REMAINING-WORK-2026-08-12.md) for current decisions and gates. Older
> point-in-time sizes and forecasts below are retained as historical evidence.

> **Cost-control verification (2026-08-14):** governed workflow run `31795809595` succeeded from `main`;
> direct inspection then showed `custoking-db-dev` `STOPPED` on `db-f1-micro` with activation policy `NEVER`.
> The database is start/stop controlled and must be rechecked after every approved dev window; this dated
> result is not a promise that it remains stopped indefinitely. An earlier dispatch from `dev` failed WIF
> authentication because the cost-controller provider condition does not authorize that ref; manual
> dispatches must use the workflow's governed `main` ref unless IAM policy is deliberately changed.

> **Artifact Registry egress regression identified and fixed (2026-08-16):** the "not a major cost
> driver" assessment below is superseded. Commit `6d9da9b2` (2026-08-11) added fourteen exact-digest
> Trivy steps to `.github/workflows/build-release.yml`. Every release resolves all seven service
> images, so each run pulled all seven from `asia-south2` to a GitHub-hosted runner outside Google
> Cloud, which is billed internet egress. Artifact Registry egress was exactly INR 0 before that
> commit and INR 628.93 / 58.84 GB after it. On 2026-08-13/14 alone, 24 releases pulled 168 images
> for 48.46 GB (INR 518), of which only 62 digests were new. A digest-keyed verdict cache in
> `gs://custoking-scan-evidence` with a 24-hour TTL now skips the pull when the same immutable digest
> already passed the same scanner revision. See the Artifact Registry section below for the measured
> detail and the residual risk.

Last updated: 2026-08-05
Project inspected: `custoking`  
Region inspected: `asia-south2`  
Primary goal: reduce fixed monthly GCP cost while keeping production safe as more schools are onboarded.

## Executive Summary

### Measured Billing Baseline - 2026-08-05

The detailed billing export covers 2026-07-05 through partial 2026-08-05. Gross usage was
`INR 17,113.65`; temporary Free Trial credits and ordinary Cloud Run discounts reduced the
exported net cost to approximately zero. Gross cost, not the promotional-credit net, is the
operating baseline.

| Service | Gross cost |
| --- | ---: |
| Cloud Run | INR 10,572.67 |
| Cloud SQL | INR 4,193.46 |
| Cloud Build | INR 1,059.72 |
| Networking | INR 735.93 |
| Artifact Registry | INR 336.36 |
| Secret Manager | INR 203.55 |
| Other storage/transfer | INR 12.00 |

Two historical charges no longer represent the deployed baseline: July included INR 7,847 of
Cloud Run minimum-instance CPU/memory and INR 736 of Cloud NAT. Cloud Run now has zero minimum
instances and the NAT path has been removed.

The active avoidable runtime issue was an authenticated browser repeatedly requesting
`/api/v1/dashboard/command-center`, producing matching frontend, gateway, and platform request
counts. The frontend now single-flights and caches that request per authenticated session,
refreshes at most once per minute while visible, and pauses refreshes in hidden tabs.

Expected post-fix monthly gross cost is INR 5,100-6,000 with dev SQL always running, or
INR 4,500-5,500 with the weekday dev schedule. These are planning ranges, not invoice guarantees.

The current architecture is already using several good cost-control choices: Cloud Run request-driven services, min instances effectively set to `0`, Direct VPC egress instead of a Serverless VPC Access connector, small Cloud SQL shared-core instances, short Cloud Logging retention, Artifact Registry cleanup policies, and lifecycle rules on build/source buckets.

The biggest remaining cost risk is not raw traffic from new schools. It is fixed baseline spend and premature "always on" infrastructure:

1. Cloud SQL is the main unavoidable fixed component. Prod is `db-g1-small`, zonal, 10 GB PD-SSD, backups enabled. This is intentionally small; do not scale it up until metrics force it.
2. Cloud Run min instances are currently `0`. Keep that default. Turning on `min-instances=1` across the gateway plus five Java services would create continuous idle spend.
3. The current outbox relays are `@Scheduled` pollers inside request-based Cloud Run services. With min instances `0`, those pollers only run while a service is awake. This is a product-latency/correctness tradeoff, not just a cost setting.
4. Dev and prod are both deployed in the same project. Dev Cloud SQL is scheduled to start at 08:00 IST on weekdays and stop at 20:00 IST every day; deployments also start and wait for it when required. Production remains continuously available.
5. Cost attribution by school is not available from GCP billing alone because schools share the same services, database, buckets, and topics. Per-school unit economics must be added at the application metrics layer.

Recommended direction:

- Keep production Cloud Run `min-instances=0` by default.
- Do not buy committed use discounts yet; collect 30-60 days of production spend and utilization after onboarding.
- Replace always-on outbox polling pressure with scheduled Cloud Run Jobs or another request-triggered relay pattern before deciding to spend on hot Java instances.
- Add cost guardrails now: budgets, billing export, labels, and a weekly cost review query.
- Right-size only after load tests and Cloud Monitoring evidence.

## Verified Current State

This section is based on live `gcloud.cmd` checks on 2026-08-05 plus repo files. The cost-control application release is `rel-prod-58061a5e8e23-1`; the repository and both environment branches include the subsequent rollout-waiter fix at commit `96fb1eb71209dd8b21009489493b2811069217ff`.

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
| identity, operations, platform, billing | 1 vCPU | 768 MiB | 80 | 2 | unset = 0 |
| school-core | 1 vCPU | 2 GiB | 80 | 2 | unset = 0 |

Other relevant facts:

- Startup CPU boost is enabled everywhere.
- Java services use Direct VPC egress with `private-ranges-only`.
- Gateway and frontend do not attach to the VPC.
- Cloud Deploy target parameters currently set `domain_min_instances="0"` and `gateway_min_instances="0"`.
- Cloud Run manifests consume those parameters through Skaffold/Cloud Deploy.

Important drift: none identified for Cloud Run min instances in current-state docs after the 2026-08-04 documentation update. Live Cloud Run and deployment source both show `0`.

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

Live bucket sizes at the 2026-08-05 inspection:

| Bucket | Size | Lifecycle |
| --- | ---: | --- |
| `custoking-student-photos-prod` | ~2.34 GB | delete isolated temporary photo-import sources after 14 days |
| `custoking-student-photos-dev` | ~10.3 MB | delete isolated temporary photo-import sources after 14 days |
| `custoking-db-snapshots` | ~0.27 MB | delete after 30 days |
| `custoking-github-deploy-source` | ~106 MB | delete after 14 days |
| `custoking_cloudbuild` | ~17.8 MB | delete after 14 days |
| `custoking-terraform-state` | ~0.73 MB | versioning enabled |

Production contained 729 temporary source objects using ~2.31 GB and 693 normalized student
photos using ~29.1 MB. Permanent photos use `schools/<school>/students/<id>/photos/`. New temporary
photo-import sources use `temporary/photo-imports/`; exact legacy `student-imports/photo-import-*`
batch prefixes are included during migration. Lifecycle rules cannot match the permanent path.

### Artifact Registry

Repository:

```text
asia-south2-docker.pkg.dev/custoking/custoking
```

Current size: ~38.6 GB before the cleanup policy's next background application.

Cleanup policies are applied:

- delete images older than 7 days
- keep last 3 versions per service

Stored bytes are not a major cost driver. Egress became one on 2026-08-11 and is the larger risk.

#### Scan-driven egress (measured 2026-08-16)

Pulling an image into Cloud Run in the same region is free. Pulling it to a GitHub-hosted runner is
billed as internet egress, and the release gate does exactly that.

| Day | Egress | Cost | Note |
| --- | ---: | ---: | --- |
| through 2026-08-10 | 0.00 GB | INR 0.00 | no exact-digest scanning yet |
| 2026-08-11 | 10.36 GB | INR 110.77 | `6d9da9b2` adds fourteen Trivy steps |
| 2026-08-12 | 0.00 GB | INR 0.00 | no releases reached the release job |
| 2026-08-13 | 30.23 GB | INR 323.19 | dependabot and codex merge batch |
| 2026-08-14 | 18.23 GB | INR 194.85 | continued merges plus the prod release |

Attribution for 2026-08-13/14: 45 `CD / Deploy branch environment` runs, of which 24 reached the
release job. The `images` step resolves every service to an immutable digest whether or not it was
rebuilt, so each of those 24 runs pulled exactly seven images. 168 pulls against 48.46 GB implies
about 288 MB per pull, which matches the Spring Boot images. Only 62 new tagged digests were built in
that window, so roughly 106 of the 168 pulls re-scanned a digest that already had a verdict.

The paired SARIF step is not a second pull. Trivy's layer cache serves it, evidenced by step
durations of 4-9 seconds against 10-42 seconds for the gate that precedes it. Do not remove it as a
cost measure; it is already free.

Unit cost is fixed per release run at roughly 2.0 GB, about INR 21.5, and scales with merge frequency
rather than with code size. At the 2026-08-13/14 pace of twelve releases per day this line item
projects to about INR 7,750/month, which alone would exceed the whole idle baseline.

Mitigation in place since 2026-08-16: `build-release.yml` restores a stored verdict from
`gs://custoking-scan-evidence` when the same immutable digest passed the same pinned scanner revision
within 24 hours, and skips the scan. Only passes are stored, so a failing gate always rescans. Cache
failures are misses, never passes; the gate still fails closed and the enforcement step still requires
the evidence files on disk. Bucket IAM is in `infra/terraform/cicd/main.tf`.

Residual risks worth monitoring:

- The saving depends on merge frequency. A quiet week saves little because there is little to save.
- The 24-hour TTL is the deliberate trade. An unchanged digest can ship up to a day after its last
  CVE evaluation. Shortening the TTL costs egress; lengthening it costs freshness.
- Scanning still happens off-platform, so a pull is still billed for each genuinely new digest.
  Moving the gate to same-region execution was investigated on 2026-08-17 and **deliberately not
  adopted**; see follow-up 10 for the measurements and the index-digest finding that closes it. The
  separate load-generator egress in `GCP-BUDGET-INCIDENT-2026-08-11.md` is unaffected by that
  decision and its same-region recommendation still stands on its own.

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

#### 1. Keep Documentation Drift Checked

Current-state docs were updated on 2026-08-04 for active CI/CD and Cloud Run min instances. Re-check them after each deployment-system change.

Why:

- New-school onboarding decisions should not be based on stale min-instance, CI/CD, or rollout assumptions.

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

Current Java services are 1 vCPU. Identity, operations, platform, and billing are 768 MiB. School-core is 2 GiB because it owns the larger consolidated tenant school, student, attendance, fee, catalog, and photo-import surface. Java services use `MaxRAMPercentage=75`.

Test changes in dev under a realistic seed:

- `JAVA_OPTS=-XX:TieredStopAtLevel=1`
- Spring lazy initialization for non-critical beans only, if it does not hide startup failures
- reduce unnecessary classpath scanning
- keep startup probes
- measure actual RSS/memory utilization
- evaluate whether identity, operations, platform, or billing can move from 768 MiB to 640 MiB or 512 MiB without OOM risk
- evaluate school-core separately; do not lower it from 2 GiB until photo import, student directory, fee, and attendance paths have memory evidence under realistic load

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

## Costs This Plan Previously Understated (2026-08-17)

Four gaps found by re-auditing the export rather than re-reading this document. Each changes a number
that has been quoted elsewhere.

### 1. Every figure in this plan is pre-tax

The billing export carries no tax rows: `cost_type` contains only `regular` and `rounding_error`.
Indian Google Cloud invoices add **18% GST** on top. Section 8 already lists taxes as excluded, so the
model is internally consistent, but any figure repeated outside that caveat understates the invoice
by 18%.

| Figure | Pre-tax | With 18% GST |
| --- | ---: | ---: |
| Zero-user floor | 4,450 | **5,251** |
| Normal development month | 5,500-7,500 | **6,490-8,850** |
| 100 schools / 200k students, zonal midpoint | 31,881 | **37,620** |
| 150 schools / 300k students, zonal midpoint | 37,396 | **44,127** |
| 100 schools, regional HA midpoint | 52,084 | **61,459** |

Quote GST-inclusive figures in any commercial or budgeting context. Keep the pre-tax figures for
engineering comparison against the export, which is pre-tax.

### 2. Cloud Logging is free-tier masked, and one load test nearly exhausts the allowance

Cloud Logging has billed exactly INR 0 throughout, which is why it never appeared in the baseline.
That is the free allowance, not absence of usage:

| Month | Ingested | Billed |
| --- | ---: | ---: |
| 2026-07 | 7.9 GiB | INR 0 |
| 2026-08 (to 17th) | 48.9 GiB | INR 0 |

The monthly free allowance is 50 GiB. August reached 48.9 GiB with effectively no users, and the
driver is a single day:

```
2026-08-11   38.33 GiB    full Soak load certification
2026-08-15    0.03 GiB    steady state
2026-08-16    0.04 GiB    steady state
```

Steady state is roughly 1.5 GiB/month and is not a concern. One Soak run consuming 77% of the monthly
allowance is. The existing gross-spend preflight in `scripts/invoke-dev-load-certification.ps1`
cannot see this, because ingestion inside the allowance bills zero — the guard reports the run as
free right up until the allowance is crossed, after which every further GiB is charged. A
volume-based `-AllowLoggingOverrun` guard now sits alongside the cost guard for that reason.

At 100-150 schools, sustained ingestion is unmodelled in
[SCALE-READINESS-AND-COST-PLAN-2026-08-10.md](SCALE-READINESS-AND-COST-PLAN-2026-08-10.md) and should
be measured before that fleet target is committed. The same free-tier masking applies to Pub/Sub
(1.5 GiB used against 10 GiB free), Cloud Trace and Cloud Monitoring: all bill zero today, none are
in the fleet model.

### 3. Committed use discounts do not apply to the current database

Worth stating plainly because it is counter-intuitive: prod runs `db-g1-small`, a **shared-core**
instance billed as one bundled SKU, `Cloud SQL for PostgreSQL: Zonal - Small instance in Delhi`.
Cloud SQL committed use discounts are spend commitments against **dedicated-core** vCPU and RAM SKUs,
which a shared-core instance does not consume. Buying a commitment today would buy nothing.

The billing account's own pricing export exposes only the `Default` consumption model, so committed
rates could not be read from project data and the percentages below must be confirmed against
Google's current published terms before any commitment.

CUDs become relevant only after the move to `db-custom-4-7680`, at which point the covered component
is compute, not storage:

| Component | Monthly, pre-tax |
| --- | ---: |
| 4 vCPU at INR 4.744054/vCPU-hour x 730 | 13,853 |
| 7.5 GiB RAM at INR 0.8034285/GiB-hour x 730 | 4,399 |
| **Compute subtotal, CUD-eligible** | **18,252** |
| 100 GiB SSD at INR 19.511835/GiB-month, not CUD-eligible | 1,951 |

At commonly documented Cloud SQL commitment terms, a one-year commitment on that compute saves in the
region of INR 4,500/month and a three-year roughly INR 9,500/month. Revisit at the tier change, not
before, and only once the steady-state shape is known — a commitment is a floor, so it converts
flexible spend into fixed spend.

### 4. Messaging is likely the largest single line at scale, larger than all of GCP

`MSG91_DRY_RUN` defaults to `true`, so nothing has been sent and messaging has cost nothing to date.
That makes it invisible in every measurement in this document while remaining the most probable
largest cost at fleet scale.

Three channels are wired in `platform-service`: SMS, email, and WhatsApp. Using this plan's own
example volume — 5% absentees across 300,000 students over 22 school days — gives 330,000 outbound
messages per month from attendance alone, before fee reminders, announcements, or transactional
notifications.

Provider rates are commercial and must come from the actual MSG91 contract, so the model is left
rate-parameterised rather than asserting a price:

| Rate per message | 330k messages/month | Versus 150-school zonal GCP (37,396 pre-tax) |
| ---: | ---: | --- |
| INR 0.10 | 33,000 | comparable to all infrastructure |
| INR 0.15 | 49,500 | **1.3x all infrastructure** |
| INR 0.20 | 66,000 | **1.8x all infrastructure** |
| INR 0.25 | 82,500 | **2.2x all infrastructure** |

Two structural points. WhatsApp is billed per 24-hour conversation rather than per message, so its
economics differ from SMS entirely and can be far cheaper at high volume; the channel mix is a
commercial lever, not just a product choice. And per-school margin cannot be computed until the
application emits per-school send counts — follow-up 5 in this document remains open, and no usage
counters exist in the services today, so the INR 199-399/school/month figures in section 8 are
top-down division of a modelled total rather than anything measured.

## Cost Guardrail Checklist

Before each school onboarding wave:

- [ ] Check month-to-date GCP cost vs budget.
- [ ] Check Cloud SQL CPU/memory/connections for last 7 and 30 days.
- [ ] Check Cloud Run p95 latency and cold-start rate for gateway, identity, school-core.
- [ ] Check max-instance saturation.
- [ ] Check outbox pending count and oldest pending age.
- [ ] Check Pub/Sub oldest unacked message age.
- [ ] Check student photo bucket growth.
- [ ] Check Artifact Registry size and, separately, Artifact Registry egress. Egress is the larger
      line item and is driven by release frequency, not by stored bytes. Confirm the verdict cache is
      being reused: `cachedCount` in `release-evidence/trivy/exact-digest-scan.json` should be
      non-zero on any run that did not rebuild every service.
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

1. Keep this plan and the current-state docs in sync after deployment-system or runtime-size changes.
2. Use the cost guardrails runbook: `docs/GCP-COST-GUARDRAILS-RUNBOOK.md`.
3. Add labels to remaining non-Cloud-Run GCP resources. Cloud Run manifests already carry `app`, `env`, `component`, `service`, `owner`, and `cost-center`.
4. Design and implement scheduled outbox relay job.
5. Add per-school usage counters.
6. Add weekly cost review SQL and dashboard.
7. Add dev Cloud SQL start/stop automation.
8. Run Java service cold-start/right-size experiments in dev.
9. **Do not run `terraform apply` in `infra/terraform/cicd` as it stands.** Correcting an earlier note
   in this document, which claimed applying would reconcile without conflict: it would not. Verified
   2026-08-16, `terraform state list` reports `No state file was found!`. There is no backend block,
   no local `terraform.tfstate`, and `gs://custoking-terraform-state` holds only `observability/`
   state. A plan therefore reports **95 to add, 0 to change, 0 to destroy** — it wants to create every
   service account, the workload identity pool and the Artifact Registry repository, all of which
   already exist. `infra/terraform/cicd/README.md` documents the required import-first sequence.

   The scan-evidence and BigQuery bindings were granted directly with `gcloud`/`bq` on 2026-08-16 and
   are correct and live; only their *state management* is outstanding. Making this module genuinely
   state-managed needs two things done deliberately, not as a side effect of a cost fix: a GCS
   backend for `cicd` mirroring the one `observability` already uses, and a full import of the
   existing resources per the README. Importing writes state only and does not mutate
   infrastructure; the risk is entirely in the first apply afterwards, so that plan must be read
   line by line before it runs.
10. **CLOSED, not adopted (2026-08-17): move the exact-digest scan gate to same-region execution.**
    Investigated and prototyped; the remaining saving does not justify the change. Do not re-open
    without reading the findings below, each of which cost real investigation time.

    Two candidate targets were eliminated outright. **Cloud Build** is not same-region: its default
    pool bills as `E2 cpu utilization per minute in the global region (Default Pool)`, so pulling
    `asia-south2` images to it is cross-region and possibly worse than today. A regional or private
    pool would fix that but adds configuration or fixed cost, and Cloud Build is not free here
    either, having already billed INR 869 for 343,848 CPU-seconds. **Artifact Analysis on-demand
    scanning** is roughly an order of magnitude underwater: about USD 0.26 per image against roughly
    930 new digests a month is approximately INR 21,000/month, to save INR 1,000-3,000.

    That left scanning at build time, on the runner that just built the image, where the layers are
    already local and no pull occurs. A local prototype against a throwaway registry established:

    - Multi-exporter (`type=image,push=true` plus `type=oci,dest=...`) does work with
      `provenance=mode=max` and `sbom=true`.
    - The **image manifest digest is identical** between the pushed image and the exported tarball,
      so scanning the tarball scans exactly the released image content.
    - The **index digest differs**, because the attestation manifest embeds per-exporter build
      metadata. Pushed `sha256:4fbd6160d277...` against tarball `sha256:b5439f067b19...`, with image
      sub-manifest `sha256:063174c5a67f...` matching in both.

    This is the finding that closes the item. The gate keys evidence on the **index** digest, so the
    design only works if the verdict cache is re-keyed to the runtime image digest that
    `build-release.yml` already computes. That re-key is defensible, since attestation metadata
    carries no CVEs, but it changes the meaning of the security contract, invalidates every stored
    verdict, and lands alongside a build-path change and a new fallback branch for digests without a
    verdict. Three coupled changes to a release security gate.

    One correction for anyone re-testing: `--load` combined with attestations does **not** reliably
    fail. It succeeded on a workstation using the containerd image store
    (`io.containerd.snapshotter.v1`), which handles OCI indexes. GitHub-hosted runners typically use
    the classic dockerd store and are expected to behave differently. Do not conclude anything about
    runner behaviour from a local test.

    The verdict cache already removed roughly 90% of this line item, which is why the remainder does
    not pay for the complexity. Measured: 2026-08-13 INR 323.19 and 2026-08-14 INR 194.85 before the
    cache, against 2026-08-16 INR 30.24 on a day that ran two full releases. Re-open only if merge
    frequency rises far enough that the residual egress becomes material against these figures.
11. Reconcile the release service-account drift found on 2026-08-16: Terraform declares
    `github-release-dev`, but that account does not exist and the dev environment sets no
    `RELEASE_BUILDER_SERVICE_ACCOUNT`, so dev releases run as the shared `github-actions-sa`. Until
    that is resolved, `var.dev_release_service_account` must keep pointing at the account dev
    actually uses or the verdict cache silently stops working on dev.

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

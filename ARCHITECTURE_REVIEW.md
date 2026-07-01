# Architecture Review — Custoking IMS

**Reviewer role:** Principal / Chief Architect
**Date:** 2026-06-28
**Scope:** Full-stack review — service boundaries, data flow, dependencies, infra/deploy, DB schema, CI/CD — benchmarked against 2026 best practices.
**Constraint:** Analysis only. No code changes were made as part of this review.

---

## 1. Executive Summary

Custoking IMS is a multi-tenant school/education SaaS that was decomposed from a Spring Boot monolith into **12 Java microservices + a Node API gateway + a React SPA**, running on **Cloud Run** against **a single Cloud SQL Postgres instance (schema-per-service)**, with **Pub/Sub** push used for a subset of projections.

**The single most important finding:** this is a **distributed monolith at "nanoservice" granularity**. The evidence is unambiguous:

- The 12 services total **~18,000 lines** of Java — averaging **~1,200 LOC** each (smallest: workflow 580, audit 445). Several are a single controller + a single read repository.
- They **share one database** and **read each other's schemas directly via SQL**. The dependency baseline (`docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json`) formally sanctions: `reporting → 8 other schemas`, plus `attendance/catalog/fee → student,tenant_school` and `student → tenant_school`. `reporting-service` issues raw SQL against 20+ tables across 8 schemas.
- A large fraction of the codebase and operational effort exists **to police boundaries** that were never truly established: per-service grant scripts, schema-dependency audits, package-shape audits, boundary-verifier gates, and a multi-week "shrink the cross-schema baseline" program.

Per the 2026 consensus, a system whose services **cannot deploy independently** and **share a database** pays the full operational cost of distribution for none of the benefit — the textbook distributed-monolith anti-pattern ([enqcode](https://enqcode.com/blog/rethinking-microservices-in-2026-when-modular-monolith-architecture-actually-win), [horizonlabs](https://www.horizonlabs.com.au/insights/microservices-vs-modular-monolith-choosing-right-architecture-2026)). For a team of this size (inferred small, from repo scale and history), the recommended target is a **modular monolith or a small number of right-sized services (~5–7 deployables)**, which delivers ~80% of the architectural clarity with a fraction of the operational complexity ([byteiota](https://byteiota.com/microservices-too-expensive-modular-monoliths-win-2026/)).

**The second finding:** the platform is on an **End-of-Life framework**. Spring Boot **3.4 reached EOL on 2025-12-31** and receives no further OSS security patches ([endoflife.date](https://endoflife.date/spring-boot), [HeroDevs](https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026)). This is a P0 security exposure across all 12 services.

**What is genuinely good** and should be preserved: clean per-service Flyway histories, a fail-closed internal-token model, correlation-id + structured logging, an audit gate culture, the recently-completed single-DB-user consolidation, and a thin, dependency-free gateway. The engineering discipline is high — it is largely being spent maintaining accidental complexity.

---

## 2. Current-State Map

| Layer | Observed |
|---|---|
| Frontend | React 18.3, Vite 6.2, TypeScript 5.7, axios 1.9, react-router 6.30 |
| Gateway | Node `http` (392 LOC), prefix/regex routing, **per-request JWT introspection** to identity, internal-token injection, Cloud Run ID-token minting |
| Services | 12 × Spring Boot **3.4.13**, **Java 21**, Flyway, HikariCP, logstash JSON logs |
| Sync coupling | identity → tenant-school (HTTP, now with timeouts/retry); notification → MSG91 (external) |
| Async | Pub/Sub **push** → reporting + notification (HTTP push receivers) |
| Data | **1× Cloud SQL Postgres 15**, schema-per-service, **direct cross-schema SQL reads** (esp. reporting) |
| DB objects | 58 tables, 99 indexes, 30 intra-schema FKs, single `appuser` role (hardened: NOCREATEROLE/NOCREATEDB/NOINHERIT) |
| Infra | Cloud Run, `--max-instances=2`, `--memory=768Mi`, **no min-instances (scale-to-zero)**, **no startup CPU boost** |
| CI/CD | `ci.yml` (change-detection matrix: test/docker/secret-scan) + `deploy.yml` (Cloud Build) + **15 per-service `gcp-deploy-*.yml`** |
| Build | Cloud Build builds images **sequentially in a shell loop**; "all" deploy ≈ 30 min (observed) |

---

## 3. Prioritized Improvement Plan

Priority key: **P0** critical → **P3** nice-to-have. Each item lists **Change · Rationale · Impact · Effort · Risk**.

---

### P0 — Critical (do now)

#### P0-1. Upgrade Spring Boot 3.4.13 → 3.5.16 across all 12 services
- **Change:** Bump the parent to the latest 3.5.x; run the 3.4→3.5 migration (minimal breaking changes). Stay on Java 21 for this step.
- **Rationale:** Spring Boot 3.4 is **EOL since 2025-12-31** — zero OSS security patches; any new CVE is unaddressed ([endoflife.date](https://endoflife.date/spring-boot)). 3.5 is the last 3.x line and is patched through **2026-06-30 OSS** ([HeroDevs](https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026)).
- **Impact:** High (security) · **Effort:** Low–Medium (consistent versions already; one parent bump + test matrix) · **Risk:** Low.

#### P0-2. Plan the Spring Boot 4.0 / Spring Framework 7 path now
- **Change:** Create a tracked upgrade spike: 3.5 OSS support ends **2026-06-30**, so 3.5 is a 6-month bridge, not a destination. Target **Spring Boot 4.0** (Spring Framework 7, Jakarta, requires Java 17+; pairs naturally with Java 25).
- **Rationale:** Avoid a second EOL scramble in H2 2026 ([foojay](https://foojay.io/today/crossing-the-river-styx-spring-boot-3-5-and-the-zombie-dependency-problem/)).
- **Impact:** High · **Effort:** Medium · **Risk:** Medium (Framework 7 dependency changes).

#### P0-3. Make an explicit architecture decision: stop "shrinking the cross-schema baseline"; choose the target topology
- **Change:** Adopt a written decision (ADR) — **consolidate to ~5–7 deployables** (recommended) **or** commit to true event-driven decoupling. Freeze the incremental baseline-shrink program until the target is chosen; it is currently optimizing a distributed monolith one query at a time.
- **Rationale:** Direction-setting unblocks every other data/infra decision and stops sunk effort. The current trajectory (remove one cross-schema read per PR, add a grant script + audit each time) is high-effort, low-yield.
- **Impact:** High · **Effort:** Low (decision) · **Risk:** Low — but high-leverage.

---

### P1 — High

#### P1-1. Consolidate the 12 nanoservices into ~5 domain services (+ gateway, reporting, audit)
- **Change:** Merge along the **actual coupling seams**:
  - **`school-core`** ← tenant-school + student + attendance (they already cross-read `student`/`tenant_school`; same transactional boundary).
  - **`commerce`** ← fee + catalog + billing + workflow (orders, payments, invoices, approvals — one money/approval domain).
  - **`operations`** ← firefighting (+ optionally fold workflow approvals here).
  - **Keep separate** (genuinely independent reasons): **identity** (security boundary), **notification** (async + external MSG91, bursty), **reporting** (read-model/projection workload), **audit** (append-only sink), **api-gateway**, **frontend**.
  - Net: **~14 deployables → ~8**, and most cross-schema reads become **in-process calls** within `school-core`/`commerce`.
- **Rationale:** Removes the majority of cross-schema coupling, network hops, and the boundary-policing tooling. Matches 2026 right-sizing guidance for sub-20-engineer teams ([technijian](https://technijian.com/software-development/microservices-vs-monolith-for-startups-the-honest-2026-decision-guide/), [horizonlabs](https://www.horizonlabs.com.au/insights/microservices-vs-modular-monolith-choosing-right-architecture-2026)).
- **Impact:** High · **Effort:** High · **Risk:** Medium (preserve gateway `/api/v1/**` contract via the existing compat controllers; merge is mostly module-moves since schemas already separate).
- **Note:** Lower-effort variant — collapse all domain services into **one modular monolith** with enforced module boundaries (ArchUnit/Spring Modulith) and keep gateway/reporting/audit/notification separate. Strongly consider for the team size.

#### P1-2. Decouple `reporting` from direct cross-schema SQL
- **Change:** Reporting must own its data via **projections fed by events**, not by reaching into 8 schemas. Implement the **transactional outbox** in the owning services + Pub/Sub (you already have Pub/Sub push receivers) → reporting builds read models. Do **not** introduce Kafka/Debezium at this scale unless volume demands it — a Postgres outbox table polled/streamed to Pub/Sub is sufficient ([debezium.io outbox](https://debezium.io/blog/2019/02/19/reliable-microservices-data-exchange-with-the-outbox-pattern/), [streamkap](https://streamkap.com/resources-and-guides/outbox-pattern-explained)).
- **Rationale:** The reporting→8-schema fan-out is the keystone of the distributed-monolith coupling and the single biggest blocker to independent deploy/scale.
- **Impact:** High · **Effort:** High · **Risk:** Medium (eventual consistency in dashboards — acceptable for reporting).
- **Interaction with P1-1:** If you consolidate (P1-1), several of reporting's sources collapse into 2–3 services, making the outbox surface much smaller. **Sequence P1-1 before P1-2.**

#### P1-3. Fix Cloud Run cold starts for 12 JVM services
- **Change:** (a) Add **startup CPU boost** (`--cpu-boost`) — free, 30–50% faster JVM/Spring init ([Google Cloud](https://cloud.google.com/blog/products/serverless/announcing-startup-cpu-boost-for-cloud-run--cloud-functions)). (b) Set **`--min-instances=1`** on the hot path (gateway, identity, and the consolidated `school-core`). (c) JVM flags: `-XX:+UseG1GC` is default; add `-XX:TieredStopAtLevel=1` + `spring.main.lazy-initialization` consideration for non-hot services ([oneuptime](https://oneuptime.com/blog/post/2026-02-17-how-to-optimize-cloud-run-cold-start-latency-for-java-and-spring-boot-applications/view)).
- **Rationale:** Scale-to-zero + JVM = multi-second cold starts on every dependency in the request path; with 12 services a single user action can hit several cold JVMs.
- **Impact:** High (UX/latency) · **Effort:** Low · **Risk:** Low. Consolidation (P1-1) compounds the benefit (fewer JVMs to warm) and reduces the min-instance cost.

#### P1-4. Gateway auth: stop per-request identity introspection
- **Change:** Have the gateway **verify the JWT locally** against identity's public signing key (JWKS), caching the key; reserve introspection for refresh/revocation. Cache permissions briefly.
- **Rationale:** Per-request introspection makes **identity a latency tax and a SPOF** on every protected call and is the worst case when identity is cold (P1-3).
- **Impact:** High (latency, resilience) · **Effort:** Medium · **Risk:** Medium (token revocation latency — mitigate with short TTLs).

#### P1-5. Java 21 → 25 (LTS)
- **Change:** Move the build/runtime to **Java 25 LTS**. Pair with the Spring Boot 4 work (P0-2).
- **Rationale:** Java 21 free Oracle updates end **Sep 2026**; Java 25 is supported to **2033**, fixes the virtual-thread pinning limitation (JEP 491), and improves startup/GC/memory — directly relevant to Cloud Run cost ([javacodegeeks](https://www.javacodegeeks.com/2026/04/java-21-vs-java-25-lts-the-migration-decision-framework-teams-are-avoiding.html), [mac.install.guide](https://mac.install.guide/java/java25-vs-java21)).
- **Impact:** Medium–High · **Effort:** Low–Medium · **Risk:** Low (21→25 is a low-risk LTS hop).

#### P1-6. Parallelize the Cloud Build image build
- **Change:** Replace the sequential `for`-loop `docker build` with **parallel Cloud Build steps** (`waitFor: ['-']`) or Kaniko/Buildx with layer caching to Artifact Registry; only build **changed** services on deploy (CI already has change-detection — extend it to `deploy.yml`).
- **Rationale:** "Deploy all" takes ~30 min (observed) because 14 images build serially with no cache. Most deploys touch 1–2 services.
- **Impact:** High (deploy speed/cost) · **Effort:** Medium · **Risk:** Low.

---

### P2 — Medium

#### P2-1. Database: connection-pool math vs a single instance
- **Change:** With 12 services × Hikari `maximum-pool-size=5` (set in app yml) **+** the cloudbuild override `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5`, peak connections approach **~60+** against one Postgres. Right-size per tier, or front Cloud SQL with **PgBouncer / the Cloud SQL connector pooling**; reconcile the duplicated pool config (app yml vs cloudbuild env both set it). Consolidation (P1-1) reduces pool count directly.
- **Rationale:** One shared instance is the real bottleneck and a connection-exhaustion risk under scale-out.
- **Impact:** Medium · **Effort:** Low–Medium · **Risk:** Medium (sizing).

#### P2-2. Database: indexing, partitioning, and dead objects
- **Change:** (a) Review hot read paths in reporting/attendance/fee for covering indexes (99 indexes / 58 tables is reasonable but reporting's cross-schema joins are unindexed across schemas). (b) **Partition `attendance.attendance_daily`** (and any high-growth time-series) by date — daily attendance grows unbounded. (c) Audit for unused tables/columns left by the monolith→service split and historical backfills; drop after verification. (d) Add missing NOT NULL / CHECK / FK constraints within schemas where integrity is currently enforced only in app code.
- **Rationale:** Time-series growth and cross-schema reporting joins are the query hotspots; integrity-in-app-only is fragile.
- **Impact:** Medium · **Effort:** Medium · **Risk:** Low–Medium (partitioning migration).

#### P2-3. Frontend currency
- **Change:** React **18.3 → 19.2** (18 is security-only in 2026), Vite **6 → 7/8**, refresh axios/router/vitest to current.
- **Rationale:** React 18 receives only critical security fixes now; 19 brings Actions, `use`, and ~30% perf gains; Vite 6 is two majors behind (7 and 8 released) ([react.dev/versions](https://react.dev/versions), [endoflife React](https://endoflife.date/react), [vite.dev/releases](https://vite.dev/releases)).
- **Impact:** Medium · **Effort:** Medium (React 19 codemods; Vite 7 migration is small) · **Risk:** Low–Medium.

#### P2-4. Collapse 15 per-service deploy workflows into one reusable matrix workflow
- **Change:** Replace `gcp-deploy-<service>.yml` ×15 with a single **reusable** `workflow_call` + matrix (you already have `gcp-deploy-all.yml` delegating to `deploy.yml`). Keep one manual entrypoint.
- **Rationale:** 15 near-identical workflow files are maintenance drag and drift risk (they already diverged on IAM history).
- **Impact:** Medium (maintainability) · **Effort:** Low · **Risk:** Low.

#### P2-5. Distributed tracing
- **Change:** Add **OpenTelemetry** (Micrometer Tracing → OTLP → Cloud Trace) across gateway + services; you already propagate `X-Request-Id`/`traceparent`.
- **Rationale:** With multi-hop requests, log correlation alone can't show where latency/errors originate. (Consolidation reduces hops but tracing stays valuable.)
- **Impact:** Medium · **Effort:** Medium · **Risk:** Low.

#### P2-6. CI/CD hardening additions
- **Change:** Add **SBOM generation** (Syft) + **dependency/CVE scanning** (Trivy/Grype on every image, not just gateway), **OIDC-only** (already via WIF — keep), pin actions to SHAs, and add a **Spring Boot version gate** (fail CI if a service drifts off the supported line). Cache Maven (`~/.m2`) and the Docker layer cache.
- **Rationale:** EOL-framework exposure (P0-1) would have been caught by a version gate; image CVE scanning is currently gateway-only.
- **Impact:** Medium · **Effort:** Low–Medium · **Risk:** Low.

---

### P3 — Nice-to-have

#### P3-1. GraalVM native images (or CRaC) for the worst cold-start services
- **Change:** AOT-compile the latency-critical services to native (GraalVM) for ~sub-second / ~10–50× faster starts and lower memory, or evaluate **CRaC** checkpoint/restore ([Medium/Google Cloud](https://medium.com/google-cloud/cloud-run-the-spring-boot-rebirth-with-graalvm-native-compilation-bacd14307cb0), [spring.io](https://spring.io/blog/2023/10/16/runtime-efficiency-with-spring/)).
- **Rationale:** Removes JVM cold-start entirely on scale-to-zero. Do **after** consolidation so you're optimizing fewer artifacts.
- **Impact:** Medium (cost/latency) · **Effort:** High (native-image reflection config, build time) · **Risk:** Medium.

#### P3-2. Feature value-vs-cost pruning
- **Change:** Evaluate **`firefighting`** (very niche domain — equipment/quote/approval workflow; 891 LOC across its own service + gateway routes + reporting wiring) and **`workflow`** (580 LOC, thin) for either folding into `commerce`/`operations` or removal if low-usage. Review **billing vs fee** overlap (both touch money/invoices) for a unified ledger model. Confirm the **superadmin billing module** and **approvals inbox** (restored during migration) have real usage.
- **Rationale:** Each standalone domain service carries fixed operational + boundary-maintenance cost; thin/low-value ones don't earn it.
- **Impact:** Medium (maintenance reduction) · **Effort:** Low (analysis) → Medium (rework) · **Risk:** Low.

#### P3-3. Documentation & repo hygiene
- **Change:** Archive the now-historical migration/boundary docs and grant-era scripts under `docs/history/`; the active doc set should reflect the chosen target topology (P0-3), not the migration journey. Remove `artifacts/` deployment-evidence dumps from the repo (move to release artifacts).
- **Rationale:** The repo currently documents the *journey* more than the *destination*; new engineers will over-index on boundary ceremony.
- **Impact:** Low–Medium · **Effort:** Low · **Risk:** Low.

#### P3-4. Cost optimization sweep
- **Change:** After consolidation: fewer Cloud Run services = fewer idle min-instances and less Cloud SQL connection pressure; right-size `--memory` per service (768Mi is uniform — measure actual RSS); set Cloud Run **request-based concurrency** appropriately; apply the `gs://...github-deploy-source` lifecycle (already started) and Artifact Registry cleanup policies for old image tags.
- **Rationale:** 14 services × idle instances × uniform memory is over-provisioned for the traffic implied by the codebase.
- **Impact:** Medium (cost) · **Effort:** Low · **Risk:** Low.

---

## 4. Recommendation-at-a-Glance

| ID | Recommendation | Impact | Effort | Risk |
|---|---|---|---|---|
| **P0-1** | Spring Boot 3.4 (EOL) → 3.5.16 | High | Low–Med | Low |
| **P0-2** | Plan Spring Boot 4.0 / Framework 7 | High | Med | Med |
| **P0-3** | ADR: choose target topology; stop baseline-shrink busywork | High | Low | Low |
| **P1-1** | Consolidate 12 → ~5 domain services (or modular monolith) | High | High | Med |
| **P1-2** | Decouple reporting via outbox + Pub/Sub (after P1-1) | High | High | Med |
| **P1-3** | Cloud Run cold-start: CPU boost + min-instances + JVM flags | High | Low | Low |
| **P1-4** | Gateway local JWT verify (drop per-request introspection) | High | Med | Med |
| **P1-5** | Java 21 → 25 LTS | Med–High | Low–Med | Low |
| **P1-6** | Parallelize/cached Cloud Build; build only changed on deploy | High | Med | Low |
| **P2-1** | DB connection-pool sizing / PgBouncer; de-dupe pool config | Med | Low–Med | Med |
| **P2-2** | Indexing review, partition attendance, drop dead objects | Med | Med | Low–Med |
| **P2-3** | React 18→19, Vite 6→7/8 | Med | Med | Low–Med |
| **P2-4** | 15 deploy workflows → 1 reusable matrix | Med | Low | Low |
| **P2-5** | OpenTelemetry tracing | Med | Med | Low |
| **P2-6** | SBOM + image CVE scan all services + SB version gate | Med | Low–Med | Low |
| **P3-1** | GraalVM native / CRaC for hot services | Med | High | Med |
| **P3-2** | Prune firefighting/workflow; unify billing+fee | Med | Low–Med | Low |
| **P3-3** | Archive migration docs/scripts; remove artifacts/ | Low–Med | Low | Low |
| **P3-4** | Cost sweep: memory right-size, min-instances, AR cleanup | Med | Low | Low |

---

## 5. Suggested Sequencing (the "critical path")

1. **Week 0:** P0-1 (Spring Boot 3.5) + P1-3 (CPU boost/min-instances — instant UX win) + P0-3 (topology ADR).
2. **Weeks 1–4:** P1-1 (consolidation) — this is the highest-leverage change and shrinks the surface for everything after it.
3. **Weeks 4–6:** P1-2 (reporting outbox, now smaller), P1-4 (gateway JWT), P1-6 (build pipeline).
4. **Parallel track:** P1-5 + P0-2 (Java 25 + Spring Boot 4 spike), P2-3 (frontend).
5. **Then:** P2 hardening, P3 optimizations.

**One-line thesis:** *You built microservices discipline; what you have is a distributed monolith. Spend the next quarter consolidating to a right-sized topology and getting off EOL Spring Boot — not shrinking the cross-schema baseline one query at a time.*

---

## 6. Sources

- Spring Boot EOL/versions: [endoflife.date/spring-boot](https://endoflife.date/spring-boot) · [HeroDevs — SB versions & EOL (Apr 2026)](https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026) · [foojay — SB 3.5 EOL](https://foojay.io/today/crossing-the-river-styx-spring-boot-3-5-and-the-zombie-dependency-problem/)
- Java LTS: [Java Code Geeks — Java 21 vs 25](https://www.javacodegeeks.com/2026/04/java-21-vs-java-25-lts-the-migration-decision-framework-teams-are-avoiding.html) · [mac.install.guide — Java 25 vs 21](https://mac.install.guide/java/java25-vs-java21)
- Architecture (modular monolith / distributed monolith, 2026): [Horizon Labs](https://www.horizonlabs.com.au/insights/microservices-vs-modular-monolith-choosing-right-architecture-2026) · [enqcode](https://enqcode.com/blog/rethinking-microservices-in-2026-when-modular-monolith-architecture-actually-win) · [byteiota](https://byteiota.com/microservices-too-expensive-modular-monoliths-win-2026/) · [technijian](https://technijian.com/software-development/microservices-vs-monolith-for-startups-the-honest-2026-decision-guide/)
- Cloud Run / Java cold start: [oneuptime](https://oneuptime.com/blog/post/2026-02-17-how-to-optimize-cloud-run-cold-start-latency-for-java-and-spring-boot-applications/view) · [Google Cloud — startup CPU boost](https://cloud.google.com/blog/products/serverless/announcing-startup-cpu-boost-for-cloud-run--cloud-functions) · [spring.io — runtime efficiency](https://spring.io/blog/2023/10/16/runtime-efficiency-with-spring/) · [Google Cloud Community — GraalVM native](https://medium.com/google-cloud/cloud-run-the-spring-boot-rebirth-with-graalvm-native-compilation-bacd14307cb0)
- Outbox/CDC: [Debezium — outbox](https://debezium.io/blog/2019/02/19/reliable-microservices-data-exchange-with-the-outbox-pattern/) · [Streamkap — outbox explained](https://streamkap.com/resources-and-guides/outbox-pattern-explained)
- Frontend: [react.dev/versions](https://react.dev/versions) · [endoflife.date/react](https://endoflife.date/react) · [vite.dev/releases](https://vite.dev/releases)

---

# 7. Multi-Tenancy (Appended Review)

> **Critical context for this whole section:** the tenant-isolation finding below is **both a tenancy gap and the #1 security vulnerability** in the system. It is cross-referenced in §8 (Security). 🔗 = a recommendation that is simultaneously a multi-tenancy and a security control.

## 7.1 Current State (as built)

- **Tenant = a school** (`school_id`), with **zones** (`zone_id`) grouping schools; the tenant root lives in `tenant_school.schools` (and `zones`). Superadmins are the platform/cross-tenant role.
- **Isolation model in use:** *shared database, shared schema, discriminator column* — `school_id` / `zone_id` columns on domain tables, **enforced only in application code**. PostgreSQL **Row-Level Security is disabled** (per `docs/tenant-isolation.md`, RLS was turned off in migration V117). The DB does **not** enforce tenancy.
- **Tenant context propagation:** the gateway introspects the JWT and forwards `x-authenticated-user-id / -email / -role / -school-id / -zone-id` headers to upstreams (`server.js`). **This is good — but the services ignore it.**
- **THE finding:** domain services scope queries by a **client-supplied `@RequestParam schoolId`**, not the authenticated header. Worse, the filter is **optional**: e.g. `AttendanceReadRepository.records()` does `if (schoolId != null) sql.append(" AND school_id = :schoolId")` — **omit the param and the query returns every tenant's rows; pass another tenant's id and you read their data.** Only **1 controller** in the codebase (`TenantSchoolPublicCompatibilityController`) reads `X-Authenticated-School-Id`. Some endpoints (e.g. `/attendance/daily`) have **no tenant predicate at all**.
- **Schema hygiene:** **16 nullable `school_id` columns vs 10 NOT NULL** — nullable tenant keys silently escape discriminator filters.

**Verdict:** the model is the right *shape* for this scale, but isolation is **client-trusting, optional, and unenforced at the database** — effectively no hard tenant boundary today.

## 7.2 Isolation-Model Comparison & Recommendation

| Model | Isolation | Cost/Density | Ops complexity | Fit here |
|---|---|---|---|---|
| **DB-per-tenant** | Strongest (physical) | Worst (one instance/tenant) | High (N migrations, N backups) | Only for white-label/regulated tenants |
| **Schema-per-tenant** | Strong | Medium | High (schema sprawl, migration fan-out) | "Rarely worth it at scale" |
| **Shared schema + `tenant_id` + RLS** | Strong *if RLS enforced* | Best (pooled) | Low–Medium | ✅ **Recommended default** |

The 2026 consensus is unambiguous: **shared schema with a tenant discriminator + PostgreSQL RLS is the standard for B2B SaaS at this scale**; schema-per-tenant is rarely worth it and DB-per-tenant is for regulated/white-label workloads ([PlanetScale](https://planetscale.com/blog/approaches-to-tenancy-in-postgres), [dasroot](https://dasroot.net/posts/2026/01/multi-tenancy-database-patterns-schema-database-row-level/), [AWS Prescriptive Guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/saas-multitenant-managed-postgresql/rls.html)). **You already have the right model — you are missing the enforcement (RLS) and you are trusting the client for the discriminator.**

**Recommendation:** keep shared-schema + discriminator, but (a) drive the discriminator from the **authenticated context**, (b) add **RLS as a database-enforced backstop**, and (c) optionally offer a **DB-per-tenant tier** later for enterprise/white-label schools (a hybrid "tiered" model).

## 7.3 Recommendations

### MT-P0-1 🔗 — Enforce tenant scope from the authenticated context, never from client params
- **Change:** Every domain query must derive `school_id`/`zone_id` from the gateway's `X-Authenticated-School-Id`/`-Zone-Id` (which the gateway already injects from the verified JWT), bound in a per-request `TenantContext` (filter → service → repository). Treat client-supplied `schoolId` as a *filter within scope only*, intersected with the authenticated scope server-side. Remove all "optional" tenant predicates. Superadmin is the only role allowed to widen scope, via an explicit check.
- **Rationale:** This is textbook **BOLA / Broken Access Control (OWASP A01, #1 in 2025)** and a live cross-tenant data-leak in student PII. The fix matches the standard guidance: *validate JWT at the gateway and re-validate authorization on the backend; pass trusted claims (user-id, tenant-id) in headers; deny by default* ([Cerbos](https://www.cerbos.dev/blog/broken-access-control-owasp-top-10-2025), [Auth0](https://auth0.com/blog/why-broken-access-control-still-dominates-owasp-top-10/), [Wiz](https://www.wiz.io/academy/api-security/owasp-api-security)).
- **Impact:** **Critical (data confidentiality)** · **Effort:** Medium (a shared `TenantContext` filter + repository sweep; consolidation in P1-1 shrinks the surface) · **Risk:** Medium (must catch every query path — pair with the RLS backstop below).

### MT-P0-2 🔗 — Add PostgreSQL RLS as a database-enforced backstop
- **Change:** Re-enable RLS on every tenant-scoped table with `USING` (read) **and** `WITH CHECK` (write) policies comparing `school_id` to a transaction-local GUC, e.g. `current_setting('app.current_school_id')`, set per request/transaction via `set_config`. App connects as a **non-owner, non-superuser** role so RLS actually applies (owners/superusers bypass RLS).
- **Rationale:** Centralizes isolation at the DB so a single missed `WHERE` cannot leak tenants — "removing the burden from developers" is the explicit benefit of RLS ([AWS](https://aws.amazon.com/blogs/database/multi-tenant-data-isolation-with-postgresql-row-level-security/), [oneuptime](https://oneuptime.com/blog/post/2026-01-25-row-level-security-postgresql/view)). **Footgun to flag:** the current single `appuser` **owns all schemas and is a `cloudsqlsuperuser` member → it would bypass RLS entirely.** This directly conflicts with the recent "single `appuser`" consolidation (§ prior plan). RLS requires re-introducing a **separate, unprivileged runtime role** distinct from the owner ([Bytebase footguns](https://www.bytebase.com/blog/postgres-row-level-security-footguns/), [AWS RLS recs](https://docs.aws.amazon.com/prescriptive-guidance/latest/saas-multitenant-managed-postgresql/rls.html)).
- **Impact:** High · **Effort:** Medium–High · **Risk:** Medium (test as the *unprivileged* role — testing as owner falsely "passes"; a known #1 mistake).

### MT-P1-1 — Standardize the tenant key + make it NOT NULL with composite, tenant-leading indexes
- **Change:** (a) Treat `school_id` as the canonical `tenant_id` (optionally introduce a `tenant_id` alias/column for clarity); (b) backfill and set **NOT NULL** on the 16 nullable tenant columns (backfill from the owning row's school, then `ALTER ... SET NOT NULL` in a forward migration); (c) ensure **every** tenant-scoped index/PK has **`school_id` as the leading column** (`(school_id, …)`).
- **Rationale:** Missing composite indexes are "the #1 performance killer" for RLS — benchmarks show **0.3ms with a tenant-leading composite index vs 120ms without** ([2026 RLS benchmark via techbuddies/oneuptime](https://www.techbuddies.io/2026/02/04/how-to-implement-postgresql-row-level-security-for-multi-tenant-saas-2/)). Nullable tenant keys are an isolation hole.
- **Impact:** High (perf + isolation) · **Effort:** Medium · **Risk:** Medium (backfill + NOT NULL on live tables — do online).
- **Progress note (2026-06-30, branch `phase1-tenant-keys`):** Phase 1 of this task is complete. `school_id` is now `NOT NULL` on 10 in-scope tenant tables across 6 services via forward Flyway migrations run as `appuser`:
  - **Group A — existing column SET NOT NULL** (column was already present; verified zero NULLs before ALTER): `catalog.catalog_orders` (V3), `catalog.annual_plan_items` (V3), `workflow.workflow_instances` (V3), `firefighting.firefighting_requests` (V3), `reporting.command_center_actions` (V7).
  - **Group B — column denormalized then SET NOT NULL** (ADD nullable column + backfill from parent row + index + SET NOT NULL in next migration): `firefighting.ff_quotations` (backfill from parent `firefighting_requests`, same-schema plain UPDATE; V3 denormalize → V4 NOT NULL), `workflow.workflow_actions` (backfill from parent `workflow_instances`, same-schema plain UPDATE; V3 denormalize → V4 NOT NULL), `attendance.attendance_daily` (backfill cross-schema from `tenant_school.school_sections`, DO-block guard; V4 denormalize → V5 NOT NULL), `fee.fee_assignments` (backfill cross-schema from `student.students`, DO-block guard; V5 denormalize → V6 NOT NULL), `fee.payment_records` (same cross-schema backfill; V5 denormalize → V6 NOT NULL).
  - Tenant-leading composite indexes added: `idx_wf_instances_school_entity (school_id, entity_type, entity_id)`, `idx_wf_actions_school_instance (school_id, instance_id)`, `idx_ff_quotations_school_request (school_id, request_id)`, `idx_attendance_daily_school_date (school_id, attendance_date, academic_year_id)`, `idx_fee_assignments_school_year_student (school_id, academic_year_id, student_id)`, `idx_payment_records_school_paid (school_id, paid_at DESC)`.
  - Cross-schema backfills (attendance V4, fee V5) are wrapped in a `DO $$ BEGIN IF to_regclass(...) IS NOT NULL THEN ... END IF; END $$` guard; source table always present in production; the subsequent SET NOT NULL migration is the loud safety net if the backfill were skipped.
  - App insert methods updated to bind `school_id`: `addQuotation`, `recordAction` (+5 callers), `saveDailyAttendance`+`upsertDaily`, `assignFeePlan`, `recordPayment`.
  - **Intentionally excluded:** `reporting.command_center_feed` and `reporting.reporting_event_inbox` remain nullable (NULL = platform-wide); `fee.fee_bands` and `fee.fee_items` excluded (no per-tenant row scope).
  - **Immediate follow-up:** extend RLS to these now-NOT-NULL tables (the clean-column precondition is now satisfied). See `docs/MICROSERVICE-TENANT-KEY-ROLLOUT-RUNBOOK.md` for the two-phase production deploy procedure.

### MT-P1-2 — Canonical tenant registry + lifecycle states
- **Change:** Make `tenant_school.schools` the authoritative tenant record with explicit lifecycle (`provisioning → active → suspended → offboarding → deleted`), plan/tier, and region. Gateway/services reject requests for non-`active` tenants early.
- **Rationale:** Onboarding/suspension/offboarding need a single source of truth; "suspended" must hard-stop access (billing/abuse) before business logic.
- **Impact:** Medium · **Effort:** Medium · **Risk:** Low.

### MT-P1-3 🔗 — Transaction-scoped tenant GUC with a transaction-mode pooler
- **Change:** Set `app.current_school_id` via `set_config(..., is_local => true)` **inside the request transaction**, and use **PgBouncer transaction-mode** (or the Cloud SQL connector pooling) so tenant state can't leak across pooled connections.
- **Rationale:** With connection poolers, session-scoped tenant state leaks between tenants; tenant identity must be **transaction-scoped** ([thenile](https://www.thenile.dev/blog/multi-tenant-rls), [permit.io](https://www.permit.io/blog/postgres-rls-implementation-guide)). This also resolves the §P2-1 connection-pool pressure (12 services × pools on one instance).
- **Impact:** High (correctness + scale) · **Effort:** Medium · **Risk:** Medium.

### MT-P2-1 — Tenant onboarding/provisioning flow + tenant-scoped config
- **Change:** Formalize provisioning: create tenant record → seed RBAC/admin (you already have `provisionSchoolUser`) → default modules/entitlements → branding & feature-flag defaults → limits/quota. Store **tenant-scoped config** (branding, feature flags, plan limits) in one place keyed by `school_id`, served to the SPA and enforced server-side (module entitlements already exist — extend to quotas/flags).
- **Rationale:** Repeatable onboarding and per-tenant customization are core SaaS capabilities; you have entitlements but no unified tenant-config/flags/limits surface.
- **Impact:** Medium · **Effort:** Medium · **Risk:** Low.

### MT-P2-2 🔗 — Noisy-neighbor controls & per-tenant rate limiting
- **Change:** Add **per-tenant rate limits / quotas** at the gateway (token-bucket keyed by `school_id`), and per-tenant Cloud Run concurrency/fairness; cap expensive endpoints (imports, reports) per tenant.
- **Rationale:** In a pooled model one tenant can starve others; the shared-schema benchmark showed contention-driven latency spikes under load ([dasroot](https://dasroot.net/posts/2026/01/multi-tenancy-database-patterns-schema-database-row-level/)). Also a DoS/abuse control (security).
- **Impact:** Medium · **Effort:** Medium · **Risk:** Low.

### MT-P3-1 — Optional DB-per-tenant tier for enterprise/white-label
- **Change:** Allow specific tenants to be pinned to an isolated database/instance via the tenant registry's `region`/`tier`, routed by a tenant→datasource map.
- **Rationale:** Regulated/white-label/large tenants may demand physical isolation; tiering is the mature evolution of a pooled model.
- **Impact:** Low (few tenants) · **Effort:** High · **Risk:** Medium. **Defer until demanded.**

### Impact on microservices & CI/CD (ties to the existing plan)
- **Consolidation (P1-1) makes tenancy dramatically easier:** RLS, the `TenantContext` filter, and the GUC must be implemented and audited in **~5 services instead of 12**, and most cross-tenant query paths become in-process.
- **CI gate:** add an automated test that, as a tenant-A token, asserts **403/empty** for tenant-B object IDs on every list/detail endpoint (a "BOLA regression" suite) — wire it into the existing boundary-verifier culture.
- **RLS vs the single-`appuser` decision:** the prior plan collapsed to one owner role; **RLS needs an unprivileged runtime role** — reconcile these two before implementing (see MT-P0-2).
  - **Phase 1 Task 1.1 resolution (2026-06-29):** this reconciliation has been implemented. `app_rt` is introduced as a separate, unprivileged runtime role with DML + USAGE grants on all domain schemas, no DDL, `NOBYPASSRLS`, and no membership in `cloudsqlsuperuser`. `appuser` is intentionally retained as the schema owner and Flyway migration role. At runtime all services connect as `app_rt`; `appuser` is used only for Flyway migrations and DBA operations. This directly resolves the MT-P0-2 footgun and unblocks PostgreSQL RLS enforcement. The `app_rt` privilege set is verified automatically in the migration gate (`verify-microservice-migration.ps1 -RunDbAudit`) via `audit-app-rt-privileges.ps1`.
  - **Phase 1 Tasks 1–4 partial resolution (2026-06-30):** RLS is now **ENABLED** on the six clean NOT-NULL `school_id` tables across three services — `student.students`, `student.student_review_campaigns`, `student.student_review_items` (student-service V4), `attendance.attendance_student_records` (attendance-service V3), `reporting.academic_events`, `reporting.event_student_contributions` (reporting-service V6). Each service runs a `TenantAwareDataSource` that sets `app.current_school_id` and `app.bypass_rls` GUCs on every connection borrow, enforced for `app_rt` (NOBYPASSRLS); superadmin bypasses via `app.bypass_rls='on'`. Policy form: `ENABLE` (not `FORCE`) so `appuser`/Flyway transparently bypasses. Isolation is verified per-service by `*RlsIntegrationTest` (Testcontainers, runs as `app_rt`). **Remaining tables** (nullable `school_id`, cross-schema derived, fee-domain) continue to rely on the application-layer tenant enforcement (MT-P0-1) until Task 1.4 denormalizes `school_id` and makes those columns NOT NULL. See `docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md` for the two-phase prod rollout sequence and rollback migrations.
  - **Phase 1 RLS extension to Task-1.4 tables (2026-07-01, branch `phase1-rls-extension`):** RLS is now **ENABLED** (as `app_rt`, per-request GUC) on the 10 Task-1.4 tables that received NOT NULL `school_id` on branch `phase1-tenant-keys`: `catalog.catalog_orders` (V4), `catalog.annual_plan_items` (V4), `firefighting.firefighting_requests` (V5), `firefighting.ff_quotations` (V5), `workflow.workflow_instances` (V5), `workflow.workflow_actions` (V5), `attendance.attendance_daily` (V6), `fee.fee_assignments` (V7), `fee.payment_records` (V7), `reporting.command_center_actions` (V8). `TenantAwareDataSource` was newly added to catalog-service, firefighting-service, workflow-service, and fee-service; attendance-service and reporting-service already had it. This extends the Phase 1.3 RLS backstop across all denormalized tables. **Intentionally excluded by design:** `reporting.command_center_feed` and `reporting.reporting_event_inbox` (nullable = platform-wide projection; contextless Pub/Sub/scheduled writers would break under RLS); `fee.fee_bands` and `fee.fee_items` (global catalog, no per-tenant scope). See `docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md` §10 for the two-phase prod rollout, orphan/mis-scope pre-check queries, and per-service rollback migrations.
  - **Phase 1 BOLA gate — MT CI gate / SEC-P3-1 partial (2026-07-01):** an automated BOLA regression suite (`scripts/audit-tenant-isolation.ps1`) now runs in `whole-application-validation` CI as a required gate. The suite provisions a two-school seeded fixture (`ensure-app-rt-local.ps1` + `ensure-local-dev-users.ps1`), establishes a positive baseline (tenant-A admin can read tenant-A data), then probes cross-tenant access with a tenant-A token against tenant-B resources. Gateway runs in **enforce mode** (`docker-compose.bola.yml` overlay) for the gate so that token-injected `school_id` is authoritative. Wired into the verifier via `-RunBolaAudit` switch (`verify-microservice-migration.ps1`). **Honest coverage:** the gate carries real isolation **teeth** (a seeded school-B marker row that must never surface in a school-A response) on **students, catalog orders, firefighting requests, and workflow instances** — across detail-by-id and marker-list vectors. The **aggregate endpoints** (attendance daily-summary, fee report, catalog annual-plan, firefighting stats, workspace dashboard) are **advisory equivalence checks** only: they assert the cross-tenant `?schoolId` response is denied or identical to own-scope, but have no differential per-school seed data and therefore cannot prove isolation on their own (follow-up: seed differential per-school data to give them teeth). Global/non-tenant-scoped endpoints (e.g. `classes` — no `school_id`) are explicitly excluded. No cross-tenant leaks found on the teeth-backed probes.

---

# 8. Security Best Practices (Appended Review — OWASP Top 10 2025)

## 8.1 Posture Summary & OWASP Mapping

| OWASP 2025 | Status here | Evidence |
|---|---|---|
| **A01 Broken Access Control** | ❌ **Critical** | Client-supplied/optional `schoolId`; cross-tenant BOLA (see MT-P0-1) |
| A02 Cryptographic Failures | ⚠️ Partial | Cloud SQL at-rest + Cloud Run TLS by default; **field-level PII (Aadhaar) encryption posture unverified**; `:root` default creds in yml |
| A03 Injection | ✅ Mostly OK | Queries use **parameterized** `JdbcClient` named params; **but only 1/12 services use `@Valid`** (input validation weak) |
| A04 Insecure Design | ⚠️ | No per-tenant threat model; tenancy not designed deny-by-default |
| A05 Security Misconfiguration | ❌ | Gateway has **no CORS allowlist, no security headers, no body-size limit** |
| A06 Vulnerable Components | ⚠️ | **Spring Boot 3.4 EOL** (P0-1); image CVE scan is **gateway-only** |
| A07 Auth Failures | ⚠️ | JWT 15-min access + HttpOnly refresh cookie (good); **refresh rotation/reuse-detection unverified** |
| A08 Integrity Failures | ⚠️ | No SBOM; actions not SHA-pinned (P2-6) |
| A09 Logging/Monitoring | ⚠️ | Correlation-id + JSON logs + audit-service (good); **tenant-aware audit + log-redaction unverified** |
| A10 SSRF | ✅ Low | Gateway has fixed upstream allowlist + metadata-token minting |

Broken Access Control remains **#1** in OWASP Top 10 2025 ([owasp.org/Top10/2025/A01](https://owasp.org/Top10/2025/A01_2025-Broken_Access_Control/), [Cerbos](https://www.cerbos.dev/blog/broken-access-control-owasp-top-10-2025)), and **BOLA is the #1 API-specific risk** ([Wiz](https://www.wiz.io/academy/api-security/owasp-api-security)) — which is exactly this system's top gap.

## 8.2 Recommendations

### SEC-P0-1 🔗 — Tenant-scoped authorization: every access check verifies role **AND** tenant
- **Change:** Make authorization checks evaluate **both** the permission code (RBAC, already present) **and** the tenant scope (from the verified JWT, not the client). Deny by default. This is the same control as **MT-P0-1 + MT-P0-2** — implement them together (app-layer `TenantContext` + DB-layer RLS).
- **Rationale:** A role check without a tenant check is the canonical multi-tenant BOLA: "a single missing authorization check lets one tenant access another's records" ([Wiz](https://www.wiz.io/academy/api-security/owasp-api-security), [authgear](https://www.authgear.com/post/what-is-broken-access-control-vulnerability-and-how-to-prevent-it/)).
- **Impact:** **Critical** · **Effort:** Medium · **Risk:** Medium. **Highest-priority item in the entire review.**

### SEC-P0-2 — Gateway security controls: CORS allowlist, security headers, body limits, rate limiting
- **Change:** At the gateway add: strict **CORS allowlist** (no `*` with credentials), **security headers** (HSTS, `X-Content-Type-Options`, `X-Frame-Options`/`frame-ancestors`, a CSP for the SPA, `Referrer-Policy`), **max request-body size**, and **global + per-tenant rate limiting** (ties to MT-P2-2). Consider **Cloud Armor** in front for WAF/DoS.
- **Rationale:** OWASP **A05 Security Misconfiguration**; these are currently absent. Gateway-level enforcement is the right layer (single entry point).
- **Impact:** High · **Effort:** Low–Medium · **Risk:** Low.

### SEC-P1-1 — Refresh-token rotation + reuse detection
- **Change:** Issue **single-use refresh tokens**: on each refresh, invalidate the old token and issue a new one; track token **families** and, on presentation of an already-used token, **invalidate the whole family** (theft response). Keep access tokens 5–15 min (already ~15).
- **Rationale:** OWASP requires refresh tokens to be **rotated or sender-constrained**; reuse detection turns rotation into active theft detection ([Auth0 rotation](https://auth0.com/docs/secure/tokens/refresh-tokens/refresh-token-rotation), [Descope](https://www.descope.com/blog/post/refresh-token-rotation), [env.dev JWT](https://env.dev/guides/jwt-best-practices)).
- **Impact:** High · **Effort:** Medium (needs a server-side refresh-token store/family table) · **Risk:** Low–Medium.

### SEC-P1-2 — Input validation everywhere (DTOs + `@Valid`)
- **Change:** Replace `Map<String,Object>` request bodies with **typed DTOs annotated with Bean Validation** (`@Valid`, `@NotNull`, `@Size`, `@Email`, range checks); only 1/12 services validate today. Add output encoding for any user content reflected to the SPA.
- **Rationale:** OWASP **A03**; manual `Map` parsing is error-prone and bypasses validation. (Injection itself is largely mitigated by parameterized queries — keep that.)
- **Impact:** Medium–High · **Effort:** Medium · **Risk:** Low.

### SEC-P1-3 🔗 — PII & student-data compliance (DPDP / FERPA): field-level encryption, retention, consent
- **Change:** (a) Confirm/implement **field-level encryption** for sensitive identifiers (**Aadhaar** — `APP_AADHAR_SECRET` exists; verify it actually encrypts at the column level and that keys live in Secret Manager/KMS, not env). (b) Verify **encryption at rest** (Cloud SQL CMEK option) **and in transit** (enforce TLS on DB + Cloud Run). (c) Add **data-retention/erasure** and **parental-consent** handling. (d) **Data-minimization**: store Aadhaar only if legally necessary.
- **Rationale:** The system handles **children's data** (students) and **Aadhaar**. India's **DPDP Act** treats anyone **under 18 as a child**, requires **verifiable parental consent**, restricts behavioral profiling, and mandates strong encryption + access-audit logs ([ksandk](https://ksandk.com/data-protection-and-data-privacy/dpdp-compliance-for-schools-and-edtech/), [idfy](https://www.idfy.com/blog/protecting-minors-data-in-india-dpdp-edtech-privacy-compliance-practical-checklist/)). FERPA-style guidance mandates **encryption at rest and in transit** for student PII ([Virtru](https://www.virtru.com/blog/encryption-ferpa-compliance), [reform.app](https://www.reform.app/blog/ferpa-compliance-for-saas-tools-in-education)).
- **Impact:** **High (legal/regulatory)** · **Effort:** Medium–High · **Risk:** Medium. 🔗 RLS (MT-P0-2) is also a DPDP "access control" control.

### SEC-P1-4 — Supply-chain scanning on all images + CI security gates (extends P2-6)
- **Change:** Run **Trivy/Grype on every service image** (currently gateway-only), generate **SBOMs** (Syft), **SHA-pin** GitHub Actions, keep **Gitleaks** (already present) and add **secret-scanning as a required gate**, and add a **dependency-CVE gate** + the **Spring-Boot-supported-version gate** from §P2-6.
- **Rationale:** OWASP **A06/A08**; the EOL-Spring-Boot exposure would have been caught by a version gate. Secrets management is otherwise good (Secret Manager, single `db-password`, WIF — no static keys).
- **Impact:** Medium–High · **Effort:** Low–Medium · **Risk:** Low.

### SEC-P2-1 🔗 — Tenant-aware audit trail with sensitive-data redaction
- **Change:** Ensure every audit event (and structured log) carries **`tenant_id` + actor + action + outcome**, is **append-only**, and **never logs secrets/PII** (mask Aadhaar, tokens, passwords, full student records). Add log-field redaction at the logging layer. Make audit queries themselves tenant-scoped.
- **Rationale:** OWASP **A09** + DPDP's "audit data-access logs" requirement; audit logs that leak PII become their own breach.
- **Impact:** Medium · **Effort:** Low–Medium · **Risk:** Low.

### SEC-P2-2 — Remove dev-default credentials; fail fast
- **Change:** Drop the `:root`/`:postgres` **default fallbacks** in `application.yml` (`${SPRING_DATASOURCE_PASSWORD:root}`); require the env var and **fail to boot** if absent (you already do this for `APP_JWT_SECRET`/`APP_AADHAR_SECRET`).
- **Rationale:** OWASP **A02/A05**; default creds are a classic foot-gun if an env var is ever unset in a new environment.
- **Impact:** Low–Medium · **Effort:** Low · **Risk:** Low.

### SEC-P2-3 — Service-to-service authz hardening (defense-in-depth)
- **Change:** Keep the internal-token fail-closed model (good), but ensure backend services **independently re-validate tenant scope** (don't trust the gateway header blindly for privileged operations) and that internal tokens are **per-service, rotated**, and stored in Secret Manager (already are). Consider mTLS / signed internal claims.
- **Rationale:** Standard guidance: validate at the gateway **and** re-validate on the backend ([Wiz](https://www.wiz.io/academy/api-security/owasp-api-security)). Consolidation (P1-1) reduces the internal-call surface.
- **Impact:** Medium · **Effort:** Medium · **Risk:** Low.

### SEC-P3-1 — Threat model, pen-test, and abuse monitoring
- **Change:** Produce a per-tenant **threat model** (STRIDE), run an external **pen-test focused on tenant isolation/BOLA**, and add **anomaly/abuse monitoring** (cross-tenant access attempts → alert). Add **Cloud Armor WAF** if not adopted in SEC-P0-2.
- **Rationale:** OWASP **A04 Insecure Design**; isolation bugs are best caught by adversarial testing, and the BOLA suite (MT CI gate) should be complemented by manual testing.
- **Impact:** Medium · **Effort:** Medium · **Risk:** Low.

## 8.3 Security Recommendation-at-a-Glance

| ID | Recommendation | OWASP | Impact | Effort | Risk |
|---|---|---|---|---|---|
| **SEC-P0-1** 🔗 | Tenant-scoped authZ (role **AND** tenant from token) | A01 | Critical | Med | Med |
| **SEC-P0-2** | Gateway CORS/headers/body-limit/rate-limit | A05 | High | Low–Med | Low |
| **MT-P0-2 / SEC** 🔗 | RLS DB backstop (needs unprivileged role) | A01 | High | Med–High | Med |
| **SEC-P1-1** | Refresh rotation + reuse detection | A07 | High | Med | Low–Med |
| **SEC-P1-2** | DTO + `@Valid` input validation | A03 | Med–High | Med | Low |
| **SEC-P1-3** 🔗 | PII/Aadhaar encryption, DPDP/FERPA, retention/consent | A02 | High | Med–High | Med |
| **SEC-P1-4** | Image CVE scan all + SBOM + version/secret CI gates | A06/A08 | Med–High | Low–Med | Low |
| **SEC-P2-1** 🔗 | Tenant-aware audit + log redaction | A09 | Med | Low–Med | Low |
| **SEC-P2-2** | Remove `:root` default creds; fail fast | A02/A05 | Low–Med | Low | Low |
| **SEC-P2-3** | Backend re-validates tenant; rotate internal tokens | A01 | Med | Med | Low |
| **SEC-P3-1** | Threat model + BOLA pen-test + WAF/abuse monitoring | A04 | Med | Med | Low |

## 8.4 Where Multi-Tenancy and Security Intersect (explicit)

| Intersection | Tenancy item | Security item |
|---|---|---|
| **Tenant authZ from token, not client** | MT-P0-1 | SEC-P0-1 (A01/BOLA) |
| **RLS as DB-enforced isolation** | MT-P0-2 | A01 backstop + DPDP access-control |
| **Transaction-scoped tenant GUC + pooler** | MT-P1-3 | Prevents cross-tenant state leak |
| **Per-tenant rate limiting** | MT-P2-2 | DoS/abuse control (A05) |
| **Tenant-aware audit** | (registry/lifecycle) | SEC-P2-1 (A09) + DPDP audit logs |
| **Field-level PII encryption** | tenant data protection | SEC-P1-3 (A02, DPDP/FERPA) |

**The single most important action across both new sections:** implement **MT-P0-1 + MT-P0-2 + SEC-P0-1 together** — drive the tenant discriminator from the verified JWT, enforce it in the app layer, and back it with PostgreSQL RLS under an unprivileged runtime role. That one combined change closes a live cross-tenant student-data leak and the system's #1 OWASP risk simultaneously.

## 8.5 Additional Sources

- Multi-tenancy / RLS: [PlanetScale — tenancy in Postgres](https://planetscale.com/blog/approaches-to-tenancy-in-postgres) · [AWS — RLS data isolation](https://aws.amazon.com/blogs/database/multi-tenant-data-isolation-with-postgresql-row-level-security/) · [AWS Prescriptive Guidance — RLS recs](https://docs.aws.amazon.com/prescriptive-guidance/latest/saas-multitenant-managed-postgresql/rls.html) · [The Nile — RLS multi-tenant](https://www.thenile.dev/blog/multi-tenant-rls) · [Bytebase — RLS footguns](https://www.bytebase.com/blog/postgres-row-level-security-footguns/) · [techbuddies — RLS perf](https://www.techbuddies.io/2026/02/04/how-to-implement-postgresql-row-level-security-for-multi-tenant-saas-2/) · [dasroot — pattern comparison](https://dasroot.net/posts/2026/01/multi-tenancy-database-patterns-schema-database-row-level/)
- OWASP / access control: [OWASP Top 10 2025 — A01](https://owasp.org/Top10/2025/A01_2025-Broken_Access_Control/) · [Cerbos — A01 2025](https://www.cerbos.dev/blog/broken-access-control-owasp-top-10-2025) · [Auth0 — why BAC dominates](https://auth0.com/blog/why-broken-access-control-still-dominates-owasp-top-10/) · [Wiz — OWASP API security](https://www.wiz.io/academy/api-security/owasp-api-security)
- Tokens: [Auth0 — refresh token rotation](https://auth0.com/docs/secure/tokens/refresh-tokens/refresh-token-rotation) · [Descope — rotation guide](https://www.descope.com/blog/post/refresh-token-rotation) · [env.dev — JWT best practices](https://env.dev/guides/jwt-best-practices)
- Student-data compliance: [ksandk — DPDP for schools/EdTech](https://ksandk.com/data-protection-and-data-privacy/dpdp-compliance-for-schools-and-edtech/) · [idfy — protecting minors' data (DPDP)](https://www.idfy.com/blog/protecting-minors-data-in-india-dpdp-edtech-privacy-compliance-practical-checklist/) · [Virtru — FERPA encryption](https://www.virtru.com/blog/encryption-ferpa-compliance) · [reform.app — FERPA SaaS](https://www.reform.app/blog/ferpa-compliance-for-saas-tools-in-education)

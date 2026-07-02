# ADR-001 — Service Consolidation Topology (Phase 2, Task 2.1)

**Status:** Proposed (awaiting sign-off) · **Date:** 2026-07-02
**Context:** Remediation program Phase 2 — consolidate 12 nanoservices → ~5 domain services. Phase 0/1 (EOL upgrade, gateway hardening, `app_rt`/RLS/enforce, tenant isolation, validation) are complete and deployed to prod.

## Decision

Consolidate to **5 services** (chosen topology "A — aggressive co-location"):

| # | Service | Owns (schemas / domains) | Rationale |
|---|---|---|---|
| 1 | **identity** | `identity` (+ writes `audit` events) | Auth/JWT/RBAC is the security boundary; keep isolated. **No change.** |
| 2 | **school-core** | `tenant_school` + `student` + `attendance` + `fee` + `catalog` | These are the tightly-coupled operational core: attendance/fee/catalog all cross-read `student`+`tenant_school`, and student reads `tenant_school`. Co-locating turns every one of those cross-schema reads into an intra-service read. **Biggest merge.** |
| 3 | **operations** | `workflow` + `firefighting` | Both single-schema, no cross-reads; both are approval/procurement-workflow domains. **Safest merge — the proof-of-concept.** |
| 4 | **billing** | `billing` | Clean single-schema; superadmin invoices/sequences. **Stays standalone (no merge).** |
| 5 | **platform** | `reporting` + `notification` + `audit` | The async/projection + messaging + audit layer. `reporting` is the cross-schema aggregator (allowed to stay cross-schema; Phase 3 decouples it via outbox/events). `notification`/`audit` are clean. |

**Result:** 12 → 5. After this, the only service reading across schemas is **reporting (inside platform)** — meeting the Phase-2 acceptance criterion. Java 25 / Spring Boot 4.0 (Task 2.4) is a separate later spike; **this ADR is framework-neutral** (stays on Java 21 / SB 3.5.16).

## Coupling evidence (why this grouping)

Measured cross-schema reads on 2026-07-02:
- Clean (single-schema): `tenant_school`, `workflow`, `firefighting`, `notification`, `audit`, `billing`.
- `student` → reads `tenant_school`.
- `attendance`, `fee`, `catalog` → each reads `student` + `tenant_school`.
- `reporting` → reads all 8 (aggregator).
- `identity` → writes `audit` events (kept via event/API, not a merge).

Co-locating the `student`/`tenant_school` readers (attendance, fee, catalog) with their sources is the only way to hit "cross-schema → reporting-only" **within Phase 2** (Phase 3 does event-based decoupling for reporting).

## Migration mechanics (how each merge is done)

A merge combines N Spring Boot apps into one, preserving the public `/api/v1/**` contract exactly.

1. **Module & packages.** Create one Maven module `services/<group>-service` with a single `@SpringBootApplication` (component-scan `com.custoking.ims.<group>.*`). Move each source service's `api/`, `application/`, `persistence/`, `security/` under a domain sub-package (e.g. `com.custoking.ims.operations.workflow`, `…operations.firefighting`) — adjust `package` declarations; controllers, compat controllers, and permission/entitlement gates move **unchanged**.
2. **Flyway — one bean per schema (no renumbering, no baseline).** The merged service registers **multiple Flyway instances** (one per owned schema), each pointing at that schema's existing migration location + its **own history table in its own schema** (`<schema>.flyway_schema_history`). This preserves each domain's independent `V1..` sequence exactly as it runs today — prod already has these history tables, so nothing re-runs; new migrations continue their domain's sequence. Avoids the `V1__` filename collision that a single shared history would cause.
3. **Datasource & roles.** Single datasource per merged service, runtime role `app_rt` (RLS-subject), migration role `appuser` (owner) — unchanged from Phase 1. The `TenantAwareDataSource`/GUC/RLS wiring is copied once into the merged service and applies to all its schemas' RLS-enabled tables.
4. **Internal service tokens.** The merged service **accepts each domain's existing token** (e.g. `WORKFLOW_READ_TOKEN` and `FIREFIGHTING_READ_TOKEN`), fail-closed per Phase 0. The gateway keeps injecting the per-route token, so no token contract changes. (A later cleanup can unify tokens.)
5. **Gateway.** In `services/api-gateway/server.js`, the route **path prefixes are unchanged**; only the upstream target changes — the merged group's `*_UPSTREAM` envs all point at the one merged Cloud Run URL. `GATEWAY_AUTH_MODE=enforce` and header injection are unchanged.
6. **Deployment.** `cloudbuild.yaml`, `deploy.yml` (the `deploy_services` matrix), `docker-compose.yml`, and the Tiltfile lose the retired service names and gain the merged one. Cloud Run: deploy the merged service, repoint the gateway envs, then delete the retired services **after** verification.
7. **Cross-service calls become in-process.** Any current HTTP call between two now-merged domains (e.g. attendance→student) becomes a direct method/repository call within the merged service. Calls to services **outside** the group stay as HTTP/events.

## Merge order (lowest risk first — proves the pattern before the big one)

1. **operations = workflow + firefighting** — both single-schema, zero cross-reads → the safe proof-of-concept for the whole mechanics (Flyway-per-schema, gateway repoint, token accept-both, deploy/retire).
2. **platform = reporting + notification + audit** — reporting already reads everything (no *new* coupling); notification/audit clean.
3. **school-core = tenant_school + student + attendance + fee + catalog** — the large merge; done last, after the pattern is battle-tested. Collapses all the student/tenant_school cross-reads.

`identity` and `billing` stay standalone (no merge).

## Acceptance (per merge)
- `/api/v1/**` contract intact (gateway route smoke + feature smoke green).
- Per-merged-service `mvn test` green (all moved domains' tests pass under the one app).
- BOLA/tenant-isolation gate still green (RLS carried into the merged service for every RLS table).
- Boundary audit: no service except platform/reporting reads across schemas.
- Deployed + smoke green on prod before retiring the old services.

## Risks & mitigations
| Risk | Mitigation |
|---|---|
| Flyway `V1` collision across merged schemas | One Flyway bean **per schema** with its own history table (no shared history, no renumber). |
| RLS not carried into a moved table | Copy `TenantAwareDataSource`/GUC wiring once; verify every RLS table via the tenant-isolation gate post-merge. |
| Gateway contract drift | Route **prefixes unchanged**; only upstream target changes; gateway route smoke gates it. |
| Big-bang school-core | Do it **last**, after operations + platform prove the mechanics; its own per-merge plan. |
| Orphaned Cloud Run services / cost | Retire old services only **after** the merged one is verified live. |

## Out of scope (later phases/tasks)
- Task 2.3 (gateway local JWT verification), Task 2.4 (Java 25 / SB 4.0), Phase 3 (reporting outbox/event decoupling), Phase 4 (collapse deploy workflows).

## Next step
On sign-off: execute **merge #1 (operations = workflow + firefighting)** as its own branch → review → CI → merge → deploy + smoke, as the pattern-proving POC; then platform; then school-core — each with its own bite-sized plan.

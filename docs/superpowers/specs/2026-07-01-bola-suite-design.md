# Design — BOLA Regression Suite (Phase 1, Task 1.5)

> Review IDs: `MT` CI gate / `SEC-P3-1` (partial). Parent program plan: `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` (Task 1.5).
> Depends on: Task 1.2 `TenantContext` (server scopes from the authenticated JWT, not the client param — PR #12) and Task 1.3 RLS (DB backstop — PRs in 1.3/1.4/1.5-RLS). This is the **app-layer proof** that complements the RLS DB proof.
> Status: **approved design** → next step is the bite-sized implementation plan.

## Problem

Tenant isolation is now enforced two ways: application-level (`TenantContext`, Task 1.2) and database-level (RLS, Task 1.3 + extension). Neither is continuously *proven* against regressions. A new endpoint, a refactor that reintroduces an `if (schoolId != null)` client-trusting filter, or a controller that forgets to call `resolveSchoolId(...)` could silently reopen a Broken-Object-Level-Authorization (BOLA) hole. The Phase 1 gate requires an **automated test that, with a tenant-A token, asserts 403/empty for tenant-B objects on every covered list/detail endpoint**, wired into CI as a required gate.

## Goals / Non-goals

**Goals**
- A repeatable CI gate that drives the **running full split-service stack through the gateway** with two real school-admin tokens and proves tenant-A cannot read tenant-B data.
- Cover the two BOLA vectors: **cross-tenant list param** (`?schoolId=B` as admin-A) and **cross-tenant detail-by-id** (`GET /res/{B-id}` as admin-A).
- A **positive baseline** so the gate cannot false-pass on trivially-empty/broken endpoints.
- Curated, **easily-extensible** coverage; the covered/uncovered boundary is logged, never silently capped.
- Wired into `verify-microservice-migration.ps1` and `.github/workflows/ci.yml` as a **required** step.

**Non-goals (deferred / out of scope)**
- Exhaustive auto-discovery of every endpoint from gateway routes / OpenAPI (a much larger build; v1 is curated, extend-later).
- Write-path / mutation BOLA (cross-tenant POST/PATCH/DELETE) — v1 is read isolation; the RLS `WITH CHECK` proofs (Task 1.3) already cover cross-tenant writes at the DB layer. A follow-up may add write probes.
- Fuzzing / property-based id enumeration. v1 uses known seeded ids.
- Global/catalog-wide endpoints that are intentionally not tenant-scoped (e.g. `/api/v1/supply/catalog-categories`, `/api/v1/fee-structure`) — explicitly excluded from probes and listed as such.

## Decisions (resolved during brainstorming)

1. **Harness = PowerShell gate against the running full-stack**, reusing the login/`Invoke` pattern from `scripts/smoke-microservice-features.ps1` (gateway `POST /api/v1/auth/login` → bearer token; `Invoke-RestMethod` with `Authorization: Bearer`). Matches the repo's smoke-script convention and the tracker (`scripts/audit-tenant-isolation.ps1`). Proves the real end-to-end gateway + JWT + service path, not just controller units.
2. **Curated coverage, extend-later.** A flat probe manifest derived from the tenant-scoped endpoints already enumerated in `smoke-microservice-features.ps1`. New endpoints are one manifest line each.
3. **Seeded two-school fixture + both vectors + positive baseline.** Extend `scripts/ensure-local-dev-users.ps1` to seed school 1 AND school 2, each with its own admin and known objects. The gate asserts both BOLA vectors plus a positive baseline that distinguishes A from B (anti-false-green).

## Components

### 1. Two-tenant fixture — extend `scripts/ensure-local-dev-users.ps1`

Today the script seeds school `1` (`Local Demo School`) + one admin + the academic year. Extend it (idempotent `ON CONFLICT` upserts, same style) to also seed:

- **School `2`** (`Local Demo School Two`, short_code `LOCAL2`), reusing the existing academic year.
- **Two school admins**, password `password` (same bcrypt hash already in the script):
  - `admin1@demo.custoking.local` → admin role **scoped to school 1**.
  - `admin2@demo.custoking.local` → admin role **scoped to school 2**.
  Each must get the SAME role + scoped-assignment shape the gateway already relies on to inject `X-Authenticated-School-Id` (mirror the existing school-1 admin's `identity.app_users` row + role assignment + any `tenant_school` membership row — replicate exactly for school 2 so the minted JWT carries school 2 + admin permissions). This fidelity is the load-bearing part of the fixture.
- **Known per-school objects with fixed ids** for the detail-by-id probes (explicit `school_id`, NOT NULL after Task 1.4):
  - `student.students`: one row per school — e.g. id `9000001` (school 1), id `9000002` (school 2).
  - `catalog.catalog_orders`: one row per school with known ids (where the NOT NULL columns can be seeded deterministically).
  - `firefighting.firefighting_requests`: one row per school with known `code`/id.
  (Anchor on students; add the others only where deterministic SQL seeding is cheap. Domains without a seeded detail object are still covered by the list-param vector.)

The fixture writes a small **manifest of known ids** the gate reads (either emitted as a `$global`/JSON the gate sources, or the gate hard-codes the same fixed ids the seed uses — single source of truth: define the ids as constants shared by seed + gate).

### 2. The gate — `scripts/audit-tenant-isolation.ps1`

Parameters mirror the existing smokes: `-GatewayBaseUrl` (default `http://localhost`), the two admin emails/passwords, the superadmin creds.

Flow:
1. **Login** via the gateway → `tokenA` (school 1 admin), `tokenB` (school 2 admin), `superToken`. A login failure is an **infra/setup error** (distinct exit + message), never a silent pass.
2. **Positive baseline** (run first — establishes the probes have teeth):
   - `tokenA` `GET /res/{A-id}` → 200 with A's object; `tokenB` `GET /res/{B-id}` → 200 with B's object; assert `A-id != B-id`.
   - `superToken` sees both ids → 200 (superadmin bypass works).
   - A baseline failure fails the gate (the fixture or scoping is broken).
3. **Probes** — for each manifest entry, run as the cross-tenant actor (`tokenA` probing school 2):
   - **list** (`?schoolId=2`): response MUST be EITHER `403`/empty OR record-identical to `tokenA`'s own `?schoolId=1` response, AND MUST NOT contain school-2's known marker/id. (Server scopes to the authenticated school per Task 1.2; a response carrying B's rows is a leak.)
   - **detail** (`GET /res/{B-known-id}`): response MUST be `403` / `404` / empty — never `200` carrying B's object fields.
4. **Report + exit:** collect every failure with the endpoint, vector, and what leaked; print a summary; **exit non-zero** if any leak or baseline miss; exit 0 only when all probes isolated and all baselines passed.

### 3. Probe manifest (v1, curated)

A flat in-script list, each entry `{ key, method, path, type=list|detail, tenantScoped=true }`, derived from the tenant-scoped endpoints in `smoke-microservice-features.ps1`:

- **list / `?schoolId`**: `students`, `attendance/daily-summary`, `classes`, `classes/{id}/sections`, `fees/report`, `supply/orders`, `supply/annual-plan`, `workflows/pending`, `ff/requests`, `ff/requests/stats`, `dashboard`.
- **detail / by-id**: `students/{id}` (seeded, the anchor probe); plus `supply/orders/{id}` and `ff/requests/{id}` where a known id was seeded.
- **Excluded (logged as non-tenant-scoped, not silently dropped):** `supply/catalog-categories`, `fee-structure`, `students/import/template`, RBAC/zones/schools admin endpoints (superadmin-only, covered by their own `requireSuperAdmin`).

The script prints a **coverage summary** (probed vs known-excluded) so the boundary is explicit.

## Data flow

```
ensure-local-dev-users.ps1  → 2 schools, 2 scoped admins, known per-school objects
audit-tenant-isolation.ps1:
  login A / B / super (gateway POST /api/v1/auth/login)
  baseline: A sees A-id (200), B sees B-id (200), A-id≠B-id, super sees both
  for each probe (as tokenA against school 2):
     list   → assert 403/empty OR == own-scope, and no B marker
     detail → assert 403/404/empty
  any failure → print leak + exit 1   (CI gate red)
  all isolated → exit 0
```

## CI integration

- **`scripts/verify-microservice-migration.ps1`**: add a `-RunBolaAudit` switch (parallel to the existing `-RunDbAudit` / `-RunSmoke`) that seeds the fixture then invokes `audit-tenant-isolation.ps1`; non-zero exit fails the verifier.
- **`.github/workflows/ci.yml`** `microservice-runtime-test` job (already boots the `--profile full` stack): after the stack is healthy, run `ensure-local-dev-users.ps1` then `audit-tenant-isolation.ps1` as a **required** step (job fails on non-zero). This makes a tenant-isolation regression block merge.

## Testing strategy

- The gate **is** the test; validate it two ways:
  1. **Green run** against the current local full-stack (1.2 TenantContext is live) — all probes isolated, baselines pass.
  2. **Teeth check (negative control):** the positive baseline (A-id≠B-id, each visible only to its owner) proves the probes can distinguish tenants — a suite that "passes" because every endpoint returns empty fails the baseline. Optionally, a one-off manual check: point a `detail` probe at an A-owned id as `tokenA` and confirm it returns 200 (so the 403/404/empty assertion for B-ids is meaningful, not universal).
- Existing smokes/tests unaffected; this is additive tooling.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| **False-green from empty/broken endpoints** | Positive baseline asserts A sees A, B sees B, ids distinct, super sees both — empties fail the baseline |
| **admin-2 JWT doesn't carry school 2 / admin perms** (fixture fidelity) | Replicate the exact `app_users` + scoped role-assignment shape of the existing school-1 admin; verify via the baseline (tokenB must see B's object) |
| **Endpoint legitimately 403s on a mismatched `?schoolId` param** instead of silently scoping | Oracle accepts EITHER 403/empty OR own-scope-identical — both are non-leaking |
| **A global/catalog-wide endpoint flagged as a "leak"** | Manifest marks non-tenant-scoped endpoints excluded + logs them |
| **Seeding known detail ids across services is uneven** | Anchor detail probes on `students` (clean); other domains covered by the list-param vector; add detail probes as seeding allows |
| **Stack not healthy when the gate runs** | Gate fails fast with an infra/setup exit distinct from a leak; CI runs it only after health checks |
| **Coverage gaps read as "fully covered"** | Gate prints probed-vs-excluded coverage summary; manifest is the explicit boundary |

## Open items (deferred, not blocking)

- Write-path BOLA probes (cross-tenant POST/PATCH/DELETE) — follow-up; RLS `WITH CHECK` already backstops writes at the DB.
- Auto-discovery of endpoints from gateway routes — future, to grow coverage without manual manifest upkeep.
- Per-zone (sub-school) scoping probes — once zone-level scoping has explicit endpoints.

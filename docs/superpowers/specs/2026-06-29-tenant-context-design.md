# Design — `TenantContext`: Enforce Tenant Scope from Authenticated Context (Phase 1, Task 1.2)

> Review IDs: `MT-P0-1` / `SEC-P0-1` (OWASP A01). Source of truth: `ARCHITECTURE_REVIEW.md` §7.3.
> Parent program plan: `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md`.
> Depends on: Task 1.1 `app_rt` (PR #11) — this branch is stacked on it. RLS (Task 1.3) is the DB backstop that follows.
> Status: **approved design** → next step is the bite-sized implementation plan.

## Problem

The system is a shared-schema, discriminator-column multi-tenant app: domain rows carry
`school_id` (tenant) and sometimes `zone_id`, enforced **only in application code**. Today
that enforcement is **client-trusting and optional**:

- Controllers read the tenant key from a client-supplied `@RequestParam schoolId`, path
  variable, or `@RequestBody` Map — never from the authenticated identity.
- The API gateway already injects verified headers from the JWT on every upstream call
  (`X-Authenticated-User-Id/-Email/-Role/-School-Id/-Zone-Id`, see `services/api-gateway/server.js`),
  **but the services ignore them.**
- A tenant-A user can pass `schoolId=B` and read/write tenant B's data (a textbook BOLA /
  IDOR). Worse, several endpoints treat `schoolId == null` as "all schools," and
  `reporting-service` reads a `superAdmin` boolean **straight from the request body**
  (`ReportingReadController` / `ReportingCommandRepository.requireActionAccess`) — client
  self-elevation.

This is the #1 security vulnerability in the system: a live cross-tenant student-PII
exposure. This task closes it at the application layer; Task 1.3 (RLS) adds the
database-enforced backstop.

## Goals / Non-goals

**Goals**
- Derive tenant scope from the **gateway-verified** authenticated context, never from
  client params. Deny-by-default.
- A request-scoped `TenantContext` (filter → controller) in every service, populated from
  the `X-Authenticated-*` headers.
- A single resolution rule, `resolveSchoolId(requested)`, that returns the authorized
  school or throws 403; only **superadmin** may widen scope.
- Apply enforcement to all **7 tenant-scoped services**; apply a deny-cross-tenant policy
  to the **5 platform services**.
- Per-service tests proving cross-tenant access is denied.

**Non-goals (handled elsewhere / deferred)**
- RLS + the `app.current_school_id` GUC — Task 1.3 (backstop).
- The repo-wide automated **BOLA regression CI suite** — Task 1.5.
- **Full zone-admin multi-school scoping** — deferred (see Decisions). Zone admins without
  an authenticated school get no cross-school access in this increment.
- Permission-code authorization changes (RBAC) — unchanged; this task is about tenant
  *scope*, not permission gating.
- A shared `common` Maven module — explicitly not introduced (see Decisions).

## Decisions (resolved during brainstorming)

1. **Code sharing: copy-per-service.** No `services/common` module exists and there is no
   root pom (services build independently). The `TenantContext` + filter (~3 small files)
   are copied into each service, mirroring the existing per-service `RequestCorrelationFilter`.
   Acceptable minor duplication; Phase 2 consolidation will dedupe.
2. **Scope: all 7 tenant-scoped services** (`student, attendance, fee, catalog, workflow,
   firefighting, reporting`) get full enforcement; the 5 platform services (`identity,
   tenant-school, billing, audit, notification`) get the filter + targeted deny-cross-tenant
   guards.
3. **Strict single-school now; defer full zone-admin.** Non-superadmin requests are locked
   to their authenticated `schoolId`. Superadmin widens. Zone admins (authenticated school
   absent, zone present) get **no** cross-school access this increment — a known, accepted
   limitation closed by a later task (zone→school resolution).
4. **Enforcement mechanism: controller-boundary resolution** via `TenantContext` (approach
   A). Repos are largely unchanged (they keep their `schoolId` parameter); controllers feed
   them the *resolved* school. Explicit and unit-testable.
5. **Superadmin signal: the gateway-verified `X-Authenticated-Role == SUPERADMIN`** (case-
   insensitive), used as the explicit scope-widening check the review mandates. Rationale:
   the role here comes from the verified JWT (not the client), and this is a platform-scope
   decision, not business-logic permission gating. (A forwarded permission/claim is a
   cleaner future signal; noted as an improvement, out of scope here.)

## The security invariant

> For any request that is not superadmin, the effective `school_id` used by every
> tenant-scoped query equals the gateway-authenticated `X-Authenticated-School-Id`. A
> client-supplied `schoolId` may only narrow within that scope; it can never widen it.

## Components (copied into each service)

Package: `com.custoking.ims.<service>.security` (alongside existing `config`/`security`).

### 1. `TenantContext` (request-scoped, thread-local holder)

Immutable value object + a thread-local holder, mirroring how `RequestCorrelationFilter`
uses MDC. Fields: `userId` (Long, nullable), `email` (String, nullable), `role` (String,
nullable), `schoolId` (Long, nullable), `zoneId` (Long, nullable).

Derived:
- `isSuperAdmin()` → `role != null && role.equalsIgnoreCase("SUPERADMIN")`.
- `isAuthenticated()` → any of userId/role present (i.e. gateway forwarded a user).

Holder API:
- `TenantContext.set(ctx)` / `TenantContext.get()` / `TenantContext.clear()` (ThreadLocal).
- `get()` returns an empty context (all-null) when nothing was set (system/internal calls).

### 2. `TenantContextFilter` (`OncePerRequestFilter`)

- Ordered immediately **after** `RequestCorrelationFilter`.
- Reads `X-Authenticated-User-Id/-Email/-Role/-School-Id/-Zone-Id`; parses ids as Long
  (blank/absent → null).
- `TenantContext.set(...)` at entry; `TenantContext.clear()` in `finally` (no leak across
  pooled/virtual threads).

### 3. `TenantScope` resolution (static helper or methods on `TenantContext`)

```
Long resolveSchoolId(Long requested):
  ctx = TenantContext.get()
  if ctx.isSuperAdmin():            return requested          // may be null = platform-wide
  if ctx.schoolId == null:          throw 403                 // non-superadmin w/o a school (deferred zone-admin)
  if requested != null && !requested.equals(ctx.schoolId): throw 403   // cross-tenant attempt
  return ctx.schoolId                                          // locked to authenticated school
```

- `requireSuperAdmin()` → throws 403 unless `isSuperAdmin()` (for platform-only endpoints).
- 403 is raised as `ResponseStatusException(FORBIDDEN, ...)` (the pattern already used in
  the compat controllers).

## Per-service changes

### Tenant-scoped services (full enforcement)

For each of `student, attendance, fee, catalog, workflow, firefighting, reporting`:
- Add the 3 components above.
- In every controller endpoint that previously took a client `schoolId` (`@RequestParam`,
  path var, or body Map) for **scoping**, replace the raw value with
  `resolveSchoolId(requestedSchoolId)` and pass the result to the (unchanged) repository.
- Eliminate every `schoolId == null ⇒ all rows` path for non-superadmin: after resolution,
  a non-superadmin always has a concrete `schoolId`; only superadmin yields `null` (all).
- **reporting-service** specifically: remove the `superAdmin` boolean read from the request
  body and the client `actorSchoolId`; use `TenantContext.isSuperAdmin()` and
  `resolveSchoolId(...)` in `acceptAction`/`dismissAction` and `requireActionAccess`.
- Keep existing internal service-token checks (`X-…-Service-Token`) — unchanged; the tenant
  filter is additive defense.

Representative call-site counts (from survey): student ~14, reporting ~17, fee ~6,
catalog ~6, attendance ~5, workflow ~3, firefighting ~3.

### Platform services (targeted deny-cross-tenant)

Install the filter (so `isSuperAdmin()`/context is available), then:
- **identity-service:** user-directory / RBAC reads that accept `branchId`/`schoolId`/`zoneId`
  filters from an end user → apply `resolveSchoolId(...)` (a school admin may only see their
  own school's users); platform-wide listing requires `isSuperAdmin()`.
- **audit-service:** the read/query endpoint scoped by `schoolId` → `resolveSchoolId(...)`
  so a school admin reads only their own audit rows; full export requires `isSuperAdmin()`.
- **tenant-school-service:** section/class/staff reads that accept `schoolId` →
  `resolveSchoolId(...)`; registry-wide reads (`/zones`, `/sa/schools`, school list) and
  registry mutations require `isSuperAdmin()`.
- **billing-service:** `/api/v1/...sa/...` invoice endpoints → `requireSuperAdmin()`
  (these are Custoking↔school B2B, superadmin-only).
- **notification-service:** delivery/broadcast infra reached via service token; no per-school
  end-user data exposure — install the filter for consistency, require `isSuperAdmin()` on
  any cross-tenant broadcast/admin read; otherwise no change.

## Data flow

```
Browser → gateway (verifies JWT, injects X-Authenticated-* headers)
        → service: RequestCorrelationFilter → TenantContextFilter (sets TenantContext)
        → controller: effective = resolveSchoolId(clientRequested)   // 403 if cross-tenant
        → repository(effective)   // never the raw client value
        → finally: TenantContext.clear()
```

System/internal call (service-to-service, valid service token, **no** `X-Authenticated-*`):
`TenantContext` is empty → not superadmin, schoolId null. Such calls must pass an explicit
`schoolId` and are trusted (the calling service owns correctness); they do **not** route
through `resolveSchoolId` (which would 403). Endpoints reachable both ways document which
path applies. (Services are private/IAM+token-gated; only the gateway injects user headers,
so absence of those headers reliably indicates an internal call.)

## Testing strategy

- **Per-service controller unit tests** (Mockito + standalone MockMvc, matching existing
  `*ControllerTest` style) for each tenant-scoped service:
  - tenant-A context (`X-Authenticated-School-Id=A`) requesting school B → **403** (or empty
    where the endpoint is a list).
  - tenant-A context omitting `schoolId` → results scoped to A only (repo invoked with A).
  - superadmin context → cross-tenant/platform-wide succeeds (repo invoked with requested/null).
- **`TenantContextFilter` / `resolveSchoolId` unit tests** (pure): header parsing; the three
  resolution branches; superadmin widening; `clear()` always runs.
- **Platform-service guard tests**: non-superadmin cross-school read → 403; superadmin → ok.
- Existing service unit tests stay green (`scripts/invoke-microservice-tests.ps1`).
- The automated repo-wide BOLA suite + CI gate is **Task 1.5** (out of scope here).

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| A tenant-scoped query path is missed | RLS backstop (1.3) + BOLA suite (1.5) catch app-layer misses; this is the explicit defense-in-depth pairing |
| Over-blocking internal service-to-service calls (no user headers) | System-context rule: token-authed calls without `X-Authenticated-*` bypass `resolveSchoolId` and pass explicit `schoolId` |
| Zone admins lose cross-school reach | Accepted, documented deferral (Decision 3); superadmin path unaffected; follow-up task adds zone→school resolution |
| ThreadLocal leak across pooled/virtual threads | `clear()` in `finally` (same discipline as MDC in `RequestCorrelationFilter`) |
| Superadmin detection via role header | Role is gateway-verified (JWT-sourced), used only for platform scope-widening; documented; cleaner claim is a future improvement |
| Public/auth endpoints (login/refresh) have no context | They never call `resolveSchoolId`; unaffected |

## Open items (deferred, not blocking this task)

- Full zone-admin multi-school scoping (zone→school resolution).
- Forwarding a permission/claim (instead of the role string) from the gateway as the
  superadmin signal.
- The automated BOLA regression CI gate (Task 1.5).

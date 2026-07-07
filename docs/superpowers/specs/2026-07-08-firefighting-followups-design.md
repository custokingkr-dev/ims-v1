# Firefighting Follow-ups — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** operations-service (new cross-service client + entitlement guard), frontend (two cosmetics).

Three follow-ups from the firefighting audit: two trivial FE cosmetics + backend FIREFIGHTING module-entitlement enforcement in operations-service (the "module entitlement only" proportionate scope; per-user permission propagation and separation-of-duties roles remain deferred).

---

## A — Dedup firefighting timeline helpers (frontend)

**Problem:** `FfTrackItem` (interface), `formatFfDate`, and `labelFfStatus` are duplicated verbatim in `FirefightingDashboardPanel.tsx` and `FirefightingOrdersPanel.tsx` (and `formatFfDate` is used in `FirefightingApprovalsPanel.tsx`).

**Design:** create `frontend/src/pages/workspace/panels/ffUtils.ts` exporting the `FfTrackItem` type + `formatFfDate` + `labelFfStatus` (the existing implementations, verbatim). Import from it in the Dashboard, Orders, and Approvals panels; remove the local copies. No behavior change.

---

## B — Nav module-entitlement live-refresh (frontend)

**Problem:** the `activeModules` fetch in `UnifiedWorkspacePage.tsx` keys on `[user?.branchId, isPlatformAdmin]`, so a mid-session entitlement change (e.g. superadmin grants FIREFIGHTING) isn't reflected until a full reload.

**Design:** thread the workspace's existing refresh signal into the `activeModules` fetch effect so it re-fetches when the workspace refreshes. Concretely: the page already has a refresh mechanism (`refresh()` / a refresh nonce driving `workspace` reloads); add that nonce to the effect's dependency array (or call the modules fetch from the same refresh path). Preserve the fail-open sentinel behavior (`Set<string> | null`, null = show all). No new endpoint.

---

## C — Operations FIREFIGHTING module-entitlement enforcement (backend)

**Problem:** operations-service firefighting endpoints gate only on the internal service token + tenant scope; they never check whether the school has the FIREFIGHTING module entitlement. The FE nav gate (shipped) hides the module for ungranted schools, but a gateway-authenticated user at a non-entitled school could still drive `/api/v1/ff/**` via the API. Module entitlements live only in school-core (`ModuleEntitlementReadRepository`, tenant_school schema); operations has **no** cross-service HTTP client today.

### C1 — `ModuleEntitlementClient` (new, mirrors identity's `TenantSchoolClient`)
A Spring `RestClient` component in operations-service that calls school-core `GET /api/v1/schools/{id}/modules/active` and returns the set of active module codes for a school. Mirror `identity-service/.../infrastructure/TenantSchoolClient` exactly for the cross-service concerns:
- Header `X-Tenant-School-Token` = the internal tenant-school read token; forward the authenticated-context headers if needed.
- **Cloud Run OIDC** identity token (audience = the peer base URL), `auto` mode (activates only for `*.run.app`), minted from the metadata server — so the private school-core peer accepts the call in prod.
- Config: `operations.tenant-school.base-url` / `operations.tenant-school.token` / `operations.tenant-school.cloud-run-auth` (default `auto`) + connect/read timeouts.
- **Response shape:** `/schools/{id}/modules/active` returns a list of `ModuleEntitlementRow` with a `moduleCode` field; the client extracts an uppercased `Set<String>` of module codes.
- **Cache:** a small per-school TTL cache (e.g. 60s) so entitlement isn't re-fetched on every firefighting request. Keep it simple (a `ConcurrentHashMap<Long, CachedEntry>` with expiry, or Caffeine if already on the classpath).
- **Fail-open on lookup error:** if the client call fails (peer down, timeout, non-2xx), the guard allows the request (logs a warning) — this is defense-in-depth behind the FE gate; availability of firefighting must not depend on a live school-core call succeeding. When the lookup succeeds and FIREFIGHTING is absent, the guard blocks.

### C2 — The entitlement guard
Enforce on the firefighting request surface — the `/api/v1/ff/**` paths and the `/api/v1/workspace/firefighting` compat path. Preferred: a `HandlerInterceptor` (registered via a `WebMvcConfigurer` for those path patterns) that:
- **Bypasses** superadmin (`TenantContext.get().isSuperAdmin()`), and bypasses when the authenticated `TenantContext.get().schoolId()` is null (no school scope → nothing to check; superadmin/platform).
- Otherwise checks `moduleEntitlementClient.activeModules(schoolId).contains("FIREFIGHTING")`; if the lookup succeeded and the code is absent → **403** ("This school does not have the Urgent Procurement module enabled"). On lookup failure → allow (fail-open, logged).
- Runs after the existing `TenantContextFilter` (so `TenantContext` is populated) and before the controller.

*(Alternative if an interceptor is awkward in this service's setup: a `requireFirefightingModule()` guard method called at the top of each firefighting read/write endpoint. The plan picks whichever fits the service's existing structure; the interceptor is preferred for single-point coverage.)*

### C3 — Config & deploy
- `application.yml` (operations): add `operations.tenant-school.base-url` / `.token` / `.cloud-run-auth` (empty defaults; when unset the guard fails-open / is inert, so local/dev without the peer configured doesn't break firefighting).
- `cloudbuild.yaml`: the operations `deploy_service` gets `TENANT_SCHOOL_URL` (resolved the same way other services resolve peer URLs) + the tenant-school read token secret, so the client is configured in the deployed env. Mirror how platform-service's deploy passes `SCHOOL_CORE_URL` + `CATALOG_READ_TOKEN`.

### Error handling
| Condition | HTTP | Message |
|---|---|---|
| non-superadmin at a school without FIREFIGHTING hits `/ff/**` | 403 | "This school does not have the Urgent Procurement module enabled" |
| entitlement lookup fails (peer down/timeout) | (allow) | request proceeds; a warning is logged (fail-open) |
| superadmin / null-school | (allow) | bypass |

### Testing
- **operations unit tests** (mock `ModuleEntitlementClient`): the guard returns 403 when the client reports FIREFIGHTING absent for the school; allows when present; allows for superadmin; allows (fail-open) when the client throws.
- The `ModuleEntitlementClient` itself: a focused test of the code-set extraction + cache behavior (mock the RestClient or use a stubbed response), mirroring how `TenantSchoolClient`/`ApprovalCommandClient` are unit-tested.
- **FE:** no tests (convention) — `npm run build` clean for A + B.

---

## Files (indicative — plan pins exact lines)
**frontend**
- Create: `src/pages/workspace/panels/ffUtils.ts` (A)
- Modify: `FirefightingDashboardPanel.tsx`, `FirefightingOrdersPanel.tsx`, `FirefightingApprovalsPanel.tsx` (A — import shared helpers)
- Modify: `UnifiedWorkspacePage.tsx` (B — refresh the modules fetch)

**operations-service**
- Create: `.../infrastructure/ModuleEntitlementClient.java` (C1)
- Create: the entitlement guard (`HandlerInterceptor` + `WebMvcConfigurer`, or a controller guard helper) (C2)
- Modify: `src/main/resources/application.yml` (C3 config)
- Test: `.../infrastructure/ModuleEntitlementClientTest.java` + the guard test

**deploy**
- Modify: `cloudbuild.yaml` (operations `deploy_service` env: `TENANT_SCHOOL_URL` + tenant-school token) (C3)

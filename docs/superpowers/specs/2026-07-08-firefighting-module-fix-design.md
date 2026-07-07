# Firefighting ("Urgent Procurement") Module Fix — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** operations-service (firefighting schema), platform-service (reporting approvals), frontend, and a small modules-exposure touch (identity or school-core).

Fixes the end-to-end audit of the Firefighting module. Today only **New Request** works; **Request Pipeline**, **Approvals**, and **Placed Orders** are broken (lists never load; the custoking approval stage is unreachable so every request stalls at `APPROVED`).

## Decisions (settled during brainstorming)
- **Scope:** everything in the audit — Criticals, Important (incl. vendor-paid guard, RBAC, module entitlement), and Minors.
- **Canonical "awaiting custoking" status = `APPROVED`** (minimal; no migration — existing rows are already `APPROVED`).
- **Part D (module entitlement + RBAC) is proportionate:** FE-level gating now; deep cross-service entitlement plumbing and true separation-of-duties role modeling are explicitly **deferred** (noted below), because operations' `TenantContext` carries **role only** (no permission codes) and the FE has **no module list** today.

---

## Part A — Make the four features load & complete the workflow (Critical)

### A1 — Lists never load (data plumbing)
**Root cause:** the three list panels receive `workspace?.firefighting?.requests ?? []` (UnifiedWorkspacePage.tsx:521/531/538), but platform `ReportingPublicCompatibilityController.workspace()` hardcodes `firefighting.requests = List.of()` (a decoupling artifact — platform can't read operations data). Even the superadmin self-fetch of `GET /ff/requests` fails: the backend returns a **bare `List`** but the FE reads `res.data.content`, and the FE sends `size` while the backend only reads `limit` (default 100).

**Fix:** the three panels **self-fetch from operations** via the gateway (`/api/v1/ff/` → operations), decoupled from the platform workspace bundle:
- `FirefightingDashboardPanel` (Pipeline) and `FirefightingOrdersPanel` (Placed orders): fetch `GET /api/v1/ff/requests` (optionally `?status=` / `?limit=`), read the **bare array** (`Array.isArray(res.data) ? res.data : []`), send **`limit`** not `size`.
- `FirefightingApprovalsPanel`: fetch `GET /api/v1/ff/requests/pending-approvals` (bare array), after the A2 filter fix.
- `UnifiedWorkspacePage`: stop passing `workspace.firefighting.requests`; the panels own their fetch (mirrors the earlier student-review-drawer repoint). Add an `onRefresh`/reload after mutations so approving/rejecting refreshes the list.
- Leave `workspace()`'s empty firefighting bundle as-is (now unread) — do not attempt to populate it cross-service.

### A2 — Custoking approval stage is dead (canonical status)
**Root cause:** operations writes `APPROVED` after principal-approval, but every approver-facing filter looks for the never-written `AWAITING_CUSTOKING`, so an `APPROVED` request appears in **no** approval inbox and `approve-custoking`/`fulfill` are unreachable.

**Fix — standardize on `APPROVED`:**
- operations `FirefightingReadRepository` `pending()` query: `AWAITING_CUSTOKING` → `APPROVED` in the `status IN (…)` list.
- platform `ReportingApprovalRepository`: the firefighting-approvals list `WHERE ff.status IN (…)` (:98), the `decideFirefighting` allowed-set guard (:142), and the custoking `case "AWAITING_CUSTOKING"` → all use `APPROVED`. `firefightingRequestType` label for the `APPROVED` stage reads "Custoking approval".
- FE `FirefightingApprovalsPanel`: add the **custoking stage** — for a request in `APPROVED` status, show an **"Approve (Custoking)"** action (superadmin only) that calls `POST /api/v1/ff/requests/{code}/approve-custoking`; keep the reject action. (The panel's stage list must include `APPROVED` between `AWAITING_PRINCIPAL` and `FULFILLED`.)
- Fix the masking test `ReportingApprovalRepositoryTest` (:156): seed `APPROVED` (not the impossible `AWAITING_CUSTOKING`) and assert `approveFirefightingCustoking` is invoked.
- `decideFirefighting`'s status→endpoint mapping stays: `AWAITING_BURSAR`→bursar, `AWAITING_PRINCIPAL`→principal, **`APPROVED`→custoking**, else reject.

---

## Part B — Workflow safety (Important)

### B1 — `vendor-paid` has no status precondition
**Root cause:** `FirefightingReadRepository.markVendorPaid` never checks status, so a `DRAFT`/`AWAITING_*`/`REJECTED` request can be marked vendor-paid, corrupting Placed-Orders / vendor-dues accounting.

**Fix:** require the request be `CUSTOKING_APPROVED` or `FULFILLED` before marking vendor-paid (`requireStatus`-style check → `IllegalStateException` → 400).

---

## Part C — Minors

- **C-Timeline shape:** the timeline modal reads `{state, title, meta, note}` but the backend returns `{status, at}`. Align them — simplest: the FE reads `{status, at}` and renders a label from `status` + formatted `at`.
- **C-Timeline events:** add `custoking_approved_at`, `fulfilled_at`, `rejected_at` columns (operations firefighting migration, next free version), set them on the respective transitions, and include `CUSTOKING_APPROVED`/`FULFILLED`/`REJECTED` events in `timeline()`.
- **C-Orders date:** the Placed-orders "Date" column reads `row.date`, which the list DTO never provides — read `row.createdAt` (or alias it) instead.
- **C-custokingCriteria:** `detail()` hardcodes `custokingCriteria = Map.of()`; parse and surface the stored `custoking_criteria_json`.
- **C-decideFirefighting return:** it returns `status:"APPROVED"` for every approve; return the actual post-transition status (or omit — the FE refetches). Low priority.

---

## Part D — Entitlement & RBAC (proportionate; deep parts deferred)

### D1 — FIREFIGHTING module entitlement (proportionate = FE nav gating)
**Reality:** module entitlements live only in school-core (`ModuleEntitlementReadRepository`, tenant_school); operations can't see them and the FE has no module list. The nav config already carries `module: 'FIREFIGHTING'` on the ff items but nothing consumes it.

**Fix (proportionate):**
- Expose the authenticated school's **enabled module codes** to the FE. Add them to an endpoint the FE already calls at load — preferred: the `GET /api/v1/auth/me` response (identity-service), populated from the school's entitlements; if identity can't cheaply resolve modules, expose a small `GET /api/v1/schools/{id}/modules` (school-core, reading `ModuleEntitlementReadRepository`) the FE calls once. The plan will pick whichever is the smaller, existing-pattern touch.
- Filter the workspace nav: hide any nav item whose `module` code is not in the school's enabled set (superadmin/platform bypass). This hides the ff panels for schools without FIREFIGHTING.
- **Deferred (own spec):** backend module-entitlement enforcement inside operations-service (requires cross-service entitlement propagation) — the FE gate is the proportionate hardening; a determined caller with the internal token is still gated by the existing service-token + tenant scope.

### D2 — RBAC on approvals (proportionate = FE permission gating)
**Reality:** operations' `TenantContext` carries **role only**, no permission codes, so operations can't enforce per-user permission codes without propagation work; and there are no distinct bursar/principal roles to enforce separation of duties.

**Fix (proportionate):**
- FE gates the approve/reject actions on the user's permission via `usePermissions().can('firefighting:write')` (the FE already loads permission codes from `/auth/me`), so only authorized users see/trigger approvals. Custoking stays superadmin-only (backend `requireSuperAdmin()`, already enforced).
- **Deferred (own spec):** backend per-user permission enforcement in operations (needs permission-code propagation into `TenantContext`) and true separation-of-duties (bursar ≠ principal) which requires modeling bursar/principal roles. Documented as a follow-up.

---

## Error handling
| Condition | HTTP | Message |
|---|---|---|
| vendor-paid on a pre-approval request | 400 | "Only approved/fulfilled requests can be marked vendor-paid" |
| approve-custoking on a non-`APPROVED` request | 400 | existing `requireStatus` message |
| stale-projection wrong-stage approve (superadmin path) | 409 | existing (retryable; operations `requireStatus` is the safety net) |

## Testing
- **operations:** integration tests — the full chain `DRAFT→…→APPROVED→CUSTOKING_APPROVED→FULFILLED` reachable; `pending()` returns an `APPROVED` request (A2); `vendor-paid` rejected pre-approval (B1); the new timeline timestamps set on transition (C).
- **platform:** `ReportingApprovalRepositoryTest` — seed `APPROVED`, assert the approvals list surfaces it and `decideFirefighting` routes to `approve-custoking` (A2).
- **FE:** no tests (repo convention) — verify with `npm run build`; manual acceptance that the four panels load real requests and the New→Pipeline→Approvals(incl. custoking)→Placed lifecycle is observable.

## Files (indicative — plan pins exact lines)
**operations-service:** `persistence/FirefightingReadRepository.java` (pending filter, vendor-paid guard, timeline events, custokingCriteria, transition timestamps), `api/FirefightingReadController.java` (if needed), a new firefighting migration (timeline timestamp columns), tests.
**platform-service:** `persistence/ReportingApprovalRepository.java` (APPROVED filters + guard + case + label), `ReportingApprovalRepositoryTest.java`.
**identity- or school-core-service:** modules exposure (`/auth/me` or `/schools/{id}/modules`).
**frontend:** `FirefightingDashboardPanel.tsx`, `FirefightingApprovalsPanel.tsx`, `FirefightingOrdersPanel.tsx` (self-fetch + custoking stage + permission gating), `UnifiedWorkspacePage.tsx` (drop the workspace-bundle props), the nav filter (module gating), `services` (ff api calls), timeline modal shape.

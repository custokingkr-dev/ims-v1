# Firefighting Module Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all four Firefighting ("Urgent Procurement") features work end-to-end — lists load, the custoking approval stage is reachable, and the New→Pipeline→Approvals→Placed lifecycle completes.

**Architecture:** Backend correctness fixes in operations-service (state machine, vendor-paid guard, timeline) + platform-service (approvals-list canonical status). Frontend repoint so the panels self-fetch operations data (the platform workspace bundle hardcodes firefighting=[]), add the custoking approval UI, and gate the nav on module entitlement + approvals on permission.

**Tech Stack:** Spring Boot 4.0.7 / Java 25 (`JdbcClient`, `@Transactional`, Flyway per-schema), React 18 + Vite + TS. Backend build/test (Windows Bash tool): `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/<svc>/pom.xml -q -Dtest=<T> test`; frontend `cd frontend && npm run build`.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-08-firefighting-module-fix-design.md`.
- **Canonical "awaiting custoking" status is `APPROVED`** (operations already writes it after principal-approval; no migration/backfill for status). Every approver-facing filter must use `APPROVED`, NOT the never-written `AWAITING_CUSTOKING`.
- Full status machine: `DRAFT → AWAITING_BURSAR → AWAITING_PRINCIPAL → APPROVED → CUSTOKING_APPROVED → FULFILLED` (+ `REJECTED`). Custoking approve requires `APPROVED`; fulfill requires `CUSTOKING_APPROVED`.
- The operations `GET /api/v1/ff/requests` and `/ff/requests/pending-approvals` return a **bare JSON array** and read a **`limit`** query param (default 100, capped 500) — NOT a `{content}` envelope and NOT `size`. The FE must match.
- FE `api` base is `/api/v1`. Gateway routes `/api/v1/ff/` → operations, `/api/v1/schools/` → school-core.
- operations `TenantContext` has role only (no permission codes) — backend RBAC can check role but not per-user permission codes; FE uses `usePermissions().can(code)`.
- Reuse existing style classes; no new design system. No FE tests (repo convention) — verify FE with `npm run build`. Backend gets tests (TDD).
- Deferred (do NOT build): backend cross-service module-entitlement enforcement in operations; per-user permission propagation into operations `TenantContext`; separation-of-duties roles. FE-level gating only for Part D.

---

### Task 1: operations-service — state machine, vendor-paid guard, timeline (Parts A2-ops, B, C-backend)

**Files:**
- Modify: `services/operations-service/.../persistence/FirefightingReadRepository.java`
- Create: `services/operations-service/src/main/resources/db/migration/firefighting/V…__request_lifecycle_timestamps.sql` (next free version)
- Test: `services/operations-service/.../persistence/FirefightingReadRepositoryIntegrationTest.java` (or the existing firefighting integration test if present; else create mirroring another operations integration test's Testcontainers harness)

**Interfaces:**
- Produces: `pending()` returns `APPROVED` requests; `markVendorPaid` rejects pre-`CUSTOKING_APPROVED`; `approveCustoking`/`fulfill`/`reject` stamp `custoking_approved_at`/`fulfilled_at`/`rejected_at`; `timeline()` includes those events; `detail()` surfaces `custokingCriteria`.

- [ ] **Step 1: Write the failing tests**

Extend/create the firefighting integration test. Seed a request and drive it through the chain (or seed rows at target statuses via SQL / repo methods). Assert:
```java
// A2: pending() surfaces an APPROVED (awaiting-custoking) request
// (seed a request, submit → approve-bursar → approve-principal so status='APPROVED')
assertThat(repo.pending(schoolId, 100)).anySatisfy(r -> assertThat(r.status()).isEqualTo("APPROVED"));

// B1: markVendorPaid rejected before CUSTOKING_APPROVED/FULFILLED
// (seed a DRAFT/APPROVED request)
assertThatThrownBy(() -> repo.markVendorPaid(draftCode, Map.of()))
    .isInstanceOf(IllegalStateException.class);
// allowed once CUSTOKING_APPROVED
repo.markVendorPaid(custokingApprovedCode, Map.of("paidBy", 1));  // no throw

// C timeline: custoking/fulfilled/rejected events appear
// (drive a request to FULFILLED)
assertThat(repo.timeline(fulfilledCode)).anySatisfy(e -> assertThat(e.get("status")).isEqualTo("CUSTOKING_APPROVED"));
assertThat(repo.timeline(fulfilledCode)).anySatisfy(e -> assertThat(e.get("status")).isEqualTo("FULFILLED"));
```
(Match the harness of the existing operations firefighting test — schemas migrated, `FirefightingReadRepository` constructed with its outbox writer. If none exists, mirror `CatalogOrderEventEmissionIntegrationTest` in school-core: migrate the `firefighting` schema, seed a school if the FK requires it, construct the repo.)

- [ ] **Step 2: Run — verify failure**

Run the focused test; expected FAIL (pending omits APPROVED; markVendorPaid doesn't throw; timeline lacks the new events / columns missing).

- [ ] **Step 3: Migration — lifecycle timestamps**

Create `services/operations-service/src/main/resources/db/migration/firefighting/V…__request_lifecycle_timestamps.sql` (use the next free version in that folder):
```sql
ALTER TABLE firefighting_requests
    ADD COLUMN IF NOT EXISTS custoking_approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS fulfilled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ;
```

- [ ] **Step 4: A2 — `pending()` filter to APPROVED**

In `FirefightingReadRepository.pending()` (the `WHERE status IN (...)` list), change `'AWAITING_CUSTOKING'` → `'APPROVED'`:
```java
                 WHERE status IN ('AWAITING_BURSAR', 'AWAITING_PRINCIPAL', 'APPROVED')
```

- [ ] **Step 5: B1 — vendor-paid status guard**

In `markVendorPaid`, after the cross-school check and before the already-paid check, add:
```java
        String status = str(current.get("status"), "").toUpperCase(Locale.ROOT);
        if (!List.of("CUSTOKING_APPROVED", "FULFILLED").contains(status)) {
            throw new IllegalStateException("Only approved/fulfilled requests can be marked vendor-paid");
        }
```

- [ ] **Step 6: C — stamp lifecycle timestamps + timeline events**

- `approveCustoking`: change the status UPDATE to also set `custoking_approved_at = now()`. Simplest: replace `updateStatus(code, "CUSTOKING_APPROVED")` with an explicit UPDATE:
```java
        jdbc.sql("UPDATE firefighting_requests SET status='CUSTOKING_APPROVED', custoking_approved_at=:at WHERE code=:code")
                .param("code", code).param("at", OffsetDateTime.now()).update();
```
- `fulfill`: likewise set `fulfilled_at = now()`.
- `reject`: add `rejected_at = :at` (with `now()`) to its existing UPDATE SET clause.
- Add the new fields to `FirefightingRequestRow` (the record + the `requestSelect()` column list) so the timeline can read them: `custoking_approved_at`, `fulfilled_at`, `rejected_at`.
- In `timeline()`, add after the existing events:
```java
        addEvent(events, "CUSTOKING_APPROVED", row.custokingApprovedAt());
        addEvent(events, "FULFILLED", row.fulfilledAt());
        addEvent(events, "REJECTED", row.rejectedAt());
```

- [ ] **Step 7: C — surface `custokingCriteria` in `detail()`**

In `detailRow(...)` where `custokingCriteria` is hardcoded `Map.of()`, parse the stored `custoking_criteria_json` column into a map (use the repo's existing JSON reader / `objectMapper`), falling back to `Map.of()` when null/blank.

- [ ] **Step 8: Run — GREEN + full operations suite**

Run the focused test (PASS), then the full operations suite: `… ./mvnw.cmd -f services/operations-service/pom.xml -q test` → BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add services/operations-service/src/main/java/com/custoking/ims/operationsservice/persistence/FirefightingReadRepository.java \
        services/operations-service/src/main/resources/db/migration/firefighting/ \
        services/operations-service/src/test/java/com/custoking/ims/operationsservice/persistence/FirefightingReadRepositoryIntegrationTest.java
git commit -m "fix(firefighting): pending surfaces APPROVED, vendor-paid status guard, lifecycle timestamps + timeline events, custokingCriteria"
```

---

### Task 2: platform-service — approvals-list canonical status (Part A2-platform)

**Files:**
- Modify: `services/platform-service/.../persistence/ReportingApprovalRepository.java`
- Test: `services/platform-service/.../persistence/ReportingApprovalRepositoryTest.java`

**Interfaces:**
- Consumes: operations now surfaces `APPROVED` as awaiting-custoking (Task 1).
- Produces: the command-center approvals list surfaces `APPROVED` firefighting requests; `decideFirefighting` routes `APPROVED` → `approveFirefightingCustoking`.

- [ ] **Step 1: Fix the masking test first (RED)**

In `ReportingApprovalRepositoryTest`, change the firefighting-custoking test to seed `APPROVED` instead of `AWAITING_CUSTOKING`:
```java
seedFirefighting("FF-3", "APPROVED");
// ... assert decide(...) invokes approveFirefightingCustoking on the client
```
Run it → expect FAIL (current code only recognizes `AWAITING_CUSTOKING`, so `APPROVED` throws "Approval not found" / doesn't route to custoking).

- [ ] **Step 2: Change the three filters to APPROVED**

In `ReportingApprovalRepository`:
- The firefighting-approvals list query `WHERE ff.status IN ('AWAITING_BURSAR','AWAITING_PRINCIPAL','AWAITING_CUSTOKING')` → replace `'AWAITING_CUSTOKING'` with `'APPROVED'`.
- The `decideFirefighting` allowed-set guard `List.of("AWAITING_BURSAR","AWAITING_PRINCIPAL","AWAITING_CUSTOKING")` → replace `"AWAITING_CUSTOKING"` with `"APPROVED"`.
- The `switch`/`case "AWAITING_CUSTOKING" -> approvalCommandClient.approveFirefightingCustoking(code)` → `case "APPROVED" ->`.
- `firefightingRequestType(...)`: the label for the `APPROVED` stage returns "Urgent procurement - Custoking approval".

- [ ] **Step 3: Run — GREEN + full platform suite**

Focused `ReportingApprovalRepositoryTest` PASS, then full platform suite BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/ReportingApprovalRepository.java \
        services/platform-service/src/test/java/com/custoking/ims/platformservice/persistence/ReportingApprovalRepositoryTest.java
git commit -m "fix(reporting): firefighting approvals surface APPROVED (awaiting-custoking) not the never-written AWAITING_CUSTOKING"
```

---

### Task 3: frontend — panels self-fetch, custoking approval UI, permission gating (Parts A1, A2-FE, C-FE, D2)

**Files:**
- Modify: `frontend/src/pages/workspace/panels/FirefightingDashboardPanel.tsx`
- Modify: `frontend/src/pages/workspace/panels/FirefightingApprovalsPanel.tsx`
- Modify: `frontend/src/pages/workspace/panels/FirefightingOrdersPanel.tsx`
- Modify: `frontend/src/pages/UnifiedWorkspacePage.tsx` (drop the `workspace.firefighting.requests` props)

**Interfaces:**
- Consumes: operations `GET /api/v1/ff/requests` (bare array, `limit`), `/ff/requests/pending-approvals` (bare array), `/ff/requests/{code}` (detail), `/ff/requests/{code}/approve-custoking`, `/ff/requests/{code}/timeline` (`{status, at}` items). `usePermissions().can('firefighting:write')`.

- [ ] **Step 1: Dashboard (Request Pipeline) self-fetch**

In `FirefightingDashboardPanel.tsx`: the superadmin branch currently does `api.get('/ff/requests', { params: { size: 200 } })` and reads `res.data.content`. Change it (for BOTH personas — stop relying on the `adminRequests` prop) to:
```tsx
  const [requests, setRequests] = useState<FirefightingRequest[]>([]);
  useEffect(() => {
    api.get('/ff/requests', { params: { limit: 200 } })
      .then((res) => setRequests(Array.isArray(res.data) ? res.data : []))
      .catch(() => setRequests([]));
  }, []);
  // use `requests` where `allReqs` was; keep isSuperAdmin only for UI affordances, not data source.
```
Fix the timeline modal: the FE reads `state/title/meta/note` from timeline items but the backend returns `{status, at}` — read `item.status` (label it) and `item.at` (format).

- [ ] **Step 2: Orders (Placed orders) self-fetch + Date fix**

In `FirefightingOrdersPanel.tsx`: same self-fetch of `/ff/requests` (bare array, `limit`), stop relying on `adminRequests`. The "Date" column reads `row.date` (absent) → read `row.createdAt` (format it). Placed-orders filters on `CUSTOKING_APPROVED`/`FULFILLED` — confirm those exact strings.

- [ ] **Step 3: Approvals — self-fetch pending + custoking stage + permission gate**

In `FirefightingApprovalsPanel.tsx`:
- Replace the `pendingRequests` prop dependency: self-fetch the pending list `api.get('/ff/requests/pending-approvals', { params: { limit: 200 } })` → `Array.isArray(res.data) ? res.data : []`, and use that where `pendingRequests` was fed into `loadFfApprovalDetails`.
- In `loadFfApprovalDetails`, extend the status filter to include `'APPROVED'`: `['AWAITING_BURSAR','AWAITING_PRINCIPAL','APPROVED'].includes(r.status)`.
- Add the custoking action: in the action footer, when `req.status === 'APPROVED'`, render an **"✓ Approve — Custoking"** button (superadmin only) that `POST /ff/requests/${req.code}/approve-custoking`, then `onRefresh()` + reload. Add a matching `approveFfRequest` branch for `APPROVED` → approve-custoking.
- Add `CUSTOKING_APPROVED` to `FF_STAGES` (between `APPROVED` and `FULFILLED`) so the stepper reflects it; relabel `APPROVED` → "Approved (awaiting Custoking)".
- Gate the approve/reject buttons on `const { can } = usePermissions();` `can('firefighting:write')` (hide when false).

- [ ] **Step 4: UnifiedWorkspacePage — drop the empty props**

Remove `adminRequests={workspace?.firefighting?.requests ?? []}` / `pendingRequests={…}` from the three panels (they self-fetch now). Keep `isSuperAdmin`, `setPanel`, `onRefresh`, `onOpenFfDraft`.

- [ ] **Step 5: Build**

`cd frontend && npm run build` — clean.

Manual acceptance: as a school admin, a submitted request appears in the Pipeline and Approvals; approving through bursar→principal leaves it `APPROVED`; a superadmin sees it in Approvals and can "Approve — Custoking" → `CUSTOKING_APPROVED`; it then shows in Placed Orders and can be fulfilled. Timeline modal shows labeled dated events.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/workspace/panels/Firefighting{Dashboard,Approvals,Orders}Panel.tsx frontend/src/pages/UnifiedWorkspacePage.tsx
git commit -m "fix(fe): firefighting panels self-fetch /ff/requests, custoking approval stage, permission-gated actions, timeline/date fixes"
```

---

### Task 4: frontend — module-entitlement nav gating (Part D1)

**Files:**
- Modify: `frontend/src/pages/UnifiedWorkspacePage.tsx` (or wherever nav sections are rendered) — filter nav items by the school's active modules.

**Interfaces:**
- Consumes: `GET /api/v1/schools/{schoolId}/modules/active` (school-core, already exists) → list of active module entitlements; the nav item `module` field (`config.ts`).

- [ ] **Step 1: Fetch the school's active modules**

Where the workspace page knows the current `schoolId` (e.g. `user?.branchId`), fetch once:
```tsx
  const [activeModules, setActiveModules] = useState<Set<string>>(new Set());
  useEffect(() => {
    if (!user?.branchId || isPlatformAdmin) return;  // platform admins bypass entitlement
    api.get(`/schools/${user.branchId}/modules/active`)
      .then((res) => setActiveModules(new Set((Array.isArray(res.data) ? res.data : []).map((m: any) => String(m.moduleCode ?? m.code ?? m.module).toUpperCase()))))
      .catch(() => setActiveModules(new Set()));
  }, [user?.branchId, isPlatformAdmin]);
```
(Confirm the module-code field name in the `/modules/active` response when implementing — read `ModuleEntitlementReadRepository.list(...)`'s row shape; the mapping above tries the likely keys.)

- [ ] **Step 2: Filter the nav**

When rendering nav sections, drop any item whose `item.module` is set and NOT in `activeModules` (platform admins and items without a `module` are always shown). This hides the four `FIREFIGHTING` items (and any other module-gated item) for a school lacking the entitlement.

- [ ] **Step 3: Build + acceptance**

`cd frontend && npm run build` — clean. Manual: a school without FIREFIGHTING no longer sees the Urgent Procurement nav items; a school with it (or a superadmin) does.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/UnifiedWorkspacePage.tsx
git commit -m "feat(fe): gate workspace nav by the school's active module entitlements (hides ungranted modules)"
```

---

## Self-Review

**Spec coverage:** A1 (data plumbing) → Task 3 Steps 1-4. A2 (canonical APPROVED) → Task 1 Step 4 (ops pending), Task 2 (platform), Task 3 Step 3 + FF_STAGES (FE). B1 (vendor-paid guard) → Task 1 Step 5. C (timeline shape → Task 3 Step 1; timeline events/columns → Task 1 Steps 3/6; Orders date → Task 3 Step 2; custokingCriteria → Task 1 Step 7; decideFirefighting return — minor, left as-is per spec "low priority"). D1 (module nav gating) → Task 4. D2 (permission gating) → Task 3 Step 3. Deferred items explicitly NOT built (Global Constraints).

**Placeholder scan:** no TBD/TODO. The two "confirm the field name / exact strings when implementing" notes (module-code key in `/modules/active`; placed-orders status strings) are concrete verification steps against named files, not vague placeholders — the surrounding code is given. Backend steps carry exact SQL/Java.

**Type/string consistency:** canonical `APPROVED` used in Task 1 (ops pending), Task 2 (platform filter/guard/case), Task 3 (FE filter + stage). Status vocabulary `DRAFT/AWAITING_BURSAR/AWAITING_PRINCIPAL/APPROVED/CUSTOKING_APPROVED/FULFILLED/REJECTED` identical across ops repo, platform, and FE FF_STAGES. `/ff/requests` bare-array + `limit` param consistent across all three FE panels and the Global Constraints.

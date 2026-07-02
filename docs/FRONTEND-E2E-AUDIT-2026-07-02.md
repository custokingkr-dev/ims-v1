# Frontend E2E Logical Audit — 2026-07-02

Static deep audit of every page/panel/button across the SPA (69 components, ~24 workspace panels + 8 pages, 317 click handlers, 147 API calls), tracing each interaction UI→API→display and judging **should-be** behavior — not just how it's coded. Special focus: *are API responses displayed wherever needed?*

**Headline:** a large fraction of the app is **demo / "proof-of-life" scaffolding presented as real functionality** — buttons that show a success toast but never call the backend, and panels that render hardcoded fixtures as if they were live data. There is also a cluster of genuine correctness bugs (money conversion, a broken create flow, a missing route, silent error swallowing, and permission gaps).

Rough totals: **~28 Critical, ~30 Important, ~18 Minor.** Grouped by theme below; each item has `file:line`, should-be vs actual, and a one-line fix.

---

## THEME A — "Fake" actions: button reports success but never calls the API (silent data loss)

- **[CRITICAL] command centre — ALL 13 action modals** `command/ProofOfLifeModals.tsx` (FEE_REMINDER, FF_QUOTATION_ADD, ORDER_FOLLOWUP/ESCALATE, PROFILE_UPLOAD, PROMOTION_REVIEW/EXCEPTIONS, PARENT_NOTIFY, FEE_DOWNLOAD, ATTENDANCE_SECTIONS/LOW, FF_QUOTATION_VIEW, ORDER_VALUE) — every confirm is `setTimeout(()=>{showToast();onClose()},800)` with **zero server write** (file header even says "does NOT write to the backend"). Users believe reminders were sent, quotations saved, vendors followed up — nothing happens. → Wire each to the matching function in `dashboardCommandCenterApi.ts` and refresh the metric on success.
- **[CRITICAL] Broadcast "Approve & schedule"** `HomePanel.tsx:1158` (`handleApproveBroadcast`) — only mutates local state + toast, no API call; approval lost on reload. → `POST /notifications/broadcasts/{id}/approve`, revert on error.
- **[CRITICAL] Broadcast "Send now"** `HomePanel.tsx:1149` — `api.post('.../send').catch(()=>{/*kept*/})`; on failure the UI shows "Delivering…" forever, no rollback, no error. → Revert to `scheduled` + error toast in catch.
- **[CRITICAL] PlanningPanel fake-save cluster** `PlanningPanel.tsx:182` ("Save plan"), `:226`/`:419` ("Accept all N suggestions"), `:467`/`:596`/`:603`/`:610` (event/FF "Place order"/"Add to plan" CTAs → only `setPanel('catalog')`), `:353` ("Confirm all classes"), `:179` ("Export PDF" → wrong modal, no export) — all toast-only / navigate-away with **no API call**; nothing is ever persisted. → Wire each to a real annual-plan/supply endpoint or disable with "coming soon".
- **[CRITICAL] New Firefighting Request never submits + discards quotations** `FirefightingNewPanel.tsx:129` — `POST /workspace/firefighting` creates a row hardcoded to `status='DRAFT'` (body `status` ignored); the code never POSTs the quotations array nor calls `/ff/requests/{code}/submit`. Result: request stuck in DRAFT, **all entered quotations silently lost** (only the edit-flow does the correct PATCH→quotations→submit chain). → After create, capture `code`, POST each quotation to `/ff/requests/{code}/quotations`, then POST `/ff/requests/{code}/submit`.
- **[CRITICAL] FF quotation document "upload"** `FirefightingNewPanel.tsx:218` — file `onChange` stores `documentUrl = file.name` (a bare filename); file is never uploaded anywhere; document is unrecoverable. → POST file to storage, use returned URL.
- **[CRITICAL] SaNewOrder "Save as draft" dead button** `SaNewOrderPanel.tsx:203` — no `onClick` at all. → Wire to draft POST or disable.
- **[IMPORTANT] Resend/notify no-ops** `SaAllOrdersPanel.tsx:126` (Send-to-school resend), `:252` (WhatsApp school), `SaInvoicesPanel.tsx:64` & `:121` (Send-to-school / Resend) — all `setToast(...)` with no API call; false confirmation. → Wire to resend/notify endpoints or remove the success toast.

## THEME B — Hardcoded / mock / fixture data rendered as real

- **[CRITICAL] HomePanel fixture fallbacks** shown as real data on error/empty: `MOCK_BROADCASTS` (`:1040`), `MOCK_SUGGESTIONS` (`:1025`), `SEED_FEED` (`:1055` — 7 fake feed items shown permanently on error). `fixtures.ts` comment admits "Remove once endpoints ship." → Replace with empty/error states.
- **[CRITICAL] Fabricated command-centre modals** `ProofOfLifeModals.tsx:572` (AttendanceSections — fake class/teacher names), `:613` (AttendanceLow), `:272` (PromotionExceptions — fake student names) render hardcoded arrays though real APIs exist (`fetchLowAttendanceSections`). → Fetch and render real data.
- **[CRITICAL] Superadmin placeholder panels** `SaCatalogPanel.tsx` (hardcoded string), `SaErpPanel.tsx` ("coming soon" EmptyState, no API), `SaRevenuePanel.tsx` ("coming soon", no API) — three whole superadmin tabs show no real data. → Implement or clearly mark unbuilt.
- **[IMPORTANT] Fabricated KPI sparklines** `HomePanel.tsx:286` — sparkline arrays are mostly hardcoded (only 1–2 of 10 points real); misleading trends. → Use real history or omit.
- **[IMPORTANT] Hardcoded command-centre card counts** `commandCentreUtils.ts:87` ("23 profiles incomplete"), `:107` ("412 eligible · 8 exceptions"), `:215` ("4 sections haven't marked") — fixed regardless of school state. → Derive from workspace data or omit.
- **[IMPORTANT] Planning header stats hardcoded** `PlanningPanel.tsx:198` (Urgent 3 / Suggested 4 / Total ₹15.9L) — literals, not from `workspace.annualPlan`. → Compute from real data.

## THEME C — Money / data-corruption bugs (highest data-integrity risk)

- **[CRITICAL] Add fee item stores 1/100th** `FeeStructurePanel.tsx:139` — user enters ₹ but body sends the raw number; backend stores paise → ₹1000 becomes ₹10. → Send `Math.round(Number(amount)*100)`.
- **[CRITICAL] Edit fee item stores 1/100th** `FeeStructurePanel.tsx:158` — edit pre-fills rupees (`/100`) but PUT sends the rupee value un-converted. → Re-convert `*100` on save.
- **[CRITICAL] Payment amount pre-fill 100× off** `FeesPanel.tsx:141` — pre-fills `dueAmount` (paise) into a ₹-labeled field; on submit `*100` makes it 100× the due (then a confusing "exceeds due" block). → Pre-fill `dueAmount/100`.

## THEME D — Broken / dead real features

- **[CRITICAL] Zone Admin nav renders blank** `UnifiedWorkspacePage.tsx:363-467` — no render branch for `za-overview` or `za-schools` (the entire Zone-Admin nav); a zone admin sees an empty content area on every tab. → Add render branches/components.
- **[CRITICAL] Student "Edit" is a dead-end** `StudentsPanel.tsx:74` — `handleEditStudent` ignores its arg and routes to the create-only AddStudentPanel (always POSTs); no edit/PUT path exists. → Wire an edit flow or remove the button.
- **[CRITICAL] Student names render blank** `StudentsPanel.tsx:192,195` — uses `student.name` but the API/type field is `fullName` (index signature hides the TS error). → Use `student.fullName`.
- **[CRITICAL] Missing gateway route → 404** `dashboardCommandCenterApi.ts:128` calls `/api/v1/student-review-campaigns/{id}/items`, no route in `server.js`; both review tabs swallow the 404 silently so per-student review items are **always empty**. → Add `route('student','/api/v1/student-review-campaigns/')` (or fix the path).
- **[IMPORTANT] FF "Mark Vendor Paid" missing from UI** — backend `POST /ff/requests/{code}/vendor-paid` + gateway route exist, but no button/flow anywhere. → Add the action on FULFILLED rows (superadmin).
- **[IMPORTANT] AdminOrders items column blank** `AdminOrdersPanel.tsx:141` — renders `row.items` which catalog-created orders don't have (they store `orderData`). → Derive count from `orderData` or a backend field.
- **[IMPORTANT] "Trust this device" no-op** `LoginPage.tsx:167,287` — checkbox state never passed to `login()` (TODO present). → Thread `trustDevice` into the login body.
- **[IMPORTANT/MINOR] Dead links/buttons** — `LoginPage.tsx:312` Forgot-password, `:366` Terms/Privacy/Security (all `href="#"` no-ops); `HomePanel.tsx:691` "Why this?", `:815` broadcast "Edit"; `DashboardPage.tsx` entire file is unreachable dead code (route goes to UnifiedWorkspacePage). → Wire or remove/disable.

## THEME E — Silent error swallowing (mutation/load fails with zero user feedback)

Convention: there is **no global error toast** — every call site must surface its own error. These don't:
- **[CRITICAL] StaffPanel add-staff** `StaffPanel.tsx:36` — try/finally, no catch (admin mutation fails silently).
- **[CRITICAL] RBAC loadPermissions** `useRbacFeature.ts:48` — no catch; role UI shows empty permission list on failure (roles could be saved with no perms).
- **[CRITICAL] Modules modal → all-disabled fallback** `SchoolManagementPage.tsx:128` — entitlement fetch failure silently sets every module to `false`; an unwary Save disables all modules for the school (data-loss). → `setError` + close on failure.
- **[CRITICAL] Students list load** `StudentsPanel.tsx:30` — no catch; empty list on failure, no message.
- **[CRITICAL] Review checklist toggle** `StudentReviewDrawer.tsx:118` — optimistic update with bare `catch{}`, no revert; checkbox stuck out of sync with server.
- **[CRITICAL] loadLiveOrders empty catch** `UnifiedWorkspacePage.tsx:120` — `catch {}`; orders panel blank/stale on failure.
- **[IMPORTANT] SaAllOrders acceptOrder empty catch** `SaAllOrdersPanel.tsx:99`; **openInvoiceFromOrder** `:115` treats ALL errors as 404 → opens new-invoice form → **duplicate-invoice risk** on a real outage.
- **[IMPORTANT] FF silent swallows** `FirefightingDashboardPanel.tsx:34`, `FirefightingOrdersPanel.tsx:38` (list → `[]`), timeline `:46`/`:50`, approval per-request `FirefightingApprovalsPanel.tsx:76` (failed request vanishes from queue).
- **[IMPORTANT] Fee/PDF swallows** `FeesPanel.tsx:110` (receipt PDF), `FeeStructurePanel.tsx:121` (export PDF), `:44` (class load), `:252`/`:265` (assign dropdowns), `BulkImportPanel.tsx:108` (template download).
- **[IMPORTANT] Reminder error shown as success** `FeesPanel.tsx:203/273` — error message rendered with green `✓` success styling.

## THEME F — Missing permission gates on privileged actions

- **[IMPORTANT] School mutations gated only on `school:read`** `SchoolManagementPage.tsx:236,281,284,287` — Add School / Reset Admin / Add Ops / Modules all visible to any reader. → Gate on a write permission.
- **[IMPORTANT] UsersPage no gate** `UsersPage.tsx:13` — no `can()` check/redirect on the platform-users page. → Add `can('user:read')` gate.
- **[MINOR] Customer mis-tagged to school 1** `CustomersPage.tsx:36` — `branchId: user?.branchId || 1`; superadmin-created customers default to school 1. → Require explicit school.

## THEME G — Logic / UX correctness

- **[IMPORTANT] React hooks-after-early-return** `ZoneManagementPage.tsx:34,47` — `useEffect` declared after a conditional `return`; violates Rules of Hooks. → Move the gate below all hooks.
- **[IMPORTANT] Attendance success toast never seen** `AttendancePanel.tsx:212` — toast set, then `openSectionDrawer` resets it to `''` immediately. → Use a separate toast state / set after reload.
- **[IMPORTANT] Attendance `schoolId` omitted** `AttendancePanel.tsx` section-register GET/PUT, submit-section, submit-day — `schoolScopedParams` only on the summary GET; superadmin context may hit the wrong school / be rejected. → Forward on all four.
- **[IMPORTANT] Approvals stale after decision** `ApprovalsPage.tsx:42` — `void load()` not awaited; row stays PENDING and buttons re-enable mid-reload (race). → `await load()` inside try.
- **[IMPORTANT] FF reject empty reason** `FirefightingApprovalsPanel.tsx:113` — no non-empty check; `{reason:''}` sent. → Require reason.
- **[IMPORTANT] FF double-submit on approve** `FirefightingApprovalsPanel.tsx:191` — both approve buttons always enabled during the 2-POST chain (reject is guarded, approve isn't). → Add per-code `approving` disabled state.
- **[IMPORTANT] Bulk import** `BulkImportPanel.tsx:76` — no loading during preview POST (UI looks frozen); `:118` — missing `jobId` → loop skipped → misleading "0 students imported" success. → Add previewing state; handle missing jobId.
- **[IMPORTANT] Generic error messages** `ZoneManagementPage.tsx:58,74` — discards server message for a hardcoded string; stale error persists on retry (no `setError('')` at start).
- **[IMPORTANT] Reorder handler** `UnifiedWorkspacePage.tsx:425` — no try/catch (unhandled rejection) and no success notice.
- **[IMPORTANT] Superadmin approvals not refreshed on tab return** `UnifiedWorkspacePage.tsx:200` — calls `loadLiveOrders()` not `loadPendingApprovalOrders()`; stale approval queue.

## THEME H — Missing loading / empty states

- **[IMPORTANT] Blank screen during token refresh** `ProtectedRoute.tsx:9` — `if(loading) return null` → white page on every returning-user load. → Spinner/fallback.
- **[MINOR] Missing empty states** — InvoicesPage `:130`, PaymentsPage `:99`, FeesPanel report `:421`, ZoneManagementPage `:107`, UsersPage `:43`, ApprovalsPage `:71`, CustomersPage `:70`, AttendancePanel sections grid `:379` (can't tell "no data" from "load failed").
- **[MINOR] Misc** — FF `REJECTED` status has no pipeline column (`FirefightingDashboardPanel.tsx:83`); FF existing quotations read-only (no edit/delete though endpoints exist, `FirefightingNewPanel.tsx:65`); StaffPanel form not reset / no success after add; InvoicesPage no per-line "remove"; TimetablePanel add-entry no client validation; raw ISO dates unformatted (`AdminOrdersPanel.tsx:149`); "Download" buttons show success-style "coming soon" toasts (SaAllOrders/SaInvoices).

## Clean areas (verified working)
Login form (validation, loading, error distinction, routing); AuthContext bootstrap + api.ts token/refresh/401 handling; usePermissions; catalog order create + GST/paise math + list refresh; supply-order approve/reject (school + superadmin) with notices, refetch, double-submit guard; attendance register load/marks/counts/submit-guards and error display; bulk-import preview rendering real API rows; SaSchools/SaNewOrder-create/SaInvoices-create/SaAllOrders status-patch flows; RBAC path wiring; gateway routing for all paths except `student-review-campaigns`.

---

## Suggested fix order (when greenlit)
1. **Data-integrity first (fast, high-impact):** Theme C money bugs (3 files), the missing gateway route (Theme D), student `fullName` + Edit button, Modules all-disabled fallback.
2. **Silent-failure sweep (Theme E):** add real error surfacing to the ~15 swallow sites (mechanical, per the app convention).
3. **"Fake action" decisions (Theme A/B):** these need a product call per feature — *wire to the real endpoint* vs *disable/"coming soon"*. Many endpoints already exist (broadcast approve/send, FF submit/quotations/vendor-paid, low-attendance) so wiring is straightforward; others (SaCatalog/Erp/Revenue, PDF exports, planning persistence) may be genuinely unbuilt backend-side.
4. **Permission gates (Theme F), hooks bug + logic (Theme G), loading/empty states (Theme H).**

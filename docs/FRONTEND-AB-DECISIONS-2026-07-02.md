# Theme A/B — "Wire vs Disable" decision list (2026-07-02)

The audit found many buttons that show a success toast but never call the backend (Theme A), and panels that render hardcoded fixtures as real data (Theme B). Each needs a product call: **wire it to the real endpoint**, **disable / mark "coming soon"**, or **build backend first**. Grouped by which of those applies — most already have a ready endpoint.

Legend: ✅ endpoint exists (wiring only) · 🟠 partial/needs mapping · 🔴 backend missing.

## Bucket 1 — Ready to WIRE now (endpoint already exists) — recommended
For each: the frontend button → the existing endpoint/fn it should call.

| Feature | File | Endpoint / fn (exists) |
|---|---|---|
| ✅ **FF New Request submit + quotations** (never submits, loses quotes — a real bug) | `FirefightingNewPanel.tsx:129` | `POST /ff/requests/{code}/quotations` then `POST /ff/requests/{code}/submit` (the edit-flow already does this chain — copy it) |
| ✅ **FF Mark Vendor Paid** (missing UI) | Firefighting Orders/Approvals | `markFirefightingVendorPaid()` → `/dashboard/vendor-dues/firefighting/{code}/mark-paid` |
| ✅ **Broadcast Approve / Send** (local-state only) | `HomePanel.tsx:1149,1158` | `POST /notifications/broadcasts/{id}/approve` · `.../send` |
| ✅ **Command-centre FEE_REMINDER** modal | `ProofOfLifeModals.tsx` | `sendFeeReminders()` → `/dashboard/finance/fee-defaulters/reminders` |
| ✅ **Command-centre ATTENDANCE_LOW / SECTIONS** (fake data + fake action) | `ProofOfLifeModals.tsx:572,613` | `fetchLowAttendanceSections()` / `fetchLowAttendanceStudents()` / `sendMeetingInvites()` |
| ✅ **Command-centre ORDER vendor-paid** | `ProofOfLifeModals.tsx` | `markCatalogOrderVendorPaid()` → `/dashboard/vendor-dues/catalog-orders/{id}/mark-paid` |
| ✅ **Command-centre PROMOTION_REVIEW / PROFILE** | `ProofOfLifeModals.tsx` | `initiateIdCardReview()` / `initiateFullNameVerification()` |
| ✅ **Command-centre PARENT_NOTIFY / photography** | `ProofOfLifeModals.tsx` | `sendPhotographyPaymentReminders()` |
| ✅ **Planning "Accept suggestions" / "Add to plan" / "Confirm all classes"** | `PlanningPanel.tsx:182,226,353,467,596` | `POST /supply/annual-plan/items` · `POST /supply/annual-plan/confirm` |
| ✅ **SaRevenue panel** (placeholder) | `SaRevenuePanel.tsx` | `GET /sa/invoices/stats` (GMV/revenue) — render real stats |
| ✅ **HomePanel MOCK fallbacks** (broadcasts/feed/suggestions shown on error) | `HomePanel.tsx:1025,1040,1055` | real endpoints already fetched (`/dashboard/command-center`, `/notifications/broadcasts`) — just drop the fixture fallback, show empty/error |
| ✅ **HomePanel hardcoded card counts / sparklines** | `commandCentreUtils.ts:87,107,215`; `HomePanel.tsx:286` | derive from `workspace` data already in props (or omit) |
| ✅ **SaErp panel** (placeholder) | `SaErpPanel.tsx` | `GET /dashboard` / `/dashboard/command-center` (reporting) — surface real metrics |

## Bucket 2 — DISABLE / "coming soon" until backend exists (no endpoint)
| Feature | File | Gap |
|---|---|---|
| 🔴 **FF quotation document upload** | `FirefightingNewPanel.tsx:218` | no file-storage/upload endpoint — stores filename only. Disable upload or build storage. |
| 🔴 **Planning "Export PDF"** | `PlanningPanel.tsx:179` | no plan-PDF endpoint (fee receipt/structure PDFs DO exist; plan does not). |
| 🔴 **Sa "Resend invoice"** | `SaAllOrdersPanel.tsx:126`, `SaInvoicesPanel.tsx:64,121` | no resend endpoint (only create/get). |
| 🔴 **Sa "WhatsApp school"** (order notify) | `SaAllOrdersPanel.tsx:252` | no "notify school about order" endpoint. |
| 🔴 **SaCatalog management** | `SaCatalogPanel.tsx` | hardcoded string; no SA catalog-CRUD endpoints surfaced. |
| 🔴 **Login "Trust this device"** | `LoginPage.tsx:287` | `/auth/login` must accept + honor a `trustDevice` flag (extend refresh TTL). |
| 🔴 **Login "Forgot password"** | `LoginPage.tsx:312` | no password-reset flow (backend + email). |
| 🔴 **Zone-Admin `za-overview` / `za-schools` views** | `UnifiedWorkspacePage.tsx:363` | blank content area — the zone-admin views are unbuilt (need design + data). Interim: render a clear placeholder instead of blank. |
| 🔴 **SaNewOrder "Save as draft"** | `SaNewOrderPanel.tsx:203` | no draft-order status/endpoint. |
| 🟠 **Command-centre ORDER_FOLLOWUP/ESCALATE, other modals** | `ProofOfLifeModals.tsx` | verify a matching action endpoint exists; some (follow-up/escalate) may need a new backend action. |

### Command-centre modals — wiring attempted then REVERTED (needs backend-accurate rework)
A Bucket-1 pass wired the POL modals but a regression review found the send-actions hit wrong/missing endpoints, so it was reverted. Findings to drive the redo:
- The **data-load GETs are correct** (`fetchLowAttendanceSections`, `fetchClassPhotographyPaymentStatus`, `fetchVendorDues`, `fetchFeeDefaulters`) — reuse them to replace the fixtures.
- **Vendor-paid actions ARE correct** (`markCatalogOrderVendorPaid(orderId)`, `markFirefightingVendorPaid(code)` — vendor-dues `id` = order id / FF code). Safe to re-wire.
- ❌ **Fee reminders**: `/dashboard/finance/fee-defaulters/reminders` reads `classId/sectionId` and ignores `studentIds/channel/message`; response has no `sentCount` (has `queued`/`content[]`). A command-centre "remind all defaulters" needs class/section context or a different endpoint.
- ❌ **Attendance meeting-invites**: `POST /dashboard/attendance/meeting-invites` has **no backend handler** (404) — endpoint must be built or the action dropped.
- ❌ **Photography payment-reminders**: frontend used `/dashboard/events/{eventId}/payment-reminders` (404); the real one is `POST /api/v1/reporting/event-contributions/reminders` (verify its request shape).
- ❌ **Lifecycle initiate** (id-card / full-name): frontend used `/dashboard/student-lifecycle/...` (404); the real ones are student-service `POST /api/v1/students/reviews/{id-card,full-name}/initiate`.
- Note (I2): in normal operation `/command-centre/actions` supplies the cards and opens **no** POL modals; the polcode modals only appear in the fallback path when that GET errors — so this layer is low-traffic. `dashboardCommandCenterApi.ts` itself contains several of these wrong paths as latent dead code — fix there.

## Bucket 3 — Also worth a decision
- **Student "Edit" button** (`StudentsPanel.tsx:74`) — dead-end (no edit flow). Either build an edit form (PUT) or remove the button. (Left untouched in the clear-bug pass.)
- **FF existing-quotation edit/delete** (`FirefightingNewPanel.tsx:65`) — endpoints exist (`PATCH`/`DELETE /ff/requests/{code}/quotations/{id}`) but no UI. Wire or leave read-only.
- **`DashboardPage.tsx`** — unreachable dead code (route uses UnifiedWorkspacePage). Delete.
- **"Download" buttons** (SaAllOrders/SaInvoices) — currently success-style "coming soon" toast; make them a disabled state with tooltip.

---
**Recommendation:** knock out **Bucket 1** (pure wiring, real endpoints, kills the biggest "it lied to me" issues incl. the FF-never-submits bug) as the next batch; **Bucket 2/3** are product calls (build vs disable). Tell me which items in each bucket to action.

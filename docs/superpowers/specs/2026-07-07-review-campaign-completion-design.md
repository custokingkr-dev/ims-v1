# Review-Campaign Completion — Design

**Date:** 2026-07-07
**Status:** Approved for planning
**Services touched:** school-core-service (`student` schema), platform-service (`reporting` schema), frontend

---

## 1. Problem

A *review campaign* is a school-wide, one-item-per-student data-verification drive. Two types exist:

- **ID Card Details review** (`ID_CARD_DETAILS`) — verify each enrolled student's ID-card fields via a checklist (photo, full name, admission no., class/section, roll no., father's name & contact, address, blood group).
- **Full Name Verification** (`FULL_NAME_VERIFICATION`) — confirm each student's name matches official records, with teacher/parent confirmation and a correction-request path.

`student.student_review_campaigns` is created with `status = 'ACTIVE'` and enforces **one ACTIVE campaign per `(school_id, review_type)`**. There is **no way to complete a campaign.** Consequences:

1. Once started, a campaign stays `ACTIVE` forever, so a school can **never start a fresh campaign** (e.g. next academic year) — `initiateIdCardReview` / `initiateFullNameVerification` reject with "an active … campaign already exists".
2. The dashboard "pending review" KPI (`ReportingReadRepository.dashboardCommandCenter` → `pendingReviewCount`) counts `PENDING` items with no way to ever clear them.
3. During Reporting Decoupling SP7 (phase 3), `pendingReviewCount` was rewritten to read `reporting.fact_student_review_item` and **dropped the original `campaign.status = 'ACTIVE'` filter** because the fact carries no campaign status. A code comment documents this as currently-equivalent (campaigns are only ever `ACTIVE`) but load-bearing.

## 2. Goal

Let a school admin **complete** a review campaign once every student's item is resolved. Completing:

- freezes the campaign as a read-only archive,
- unlocks starting a fresh campaign of the same type,
- makes the dashboard "pending review" KPI count only **active** campaigns (restoring the dropped filter, backed by real data this time).

## 3. Decisions (settled during brainstorming)

| # | Decision | Choice |
|---|----------|--------|
| 1 | Completion trigger | **Manual** "Complete campaign" button. No auto-complete. |
| 2 | Unresolved items at completion | **Block until all resolved** — Complete is allowed only when every item is `COMPLETED`. |
| 3 | After completion | **Freeze items** — item edits are rejected once the campaign is `COMPLETED`. |
| 4 | Permission / reversibility | **Same permission as initiate** (`student:write`, school admin); **one-way**, no reopen. |

**Out of scope (YAGNI):** reopen/unfreeze; a completed-campaign history view. If a campaign is completed by mistake, the admin starts a fresh one (it re-scans every student).

## 3.5 Prerequisite — the review drawer is broken on GCP (fix FIRST)

**Confirmed on dev (2026-07-07):** the `StudentReviewDrawer`'s status/initiate calls are wired to endpoints that do not exist. `dashboardCommandCenterApi.ts` calls `/api/v1/dashboard/student-lifecycle/{id-card-review|full-name-verification}/{status|initiate}`, which the gateway routes to **platform-service** (`route('reporting', '/api/v1/dashboard/')`). Platform has **no** controller for those paths. An authenticated superadmin probe returns:

```
GET /api/v1/dashboard/student-lifecycle/id-card-review/status  →  HTTP 404 Not Found
{"status":404,"error":"Not Found","path":"/api/v1/dashboard/student-lifecycle/id-card-review/status"}
```

So the drawer cannot load campaign status or start a campaign on GCP today — the whole feature sits on a broken surface. The working endpoints already exist in **school-core** `StudentReadController` (`@RequestMapping("/api/v1/students")`), routed via the `/api/v1/students/` gateway prefix:

| Purpose | Working endpoint (school-core) |
|---|---|
| ID-card status | `GET /api/v1/students/reviews/id-card/status` |
| ID-card initiate | `POST /api/v1/students/reviews/id-card/initiate` |
| Full-name status | `GET /api/v1/students/reviews/full-name/status` |
| Full-name initiate | `POST /api/v1/students/reviews/full-name/initiate` |
| Campaign items (already correct) | `GET /api/v1/students/review-campaigns/{campaignId}/items` |
| Item edit (already correct) | `PUT /api/v1/student-review-items/{itemId}` (+ `/full-name-verification`) |

**Fix (prerequisite task, do before the Complete button):** repoint the four status/initiate functions in `dashboardCommandCenterApi.ts` from `/dashboard/student-lifecycle/…` to the canonical `/students/reviews/…` paths above. Confirm the school-core status DTOs match the drawer's TS types (`IdCardReviewStatusResponse` / `FullNameVerificationStatusResponse`: `campaignId`, `totalStudents`, `completed`, `needsCorrection`, `completionPercent`). No gateway change (already routed). This also means status is read **live from the owning service (school-core)**, which resolves the post-complete refresh concern (see §7) — no projection lag.

## 4. Item status model (existing, for reference)

Item `status` (`student.student_review_items.status`) is one of:
- `PENDING` — not yet reviewed.
- `NEEDS_CORRECTION` — reviewed, a correction was requested (name wrong, checklist flagged).
- `COMPLETED` — fully reviewed / confirmed.

"All resolved" for completion means **every item is `COMPLETED`** (i.e. zero `PENDING` **and** zero `NEEDS_CORRECTION`). `NEEDS_CORRECTION` is *not* resolved — the correction must be applied and the item re-completed first.

The dashboard `pendingReviewCount` counts only `status = 'PENDING'`.

## 5. Backend — school-core-service (`student` schema)

### 5.1 Migration `student/V8__review_campaign_completion.sql`

```sql
ALTER TABLE student.student_review_campaigns
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS completed_by BIGINT;
```

`status` already exists; no new status column. Forward-only; no backfill (existing campaigns stay `ACTIVE`).

### 5.2 New endpoint — complete a campaign

`POST /api/v1/students/review-campaigns/{campaignId}/complete`, in `StudentReadController` (which is `@RequestMapping("/api/v1/students")`, so the method path is `/review-campaigns/{campaignId}/complete`). This sits under the already-routed `/api/v1/students/` gateway prefix (→ student/school-core) — **no gateway change needed**, and it is a sibling of the existing `GET /api/v1/students/review-campaigns/{campaignId}/items`. Gated on the internal token scope `student:write` and tenant scope (identical to the initiate endpoints). Returns the campaign's status DTO (same shape as `idCardReviewStatus` / `fullNameVerificationStatus`) so the caller can refresh in place.

> **Do NOT** put this under `/api/v1/reviews/…` — there is no such gateway route, so it would 404 (the exact failure mode the timetable route hit). The plan MUST include a step confirming the gateway routes the chosen path.

`StudentReadRepository.completeCampaign(String campaignId, Long actorId)` (`@Transactional`):

1. Load the campaign (`campaignRecord`-style read under RLS). If not found/visible → `IllegalArgumentException("Review campaign not found")` (the controller's existing exception mapping turns `IllegalArgumentException` into HTTP 400).
2. If `status <> 'ACTIVE'` → `IllegalArgumentException("This campaign is not active")`.
3. Count unresolved items:
   ```sql
   SELECT count(*) FROM student.student_review_items
   WHERE campaign_id = :campaignId AND status <> 'COMPLETED'
   ```
   If `> 0` → `IllegalArgumentException("<n> item(s) still need review — resolve them before completing the campaign.")`.
4. `UPDATE student.student_review_campaigns SET status = 'COMPLETED', completed_at = now(), completed_by = :actorId, updated_at = now() WHERE id = :campaignId`.
5. Append an outbox event (see 5.4).
6. Return the campaign status DTO.

### 5.3 Freeze — reject item edits on a completed campaign

Both item-mutating repository methods guard on the owning campaign's status **before** mutating:

- the ID-card checklist update method (behind `POST /students/reviews/items/{itemId}`)
- the full-name confirm / request-correction method (behind `POST /students/reviews/items/{itemId}/full-name-verification`)

**Put the guard in the repository method, not the controller.** Each item edit has *two* HTTP entry points that call the same repository method — the canonical `StudentReadController` path above **and** the compat `StudentWorkspaceCompatibilityController` path (`PUT /api/v1/student-review-items/{itemId}` and `…/full-name-verification`, which is what the FE currently calls). Guarding in the repository covers both; guarding only in one controller would leave the compat path unprotected.

Guard: read the item's campaign status; if `COMPLETED`, throw a dedicated `CampaignCompletedException` (new, in `student` persistence package). **Both** controllers (`StudentReadController` and `StudentWorkspaceCompatibilityController`) map it to **HTTP 409 CONFLICT** ("This campaign is completed and read-only."), mirroring how `TimetableController` maps `YearLockedException` → 409.

### 5.4 Event emission

`completeCampaign` appends, via the existing school-core `OutboxWriter` (same transaction):

- **eventType:** `student-review-campaign.completed.v1`
- **eventKey:** `StudentReviewCampaignCompleted:<campaignId>`
- **aggregateType:** `StudentReviewCampaign`, **aggregateId:** `<campaignId>`, **schoolId:** `<schoolId>`
- **payload:** `{ "campaignId": "<id>", "schoolId": <id>, "status": "COMPLETED" }`

The existing `OutboxRelay` publishes it (service-prefixed eventId already in place) to the shared reporting topic. No new outbox infrastructure.

## 6. Reporting — platform-service (`reporting` schema)

### 6.1 Migration `reporting/V21__review_campaign_status.sql`

```sql
ALTER TABLE reporting.fact_student_review_item
    ADD COLUMN IF NOT EXISTS campaign_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_fact_student_review_item_campaign
    ON reporting.fact_student_review_item (campaign_id);
```

Existing rows default to `'ACTIVE'` — correct, because every existing campaign is `ACTIVE`.

### 6.2 Projection

`StudentReviewProjector` handles a second event type:

- `handledEventTypes()` → `{ "student-review-item.upserted.v1", "student-review-campaign.completed.v1" }`.
- On `…campaign.completed.v1`: `UPDATE reporting.fact_student_review_item SET campaign_status = :status, updated_at = now() WHERE campaign_id = :campaignId`.
- On `…item.upserted.v1` (existing path): the item upsert **must not touch `campaign_status` on conflict** — it sets `id, school_id, campaign_id, status` only. New inserts get the column default `'ACTIVE'`.

**Ordering safety:** all item rows for a campaign are created at *initiation* (items are inserted up-front) and frozen at completion, so every item's fact row exists before the completion event runs its bulk `UPDATE`. Because the item upsert never resets `campaign_status`, a late-arriving item event (emitted while active, processed after completion) updates `status` but leaves `campaign_status = 'COMPLETED'`. `feedWorthy()` stays `false`.

`StudentReviewFactReadRepository` gains an `updateCampaignStatus(campaignId, status)` method for the bulk update (keeps SQL in the repository, matching the existing `upsert(...)` there).

### 6.3 KPI filter restore

In `ReportingReadRepository.dashboardCommandCenter`, the `pendingReviewCount` query changes from:

```sql
SELECT count(*) FROM reporting.fact_student_review_item
WHERE school_id = :schoolId AND status = 'PENDING'
```

to:

```sql
SELECT count(*) FROM reporting.fact_student_review_item
WHERE school_id = :schoolId AND status = 'PENDING' AND campaign_status = 'ACTIVE'
```

The phase-3 invariant-note comment is replaced with a one-line note that the filter is now backed by projected campaign status.

## 7. Frontend — `dashboardCommandCenterApi.ts` + `StudentReviewDrawer.tsx`

**First (prerequisite, §3.5):** repoint the four status/initiate functions from the broken `/dashboard/student-lifecycle/…` paths to the working `/students/reviews/…` paths so the drawer loads at all.

Then the drawer's two tabs (`IdCardTab`, `FullNameTab`) already render an active-campaign view with a summary (`total`, `completed`, `needsCorrection`) and progress. Add:

- A **"Complete campaign"** button in the active-campaign header/summary area.
  - **Enabled only when every item is resolved**, i.e. `completed === total` (since an item counts as `completed` only when fully `COMPLETED`, this already excludes `PENDING` and `NEEDS_CORRECTION`). When disabled, show an inline hint with the remaining count, e.g. "42 student(s) still to review".
  - Clicking opens a confirm dialog ("Complete this campaign? It will be locked and can't be edited."). On confirm, call the new endpoint.
- New API function in `dashboardCommandCenterApi.ts`: `completeReviewCampaign(campaignId)` → `POST /students/review-campaigns/{campaignId}/complete`.
- On success: reload status. Because status is read **live from school-core** (after the §3.5 fix) and the campaign is no longer `ACTIVE`, the status endpoint returns no active campaign, so the tab returns to its "Initiate" screen — ready for the next campaign, with **no projection lag**. Trigger `onMetricsRefresh` so the dashboard KPI (served from the reporting projection, which updates once the completion event projects) refreshes.
- Button gated on the existing `canWrite` (`student:update`) like the other write actions.

## 8. Error handling summary

| Condition | HTTP | Message |
|-----------|------|---------|
| Complete a non-existent/invisible campaign | 400 | "Review campaign not found" |
| Complete a non-`ACTIVE` campaign | 400 | "This campaign is not active" |
| Complete with unresolved items | 400 | "`<n>` item(s) still need review — resolve them before completing the campaign." |
| Edit an item in a `COMPLETED` campaign (either entry point) | 409 | "This campaign is completed and read-only." |

All surfaced by the FE via the existing `err.response.data.message` pattern.

## 9. Testing

**school-core (`StudentReviewRepositoryIntegrationTest` or equivalent, Testcontainers):**
- Complete succeeds when all items `COMPLETED`; campaign row shows `status='COMPLETED'`, `completed_at`/`completed_by` set; an outbox row for `student-review-campaign.completed.v1` exists.
- Complete is blocked (throws, campaign stays `ACTIVE`) when ≥1 item is `PENDING` or `NEEDS_CORRECTION`.
- Complete is blocked when the campaign is already `COMPLETED` (not `ACTIVE`).
- `updateReviewItem` / `verifyFullName` throw `CampaignCompletedException` when the campaign is `COMPLETED`.
- After completion, `initiateIdCardReview` / `initiateFullNameVerification` succeed (a new `ACTIVE` campaign of the same type can start).

*Note: Testcontainers run RLS-exempt (owner role), so these prove the domain logic, not RLS scoping.*

**platform-service (reporting projection tests):**
- Projecting `student-review-campaign.completed.v1` flips `campaign_status` to `COMPLETED` for all rows of that `campaign_id`.
- A completed campaign's `PENDING` items are excluded from `pendingReviewCount`; an active campaign's are counted.
- An item upsert after completion does not reset `campaign_status`.

**Prerequisite (§3.5) verification:** after repointing the FE, manually confirm on dev (authenticated) that the drawer loads campaign status and can initiate — i.e. `GET /api/v1/students/reviews/id-card/status` returns a real DTO (not 404). This is the acceptance gate for the prerequisite before the Complete button is built.

**Frontend:** no tests (repo convention for these panels), unless requested.

## 10. Files

**Prerequisite (§3.5) — frontend wiring fix, do first**
- Modify: `frontend/src/api/dashboardCommandCenterApi.ts` — repoint `fetchIdCardReviewStatus`, `initiateIdCardReview`, `fetchFullNameVerificationStatus`, `initiateFullNameVerification` from `/dashboard/student-lifecycle/…` to `/students/reviews/…`.

**school-core-service**
- Create: `src/main/resources/db/migration/student/V8__review_campaign_completion.sql`
- Create: `src/main/java/.../schoolcoreservice/persistence/CampaignCompletedException.java`
- Modify: `src/main/java/.../persistence/StudentReadRepository.java` (`completeCampaign`; freeze guard in both item-mutating methods)
- Modify: `src/main/java/.../api/StudentReadController.java` (complete endpoint at `/review-campaigns/{campaignId}/complete`; map `CampaignCompletedException` → 409)
- Modify: `src/main/java/.../api/compat/StudentWorkspaceCompatibilityController.java` (map `CampaignCompletedException` → 409 on the compat item-edit paths)
- Test: `src/test/java/.../persistence/StudentReviewRepositoryIntegrationTest.java`

**platform-service**
- Create: `src/main/resources/db/migration/reporting/V21__review_campaign_status.sql`
- Modify: `src/main/java/.../application/projection/StudentReviewProjector.java`
- Modify: `src/main/java/.../persistence/StudentReviewFactReadRepository.java` (`updateCampaignStatus`)
- Modify: `src/main/java/.../persistence/ReportingReadRepository.java` (`pendingReviewCount` filter)
- Test: reporting projection + `pendingReviewCount` integration tests

**frontend**
- Modify: `src/pages/workspace/dashboard/drawers/StudentReviewDrawer.tsx` (Complete button + confirm)
- Modify: `src/api/dashboardCommandCenterApi.ts` (`completeReviewCampaign` — same file as the prerequisite repoint)

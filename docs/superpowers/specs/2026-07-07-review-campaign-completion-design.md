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

`POST /api/v1/reviews/campaigns/{campaignId}/complete`, in `StudentReadController`, gated on the internal token scope `student:write` and tenant scope (identical to the initiate endpoints). Returns the campaign's status DTO (same shape as `idCardReviewStatus` / `fullNameVerificationStatus`) so the caller can refresh in place.

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

Both item-mutating paths guard on the owning campaign's status **before** mutating:

- `updateReviewItem(itemId, patch)` (ID-card checklist updates)
- `verifyFullName(itemId, …)` (full-name confirm / request-correction)

Guard: read the item's campaign status; if `COMPLETED`, throw a dedicated `CampaignCompletedException` (new, in `student` persistence package) which the controller maps to **HTTP 409 CONFLICT** ("This campaign is completed and read-only."). Mirrors how `TimetableController` maps `YearLockedException` → 409.

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

## 7. Frontend — `StudentReviewDrawer.tsx`

Both tabs (`IdCardTab`, `FullNameTab`) already render an active-campaign view with a summary (`total`, `completed`, `needsCorrection`) and progress. Add:

- A **"Complete campaign"** button in the active-campaign header/summary area.
  - **Enabled only when every item is resolved**, i.e. `completed === total` (since an item counts as `completed` only when fully `COMPLETED`, this already excludes `PENDING` and `NEEDS_CORRECTION`). When disabled, show an inline hint with the remaining count, e.g. "42 student(s) still to review".
  - Clicking opens a confirm dialog ("Complete this campaign? It will be locked and can't be edited."). On confirm, call the new endpoint.
- New API function in `dashboardCommandCenterApi.ts`: `completeReviewCampaign(campaignId)` → `POST /reviews/campaigns/{campaignId}/complete`.
- On success: reload status. Because the campaign is no longer `ACTIVE`, the status endpoint returns no active campaign, so the tab returns to its "Initiate" screen — ready for the next campaign. Trigger `onMetricsRefresh` so the dashboard KPI updates.
- Button gated on the existing `canWrite` (`student:update`) like the other write actions.

## 8. Error handling summary

| Condition | HTTP | Message |
|-----------|------|---------|
| Complete a non-existent/invisible campaign | 400 | "Review campaign not found" |
| Complete a non-`ACTIVE` campaign | 400 | "This campaign is not active" |
| Complete with unresolved items | 400 | "`<n>` item(s) still need review — resolve them before completing the campaign." |
| Edit an item in a `COMPLETED` campaign | 409 | "This campaign is completed and read-only." |

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

**Frontend:** no tests (repo convention for these panels), unless requested.

## 10. Files

**school-core-service**
- Create: `src/main/resources/db/migration/student/V8__review_campaign_completion.sql`
- Create: `src/main/java/.../schoolcoreservice/persistence/CampaignCompletedException.java`
- Modify: `src/main/java/.../persistence/StudentReadRepository.java` (`completeCampaign`, freeze guards in `updateReviewItem`/`verifyFullName`)
- Modify: `src/main/java/.../api/StudentReadController.java` (complete endpoint; map `CampaignCompletedException` → 409)
- Test: `src/test/java/.../persistence/StudentReviewRepositoryIntegrationTest.java`

**platform-service**
- Create: `src/main/resources/db/migration/reporting/V21__review_campaign_status.sql`
- Modify: `src/main/java/.../application/projection/StudentReviewProjector.java`
- Modify: `src/main/java/.../persistence/StudentReviewFactReadRepository.java` (`updateCampaignStatus`)
- Modify: `src/main/java/.../persistence/ReportingReadRepository.java` (`pendingReviewCount` filter)
- Test: reporting projection + `pendingReviewCount` integration tests

**frontend**
- Modify: `src/pages/workspace/dashboard/drawers/StudentReviewDrawer.tsx`
- Modify: `src/api/dashboardCommandCenterApi.ts`

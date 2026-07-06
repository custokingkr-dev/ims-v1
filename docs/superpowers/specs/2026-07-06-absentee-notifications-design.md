# Attendance — Absentee Notifications (Sub-project 3)

**Date:** 2026-07-06
**Status:** Approved (design)
**Service:** `school-core-service` (`attendance` schema) and `frontend`

---

## Context & decomposition

Third and final sub-project of the Attendance rework:

1. Daily marking redesign + Late/Leave — DONE (merged, on dev).
2. Reporting & history — DONE (merged, on dev).
3. **Absentee notifications** — THIS spec.

## Problem

After marking attendance, a school has no way to tell parents their child was absent. This
sub-project lets a school admin review a day's absentees and **queue** a WhatsApp
notification to each parent. The **actual MSG91 delivery is deferred** — this ships the
review + queue + record; a later pass drains the queue to MSG91.

## Decisions (locked during brainstorming)

- **Trigger: manual.** The admin opens the day's absentee list and clicks *Notify absent
  parents*. No auto-send on submit, no scheduler (both deferred).
- **Scope: list + queue only.** Clicking *Notify* writes `QUEUED` rows; it does **not** call
  MSG91. The delivery worker that drains `QUEUED` → MSG91 → `SENT`/`FAILED` is a documented
  later pass.
- **Channel: WhatsApp.** Queued rows carry `channel = 'WHATSAPP'`.
- **Queue location: a school-core table** `attendance.absentee_notifications` (attendance's
  outbox of notification intents). Self-contained; no cross-service call yet; clean
  idempotency via `UNIQUE(student_id, attendance_date)`. NOT platform's `notification_logs`.
- **Triggering status: `ABSENT` only.** Late/Leave do not notify.
- **Recipient: `father_contact` → fallback `phone`** on the student. No contact → skipped and
  surfaced in the UI (not silently dropped).
- **Idempotency:** one queued notification per student per absence date. Re-clicking *Notify*
  skips already-queued students.
- **No automated tests this pass** (per decision) — verified by compile (backend) + build
  (frontend) + a manual dev check. A test pass is owed alongside the deferred delivery worker.
- **Scoping** identical to the rest of attendance: `requireToken` + `TenantScope` + ATTENDANCE
  module entitlement; `schoolScopedParams` on every call.

---

## Data model & backend (`school-core-service`, `attendance` schema)

### Migration V8 (`attendance` history)

> The `attendance` history's current max is V7 (sub-project 1; the reporting sub-project added
> no attendance migration). This is **V8** — confirm the next sequential version at plan time.

```sql
CREATE TABLE attendance.absentee_notifications (
    id                VARCHAR(255) PRIMARY KEY,
    school_id         BIGINT       NOT NULL,
    student_id        BIGINT       NOT NULL,
    class_id          VARCHAR(255) NOT NULL,
    section_id        VARCHAR(255) NOT NULL,
    academic_year_id  VARCHAR(255) NOT NULL,
    attendance_date   DATE         NOT NULL,
    parent_contact    VARCHAR(255) NOT NULL,
    channel           VARCHAR(20)  NOT NULL DEFAULT 'WHATSAPP',
    message           TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    queued_by         BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_absentee_notification UNIQUE (student_id, attendance_date)
);
CREATE INDEX idx_absentee_notif_school_date ON attendance.absentee_notifications (school_id, attendance_date);
```

Plus **RLS consistent with the other attendance tables** (they enable RLS with a
`tenant_isolation` policy keyed on the denormalized `school_id`; app_rt is the runtime role).
The migration enables RLS on `absentee_notifications` and adds the same
`tenant_isolation` USING/WITH CHECK policy on `school_id` that `attendance_daily` /
`attendance_student_records` use (copy that pattern verbatim from the existing enable-RLS
migration). This avoids the RLS-vs-write pitfalls seen elsewhere — app_rt writes/reads its
own tenant's rows; superadmin (no GUC → bypass) sees all.

### New endpoints — `AttendanceReadController` (`/api/v1/attendance`)

**1. List absentees for a date**

`GET /attendance/absentees?date=YYYY-MM-DD&sectionId=…(optional)&schoolId?`
`requireToken(token, "attendance:read")`, `TenantScope.resolveSchoolId`.

```jsonc
{
  "date": "2026-07-06",
  "sectionId": "3-1-A",              // echoes the filter, or null when all sections
  "students": [
    {
      "studentId": 1, "fullName": "Asha Rao", "admissionNo": "ADM1", "rollNo": "1",
      "classSection": "Class 1-A",
      "parentContact": "9876543210",  // father_contact ?? phone, or "" if none
      "hasContact": true,
      "alreadyQueued": false          // a QUEUED row exists for (studentId, date)
    }, …
  ],
  "totalAbsent": 3,
  "queuedCount": 1                    // how many of the absentees are already queued
}
```

- Absentees = `attendance_student_records` with `status = 'ABSENT'` for the date + active
  academic year + resolved school (+ optional `section_id`), joined to `student.students`
  (for name/admission/roll/contact, `deleted_at IS NULL`) and the class/section names.
- `parentContact = COALESCE(NULLIF(father_contact,''), NULLIF(phone,''), '')`; `hasContact`
  = non-blank.
- `alreadyQueued` = `EXISTS` a row in `absentee_notifications` for `(student_id, date)`.
- Ordered like the roster (roll_no numeric-aware, then name).

**2. Queue notifications**

`POST /attendance/absentees/notify` `{date, sectionId?, schoolId?, actorId?}`
`requireToken(token, "attendance:write")`, `TenantScope.resolveSchoolId`.

- Server **recomputes** the absentee set (does not trust a client-supplied list).
- For each absentee **with a contact** and **not already queued for that date**, insert one
  `absentee_notifications` row: `status='QUEUED'`, `channel='WHATSAPP'`, the rendered
  `message`, `queued_by=actorId`. The `UNIQUE(student_id, attendance_date)` +
  `ON CONFLICT DO NOTHING` makes re-clicks idempotent even under a race.
- Returns a summary:

```jsonc
{ "date": "2026-07-06", "queued": 2, "skippedNoContact": 1, "skippedAlreadyQueued": 1 }
```

### Message rendering (stored on the row)

Human-readable text, e.g.:

> `Dear Parent, Asha Rao (Class 1-A) was marked absent on 06 Jul 2026 at St. Claire School. Please contact the school if this is unexpected.`

- `{fullName}`, `{classSection}` from the roster; date formatted `dd MMM yyyy`; school name
  from `tenant_school.schools`. At delivery time (deferred) this maps to the approved
  WhatsApp template's variables; storing the rendered text now keeps the queue self-describing.

---

## Frontend (`frontend`)

### UI placement — a third tab

`AttendanceModulePanel` gains a third tab: **Mark | Reports | Absentees** (the existing host
from sub-project 2; add one tab + branch). Left-nav unchanged.

### `panels/AttendanceAbsenteePanel.tsx` (container)

- Controls: a **date** picker (default today) and an optional **class/section** filter
  (reuse the `/classes` → `/classes/{c}/sections` chain, same as the reports panel).
- Loads `GET /attendance/absentees` → renders a table: name, class-section, parent contact
  (or a muted "No contact"), and a status cell (`—` / `Queued`).
- A **Notify absent parents (WhatsApp)** button → `POST /attendance/absentees/notify` →
  success toast `Queued N · skipped M`, then reloads the list (statuses flip to `Queued`).
  Disabled while loading, when there are no notifiable absentees, or when all are already
  queued.
- Errors via the existing `ck-alert-re` banner.

### Types (`types/attendance.ts`)

- `AbsenteeStudent { studentId; fullName; admissionNo; rollNo; classSection; parentContact;
  hasContact; alreadyQueued }`, `AbsenteeListResponse { date; sectionId; students[];
  totalAbsent; queuedCount }`, `NotifyAbsenteesResponse { date; queued; skippedNoContact;
  skippedAlreadyQueued }`. Additive.

### Styling

- Reuse the `ck-att-*` / `ck-att-table` classes from sub-projects 1–2; a small
  `ck-att-absentee-*` addition to `attendance.css` only if needed. No new inline styles
  beyond the placeholder pattern already used.

---

## Scoping, auth, entitlement

- Both endpoints: internal token + `TenantScope.resolveSchoolId` (school-admin pinned to own
  school; a cross-school student/section → 403; superadmin unscoped). ATTENDANCE module gate.
- The queue table is RLS-protected on `school_id` (defense in depth), so even a mis-scoped
  query cannot read another tenant's queued notifications.

## Error handling

- Bad `date` / unknown section → 400 (`execute()` maps `IllegalArgumentException` /
  `DateTimeParseException`); cross-tenant → 403 (`SecurityException`).
- Empty absentee list → a valid empty response, not an error; the UI shows "No absentees for
  this date."
- `POST notify` with zero notifiable absentees → `{queued:0, …}` (no error).
- A student with no contact is **skipped and counted** (`skippedNoContact`), never queued
  with a blank recipient.

## Testing / verification (no automated tests this pass)

- Backend: `mvn -DskipTests compile` (JDK 25). Frontend: `npm run build`.
- Manual dev check: mark a student `ABSENT` (sub-project 1) → open **Absentees** for that
  date → the student appears with their parent contact → **Notify** → toast shows `Queued 1`
  → re-open shows status `Queued` and a second **Notify** reports `skippedAlreadyQueued`.
  Confirm a `QUEUED` row in `attendance.absentee_notifications` (via the dev-DB read
  procedure if needed). A full automated test pass is owed with the delivery worker.

## Out of scope (deferred, documented)

- **The MSG91 WhatsApp delivery worker** — drains `QUEUED` → platform-service/MSG91 →
  `SENT`/`FAILED` + delivery-status; needs an approved WhatsApp template + non-dry-run
  config + per-school SenderProfile (integrated number / namespace).
- Automatic-on-submit and scheduled-cutoff triggers.
- Per-school opt-in / notification settings; quiet hours.
- LATE notifications; mother/guardian/multiple contacts; SMS/email channels.
- Delivery receipts / read status surfaced in the UI.

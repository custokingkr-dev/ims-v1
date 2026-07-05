# Attendance — Daily Marking Redesign + Richer Statuses (Sub-project 1)

**Date:** 2026-07-06
**Status:** Approved (design)
**Service:** `school-core-service` (`attendance` schema) and `frontend`

---

## Context & decomposition

The user asked for a full Attendance rework. It was decomposed into three sequential
sub-projects, built in order:

1. **Daily marking redesign + richer statuses** — THIS spec (the foundation).
2. Reporting & history (monthly view, per-student history, CSV/PDF export) — later.
3. Absentee notifications — later.

This spec covers only sub-project 1. Reporting and notifications are explicitly out of
scope here and get their own spec → plan → build cycles.

## Problem

The current Attendance module (`AttendancePanel.tsx`, 665-line single component) works but:
looks dated and inconsistent (heavily inline-styled 600px slide-in drawer, hand-rolled
stat cards); marking is clunky (two tiny PRESENT/ABSENT buttons, no Late/Leave); poor on
mobile/tablet (the fixed drawer doesn't adapt); and the status model is only PRESENT/ABSENT.

Goal: a redesigned, design-system-consistent, mobile-first daily marking experience with a
richer status model (Present / Late / Leave / Absent), keeping the proven day →
section → mark → save/submit → lock workflow and the 5 existing endpoints (extended
additively).

---

## Decisions (locked during brainstorming)

- **Statuses:** `PRESENT`, `ABSENT`, `LATE`, `LEAVE`. (No Half-day.)
- **Counting:** `presentPercent = (Present + Late) / (Present + Late + Absent)`. **Leave is
  excused and excluded from the denominator.** A day of one each (P, Late, Leave, Absent) =
  2/3 = 67%.
- **Colors (semantic):** Present = green, Late = amber, Excused/Leave = blue, Absent = red.
- **Layout:** master-detail two-pane (section rail + roster) on desktop; **stacks on mobile**
  (rail → tap section → roster full-width with a "← Sections" back).
- **Marking control:** 4-way **segmented pills** per student (P / L / Ex / A), one tap sets
  the state, active pill fills its color.
- **`attendance_daily` gets `late_count` + `leave_count` columns** (store aggregates, don't
  recompute from records on every summary read).
- Keep: date picker, draft **Save** vs **Submit Section** (lock), **Submit day**, locked
  read-only states, per-student remarks, teacher name on the rail.
- Additive only: the 5 endpoints keep their shapes; new fields are added, none removed.

---

## Data model & backend (`school-core-service`, `attendance` schema)

### Migration (new `V…__attendance_late_leave.sql` in `attendance` history)

```sql
-- Allow the two new per-student statuses.
ALTER TABLE attendance.attendance_student_records DROP CONSTRAINT IF EXISTS attendance_student_records_status_check;
ALTER TABLE attendance.attendance_student_records
    ADD CONSTRAINT attendance_student_records_status_check
    CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'LEAVE'));

-- Aggregate late/leave alongside the existing present/absent counts on the day rollup.
ALTER TABLE attendance.attendance_daily ADD COLUMN IF NOT EXISTS late_count  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE attendance.attendance_daily ADD COLUMN IF NOT EXISTS leave_count INTEGER NOT NULL DEFAULT 0;
```

> The exact constraint name is verified against `V1__attendance_schema.sql` at plan time
> (it is the Postgres default `attendance_student_records_status_check`); `DROP … IF EXISTS`
> then re-add keeps the migration robust.

### Counting rules (single source of truth)

For any set of per-student records:
- `attended = count(PRESENT) + count(LATE)`
- `denominator = count(PRESENT) + count(LATE) + count(ABSENT)` (LEAVE excluded)
- `presentPercent = denominator == 0 ? 0 : round(attended * 100.0 / denominator)`
- Buckets surfaced: `presentCount` (PRESENT only), `lateCount`, `leaveCount`, `absentCount`.

> Note: `presentCount` stays PRESENT-only (Late is a distinct bucket); the **percent** is what
> folds Late into "attended". The UI shows the four buckets separately and the percent.

### `AttendanceReadRepository` changes

- `sectionRegister(...)`: derive the four buckets from the student records and compute
  `presentPercent` per the rule above; add `lateCount`, `leaveCount` to the returned map.
- `saveSectionRegister(...)`: persist each record's status (now one of the four); write
  `present_count`, `absent_count`, **`late_count`, `leave_count`** to `attendance_daily`.
- `dailySummary(...)`: read/compute the four buckets per section and the new `presentPercent`;
  add `lateCount`, `leaveCount` to each section entry. The day's `overallPercent` uses the
  same attended/denominator rule aggregated across sections.
- `submitAttendanceSection` / `submitAttendanceDay`: unchanged (they lock); "all marked" now
  means every student has one of the four statuses (none `null`).

### DTO validation

`SaveSectionRegisterRequest` (and its record item): status must be one of
`PRESENT|ABSENT|LATE|LEAVE` (reject others with 400). No shape change otherwise.

### Response additions (additive)

- `AttendanceDailySummaryResponse.sections[]`: add `lateCount`, `leaveCount`.
- `SectionRegisterResponse`: add `lateCount`, `leaveCount`.
- `StudentAttendanceRecord.status`: now one of the four values.

---

## Frontend architecture (`frontend`)

Break the monolith into focused, testable components (all design-system, no inline styles):

- `panels/AttendancePanel.tsx` — container: date state, loads `/daily-summary`, holds the
  selected section, orchestrates load/save/submit/submit-day, toasts/errors. Renders the
  two-pane layout (rail + roster) and handles the mobile stack.
- `panels/attendance/SectionRail.tsx` — the section list: each item shows section name,
  teacher, `presentPercent`, a compact `P·L·Ex·A` count line, and a status pill
  (Pending/Saved/Submitted). Selected item highlighted. On mobile it's the first screen.
- `panels/attendance/SectionRoster.tsx` — right pane: a summary row (Total / Present / Late /
  Leave / Absent using the `Stat` component or `ck-att-summary`), bulk **Mark all Present**,
  the roster of `StudentAttendanceRow`, and the footer (Cancel/Back · **Save** · **Submit
  Section**). Shows a "← Sections" back on mobile.
- `panels/attendance/StudentAttendanceRow.tsx` — avatar initials, name, admission no + roll,
  the **segmented P/L/Ex/A pills**, and an inline remarks input. Locked → pills render
  read-only (no `onClick`), styled disabled.

### Types (`types/attendance.ts`)

- `AttendanceStatus = 'PRESENT' | 'ABSENT' | 'LATE' | 'LEAVE'` (record status; local
  editable state also allows `null` = unmarked).
- Add `lateCount`, `leaveCount` to `AttendanceDailySummarySection` and
  `SectionRegisterResponse`.

### Layout & responsiveness

- **Desktop (≥ 900px):** grid — `SectionRail` fixed ~260px + `SectionRoster` fills the rest.
  Selecting a rail item swaps the roster in place (no drawer/overlay).
- **Mobile (< 900px):** show `SectionRail` full-width; selecting a section replaces it with
  `SectionRoster` full-width; a "← Sections" control returns to the rail. Driven by the
  selected-section state + a CSS breakpoint (`ck-att-*` classes), not JS user-agent sniffing.
- New CSS lives in `frontend/src/styles/attendance.css` (imported like the other `styles/*`),
  classes prefixed `ck-att-` (e.g. `ck-att-layout`, `ck-att-rail`, `ck-att-roster`,
  `ck-att-row`, `ck-att-pills`, `ck-att-pill` + `--present/--late/--leave/--absent`,
  `ck-att-summary`). Reuse existing tokens/colors (`--g`, `--am`, `--b`, `--re`).

### Marking interaction

- Tap a pill → sets that student's status; active pill fills its color. Tapping the active
  pill again clears to unmarked (`null`), matching today's clear-on-re-tap behavior.
- **Mark all Present** sets every unlocked student to PRESENT.
- Live summary counts update as pills change (client-side from local `studentRecords`).
- **Submit Section** enabled only when every student is marked (no `null`); Leave counts as
  marked.

---

## Data flow (reuse the 5 endpoints, extended)

Unchanged calls, extended payloads/responses:
1. `GET /attendance/daily-summary` `{date, schoolId?}` → sections now include `lateCount`,
   `leaveCount` and the new `presentPercent`.
2. `GET /attendance/section-register` `{date, classId, sectionId, schoolId?}` → students carry
   the 4-value `status`; response includes `lateCount`, `leaveCount`.
3. `PUT /attendance/section-register` `{…, records:[{studentId, status, remarks}], …}` — status
   is one of the four.
4. `POST /attendance/submit-section` — unchanged.
5. `POST /attendance/submit-day` — unchanged.

`schoolScopedParams` continues to scope non-platform-admins.

---

## Error handling

- Invalid status in a save → 400 (DTO validation) surfaced as the panel error banner.
- A locked section/day → write controls hidden/disabled (as today); a stale write to a locked
  section returns the existing error and the panel reloads.
- Load failures show the existing `ck-alert-re` banner; save/submit failures show the toast/
  error and do not advance the lock state.

---

## Testing

**Backend (`school-core-service`):**
- Migration applies (constraint allows the 4 values; the two columns exist) — Testcontainers.
- `saveSectionRegister` → `sectionRegister` round trip with a mix of P/Late/Leave/Absent:
  buckets correct; `presentPercent = (P+Late)/(P+Late+Absent)`; Leave excluded (e.g. 1 each →
  67%); `late_count`/`leave_count` written to `attendance_daily`.
- `dailySummary` aggregates the four buckets and the new percent per section.
- DTO rejects an unknown status with 400.

**Frontend (Vitest):**
- `StudentAttendanceRow`: tapping each pill sets the status; re-tapping the active pill clears
  it; locked row renders read-only (no status change on click).
- `SectionRoster`: summary counts update from local records; **Mark all Present** sets all;
  **Submit Section** disabled until all marked; Save/Submit call the right endpoints with the
  4-value statuses.
- `AttendancePanel`: selecting a rail section loads its roster; mobile stack shows rail →
  roster → back; desktop shows both panes.

---

## Out of scope (YAGNI / later sub-projects)

- Monthly/annual reports, per-student attendance history, CSV/PDF export (sub-project 2).
- Absentee notifications to parents/admin (sub-project 3).
- Half-day / custom statuses beyond the four.
- Backfilling historical PRESENT/ABSENT data (existing rows remain valid; new statuses are
  additive going forward).

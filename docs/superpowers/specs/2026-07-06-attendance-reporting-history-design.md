# Attendance — Reporting & History (Sub-project 2)

**Date:** 2026-07-06
**Status:** Approved (design)
**Service:** `school-core-service` (`attendance` schema, read-only) and `frontend`

---

## Context & decomposition

The Attendance rework was decomposed into three sequential sub-projects:

1. **Daily marking redesign + richer statuses** — DONE (merged, spec
   `2026-07-06-attendance-marking-redesign-design.md`). Shipped PRESENT/ABSENT/LATE/LEAVE,
   the `(P+Late)/(P+Late+Absent)` percent (Leave excused/excluded), `late_count`/`leave_count`
   day columns (V7), and the master-detail marking UI.
2. **Reporting & history** — THIS spec.
3. Absentee notifications — later (own spec → plan → build).

This spec covers only sub-project 2. It is **read-only** over the data sub-project 1
produces; it adds no marking/mutation and no new tables.

## Problem

Admins can mark daily attendance but cannot review or export it: there is no monthly
register, no per-student history, and no section comparison. The thin `GET /attendance/daily`
and `GET /attendance/records` endpoints exist but are unused, un-aggregated, and not
surfaced. This sub-project delivers three read-only reports plus CSV/PDF export.

## Decisions (locked during brainstorming)

- **Three read-only reports:** (a) **monthly register grid** (student × day matrix for a
  section), (b) **per-student history** (one student over a date range), (c) **section/class
  summary** (present% per section over a date range, ranked).
- **Read-only.** Correcting a past day stays in the sub-project-1 marking flow (reopen the
  section/day). No inline editing, no unlock/relock here.
- **Export = CSV + PDF, both, for all three views.** Server-side, returned as a blob with
  `Content-Disposition: attachment` — matching the existing fee-structure/receipt pattern
  (`GET …/export?format=pdf`, `GET …/{id}/pdf`, `responseType: 'blob'` on the client).
- **PDF via OpenPDF.** school-core's existing PDF path is a hand-rolled single-text-block
  generator (`FeeReadRepository.simplePdf`) that cannot render tables. Add **OpenPDF**
  (`com.github.librepdf:openpdf`, LGPL/MPL — license-safe, unlike AGPL iText) and a new
  `AttendanceReportPdf` builder that renders real multi-page tables. `simplePdf` (fees) is
  left untouched.
- **Counting reuses sub-project 1.** The same four buckets and the same
  `AttendanceReadRepository.attendancePercent(present, late, absent)` (Leave excluded from
  the denominator). No divergent metric — the reports and the marking screen always agree.
- **UI: an in-panel `Mark | Reports` tab switch** at the top of the Attendance module. The
  left-nav is unchanged (nav redesign is a separate backlog item). `AttendancePanel` remains
  "Mark"; a new `AttendanceReportsPanel` hosts the three reports as sub-tabs.
- **Scoping = same as sub-project 1.** `schoolScopedParams` on every call; `TenantScope`
  resolves the school (school-admin → own school, superadmin → all, zone-admin → their
  schools). All report endpoints require `X-Attendance-Service-Token` and are gated by the
  ATTENDANCE module entitlement.
- **Time controls:** register = month picker; history & summary = a date-range (default:
  the current month). Reports are scoped to the active academic year.

---

## Data model & backend (`school-core-service`, `attendance` schema — read-only)

**No schema changes.** All three reports aggregate the existing tables:

- `attendance_student_records` (`student_id, attendance_date, status, remarks, section_id,
  class_id, academic_year_id, school_id`) — per-student/day. Backs the register grid cells
  and the per-student history.
- `attendance_daily` (`section_id, attendance_date, present_count, absent_count, late_count,
  leave_count, total_enrolled, locked, academic_year_id, school_id`) — per-section/day
  rollups. Backs the section summary efficiently (no per-student scan over a range).

Status letter mapping (compact grid/CSV): **P**=PRESENT, **L**=LATE, **E**=LEAVE (Excused),
**A**=ABSENT, blank = unmarked. (The on-screen pills keep the sub-project-1 `P/L/Ex/A`
labels; the grid uses single letters for density.)

### New read endpoints (JSON) — `AttendanceReadController`, all `@GetMapping`, `requireToken("attendance:read")`, `TenantScope.resolveSchoolId(schoolId)`

**1. Monthly register grid**

`GET /attendance/report/register?month=YYYY-MM&classId=…&sectionId=…&schoolId?`

```jsonc
{
  "month": "2026-07",
  "monthLabel": "July 2026",
  "classId": "c1",
  "sectionId": "s1",
  "sectionName": "Class 1-A",
  "teacherName": "Ms Rao",
  "days": [ { "date": "2026-07-01", "dayOfMonth": 1, "weekday": "Tue", "nonWorkingDay": false }, … ], // every calendar day of the month
  "students": [
    {
      "studentId": 1, "admissionNo": "ADM1", "rollNo": "1", "fullName": "Asha Rao",
      "cells": [ { "date": "2026-07-01", "status": "PRESENT" }, … ],   // aligned 1:1 to days[]; status null = unmarked
      "presentCount": 18, "lateCount": 2, "leaveCount": 1, "absentCount": 1, "presentPercent": 95.2
    }, …
  ],
  "dayTotals": [ { "date": "2026-07-01", "presentCount": 28, "lateCount": 1, "leaveCount": 0, "absentCount": 1 }, … ], // aligned to days[]
  "totals": { "presentCount": 540, "lateCount": 20, "leaveCount": 8, "absentCount": 32, "presentPercent": 94.6 }
}
```

- Reads `attendance_student_records` for the month (`section_id`, active academic year),
  pivots per student into `cells` aligned to `days[]`.
- `nonWorkingDay = date.getDayOfWeek() == SUNDAY` (reuse sub-project 1's rule).
- Per-student `presentPercent = attendancePercent(presentCount, lateCount, absentCount)`;
  `totals.presentPercent` uses the section's summed buckets. Leave excluded throughout.
- Students ordered like the marking roster (roll_no numeric-aware, then name).

**2. Per-student history**

`GET /attendance/report/student?studentId=…&from=YYYY-MM-DD&to=YYYY-MM-DD&schoolId?`

```jsonc
{
  "student": { "studentId": 1, "admissionNo": "ADM1", "rollNo": "1", "fullName": "Asha Rao", "sectionName": "Class 1-A" },
  "from": "2026-07-01", "to": "2026-07-31",
  "days": [ { "date": "2026-07-01", "weekday": "Tue", "status": "PRESENT", "remarks": "", "nonWorkingDay": false }, … ], // recorded days only, chronological
  "presentCount": 18, "lateCount": 2, "leaveCount": 1, "absentCount": 1,
  "presentPercent": 95.2, "daysRecorded": 22
}
```

- Reads `attendance_student_records` for the student within `[from, to]` and the active year.
- `daysRecorded = days.length`; `presentPercent = attendancePercent(present, late, absent)`.
- The student's owning school is derived and re-checked against the resolved scope (a
  school-admin cannot read a student outside their school).

**3. Section/class summary**

`GET /attendance/report/summary?from=YYYY-MM-DD&to=YYYY-MM-DD&schoolId?`

```jsonc
{
  "from": "2026-07-01", "to": "2026-07-31",
  "sections": [
    {
      "classId": "c1", "sectionId": "s1", "sectionName": "Class 1-A", "teacherName": "Ms Rao",
      "presentCount": 540, "lateCount": 20, "leaveCount": 8, "absentCount": 32,
      "presentPercent": 94.6, "daysRecorded": 22
    }, …
  ],                                       // ranked by presentPercent desc, then sectionName
  "overall": { "presentCount": 5400, "lateCount": 180, "leaveCount": 70, "absentCount": 300, "presentPercent": 94.9 }
}
```

- Reads `attendance_daily` rollups within `[from, to]` for the scoped school(s), summing the
  four buckets per section; `daysRecorded` = count of `attendance_daily` rows for that
  section in range; `presentPercent = attendancePercent(ΣP, ΣL, ΣA)`.
- `overall` sums attended/denominator across sections (a true aggregate, not an average of
  percentages) — same rule as sub-project 1's `overallPercent`.

### Export endpoints (blob) — one per view

`GET /attendance/report/register/export?format=csv|pdf&month&classId&sectionId&schoolId?`
`GET /attendance/report/student/export?format=csv|pdf&studentId&from&to&schoolId?`
`GET /attendance/report/summary/export?format=csv|pdf&from&to&schoolId?`

- Same params + scoping as the JSON endpoints; `format` defaults to `csv`; an unsupported
  format → 400.
- Return `ResponseEntity<byte[]>` with `Content-Disposition: attachment; filename=…` and
  `Content-Type` `text/csv` or `application/pdf` (reuse the `pdf(...)`/response idiom).
- The export builds from the **same repository read method** as the JSON view, then formats.

**CSV formats**

- Register: header `Roll, Admission No, Name, <day-1…day-N as letters>, Present, Late, Leave, Absent, Present%`; one row per student; a trailing `Day totals` row (per-day P/L/E/A counts) and a `Section total` row. Unmarked cell = empty.
- Student history: a small header block (student, section, range, `Present%`, bucket counts) then `Date, Weekday, Status, Remarks` rows.
- Summary: header `Class, Section, Teacher, Present, Late, Leave, Absent, Present%, Days Recorded`; one row per section (ranked); a trailing `Overall` row.
- Quoting: reuse standard CSV quoting (wrap fields containing `,`/`"`/newline; `"` → `""`).

**PDF layouts (OpenPDF, real tables)**

- Register: title (`Class 1-A · July 2026`), landscape, a table with student rows × day
  columns (letters) + the four total columns + `Present%`; a totals footer row. Color the
  status letters (green/amber/blue/red) to match the buckets. Paginate columns/rows as
  OpenPDF wraps.
- Student history: title (student, section, range, `Present% NN.N%`), a `Date / Status /
  Remarks` table, a summary line of the four buckets.
- Summary: title (range), a `Class / Section / Teacher / P / L / E / A / % / Days` table
  (ranked), an `Overall` footer row.
- `AttendanceReportPdf` is a small dedicated builder (one method per view) so the table
  logic stays isolated and testable.

---

## Frontend (`frontend`)

### Host: `Mark | Reports` tab switch

- New `panels/AttendanceModulePanel.tsx` — a thin host holding the `'mark' | 'reports'` tab
  state and rendering either `AttendancePanel` (existing) or the new `AttendanceReportsPanel`.
  The workspace page's `attendance` case renders `AttendanceModulePanel` and passes
  `onRefresh` / `schoolScopedParams` straight through. `AttendancePanel` is otherwise
  unchanged.

### `panels/AttendanceReportsPanel.tsx` (container)

- Sub-tab state `'register' | 'student' | 'summary'`; shared controls (a **section picker**
  for register/student, a **student picker** for student history, a **month picker** for
  register, a **date-range** for student/summary); owns the report `GET`s and the export
  blob download; renders the active view + a shared **Export ▾ (CSV / PDF)** control.
- The section/student pickers reuse the school's existing class/section list (the same
  source the marking rail is fed) and a section→students lookup (reuse
  `/attendance/section-register` for the roster, or a lightweight students list) — no new
  student-directory endpoint.

### View components (presentational)

- `panels/attendance/reports/RegisterGrid.tsx` — the month matrix: sticky first columns
  (roll/name), day columns with color-coded letters, a totals column and a totals row;
  wrapped in an `overflow-x: auto` container so wide months scroll inside the panel, never
  the page body.
- `panels/attendance/reports/StudentHistory.tsx` — the student header + running % + a
  chronological `Date / Status / Remarks` list.
- `panels/attendance/reports/SectionSummary.tsx` — the ranked section table with the
  four-bucket breakdown, `Present%`, and days-recorded; an overall row.

### Export helper

- A small `downloadReport(path, params, format, filename)` util: `api.get(path, { params: {
  …, format }, responseType: 'blob' })` → `URL.createObjectURL` → anchor click → revoke.
  (The exact idiom already used by `InvoicesPage`/`FeesPanel` for PDF blobs.)

### Types (`types/attendance.ts`)

- Add `AttendanceRegisterReport`, `AttendanceStudentHistory`, `AttendanceSummaryReport`
  (+ their nested row/day/cell types), all reusing `AttendanceStatus` and the four bucket
  fields. Additive — no existing type changed.

### Styling

- New `ck-att-report-*` classes appended to `frontend/src/styles/attendance.css` (grid,
  sticky columns, letter cells `--present/--late/--leave/--absent`, summary table, tab
  switch). Reuse the existing color tokens; no inline styles beyond the loading/empty
  placeholders already used in sub-project 1.

---

## Scoping, auth, entitlement

- Every report + export endpoint: `requireToken("attendance:read")`, then
  `TenantScope.resolveSchoolId(schoolId)` — non-platform-admins are pinned to their school;
  a school-admin requesting another school's section/student gets a 403 (the section's /
  student's real `school_id` is re-checked, as in `sectionRegister`).
- Gated by the ATTENDANCE module entitlement (platform admins bypass).
- Gateway already routes `/api/v1/attendance/**` to school-core; the new `…/report/**`
  paths fall under that prefix (confirm in the plan).

## Error handling

- Bad/missing params (`month` not `YYYY-MM`, `from>to`, unknown section/student) → 400 via
  the controller's existing `execute(...)` mapping (`IllegalArgumentException`/
  `DateTimeParseException` → 400; `SecurityException` → 403).
- Unsupported `format` → 400.
- Empty result (no marked days in range) → a valid empty report (empty `students`/`days`/
  `sections`, zeroed totals, `presentPercent: 0`), not an error; the UI shows an empty state.
- Load/export failures surface via the existing `ck-alert-re` banner / toast; a failed
  export never corrupts the on-screen view.

## Testing

**Backend (`school-core-service`, Testcontainers `postgres:16`):**
- Seed one academic month of mixed P/L/E/A across ≥2 sections and several students, plus
  `attendance_daily` rollups. Assert:
  - **register**: `days[]` covers the month; a known student's `cells` align to `days[]`
    with the right statuses; per-student and section `presentPercent` follow
    `(P+Late)/(P+Late+Absent)` with Leave excluded; `dayTotals`/`totals` correct.
  - **student history**: recorded days chronological with status+remarks; running % + buckets
    + `daysRecorded` correct; cross-school student read is 403.
  - **summary**: sections summed from `attendance_daily`, ranked by `presentPercent` desc;
    `overall` is the attended/denominator aggregate; `daysRecorded` correct.
  - **export**: CSV contains the expected header + a known row; PDF bytes start with `%PDF`
    and are non-trivial (generation doesn't throw for each view); unsupported format → 400.
- A pure unit check that the register/summary percent equals `attendancePercent(...)` for a
  mixed sample (reuses the sub-project-1 helper — no second formula).

**Frontend (Vitest):**
- `RegisterGrid`: renders a cell matrix from a mock report, color letters per status, totals
  row/column present; wide-month container scrolls (has the overflow wrapper).
- `StudentHistory` / `SectionSummary`: render rows + running %/ranking from mock data.
- `AttendanceReportsPanel`: switching sub-tabs loads the right endpoint with the right
  params; the Export control calls `downloadReport` with `format=csv` / `format=pdf` and the
  current params.
- `AttendanceModulePanel`: the `Mark | Reports` switch renders `AttendancePanel` vs
  `AttendanceReportsPanel`.

## Out of scope (YAGNI / later)

- Editing/correcting past marks (stays in the sub-project-1 marking flow).
- Absentee notifications to parents/admin (sub-project 3).
- Term/annual roll-ups beyond a chosen range; cross-school district roll-ups beyond what
  superadmin scoping already yields; period/timetable-level attendance.
- Charts/graphs (the reports are tabular; visualization can be a later enhancement).
- Scheduled/emailed report delivery (a notifications concern).

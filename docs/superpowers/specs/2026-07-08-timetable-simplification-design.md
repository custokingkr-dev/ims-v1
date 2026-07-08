# Timetable Simplification — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** frontend (timetable module rewire), school-core-service (one small bulk-save endpoint).

The timetable setup is spread across three co-equal tabs — **Bell Schedules** (create a schedule, add timed periods + break flags, map classes to it), **Subjects** (define per-class subjects), **Weekly Grid** (assign subject+teacher per section×day×period). Users can't see how period-times/breaks → class/section → subjects connect, so they can't complete a working timetable. This is a **UX/flow** problem — the data model is correct and unchanged.

## Decisions (settled during brainstorming)
- School has **a few shared period-timing variants** (e.g. Primary / Senior / Half-day) that groups of classes use → keep the existing multi-`bell_schedule` + class→schedule-map model. This is a **flow** redesign, not a data change.
- Collapse the 3 tabs into **one class/section-anchored screen**; fold subjects into the grid; demote pattern management.
- Add two setup conveniences: a **"Same every day"** toggle and a **"copy day → all weekdays"** action.
- No data-model change; reuse existing timetable APIs; add ONE bulk-save endpoint so the conveniences don't fire N×5 calls. Past-year-immutable rule, teacher-per-slot, and RBAC/module gating all stay.

## The unified screen (replaces `TimetableModule`'s 3 tabs)
Anchored on **class + section + year** selectors at the top. One view:

1. **Period pattern dropdown** — the class's chosen bell-schedule variant (the existing class→schedule map, `PUT /timetable/class-schedules/{classId}`), made inline + obvious. Shows the pattern's period summary (times + breaks). A small **"Manage patterns"** link opens a lightweight editor (the existing `BellSchedulesPanel` capability: create/rename schedules, add/edit/reorder/delete periods with times + `is_break`) — kept but demoted from a co-equal tab to an occasional modal/drawer.
2. **The grid** — rows = the picked pattern's periods (label + time); **break periods render as non-editable spanning bars**. Columns = weekdays (or one "Every day" column, see below). Each teachable cell = a **subject picker** (searchable select over the class's subjects, `GET /timetable/class-subjects`) with an inline **"+ Add subject"** (`POST /timetable/class-subjects`) + an optional **teacher** picker. Assigning a cell calls the entry API.
3. **Subjects tab removed** — subjects are added/selected in-grid, per class. **Bell Schedules tab removed** — folded into "Manage patterns".

## Convenience A — "Same every day" toggle
- **ON:** grid shows a single **"Every day"** column; setting a cell writes that (subject, teacher) to **all five weekdays** for that period (via the bulk endpoint). The toggle auto-detects ON when a section's five days are already identical for every period.
- **OFF:** expands to the full Mon–Fri grid, pre-populated from the current values, for per-day editing.
- Behind the scenes the data is always per-day `school_timetable_entries`; the toggle is an editing/display convenience.

## Convenience B — "Copy day → all weekdays"
- In per-day mode, each weekday column header has a **⧉ copy** action that replicates that day's whole column (each period's subject + teacher, skipping breaks) to the other weekdays (via the bulk endpoint). For "mostly same, then tweak" setup.

## Backend — one bulk-save endpoint
Add `PUT /api/v1/timetable/entries/bulk` (school-core `TimetableController`), mirroring the single-entry guards: `requireToken("tenant-school:write")` + `TenantScope.requireSchoolAdmin()`, `YearLockedException → 409`, `IllegalArgumentException → 400`. Body: `{ sectionId, entries: [ { day, periodId, subjectName, teacherId? }, … ] }` — upserts all entries in one transaction (reuse `timetable.upsertEntry` per row inside a `@Transactional` service method, or a batch upsert). Both conveniences (same-every-day: same cell × 5 days; copy-day: source column × 4 target days) post through this. The existing single `PUT /timetable/entry` and `DELETE /timetable/entry` stay for individual cell edits/clears.

## Files (indicative — plan pins exact lines)
**frontend:** rewrite `pages/workspace/panels/TimetableModule.tsx` (one screen, drop the 3-tab switch), rework `TimetableGrid.tsx` (inline pattern dropdown, in-cell subject+teacher pickers with add-subject, same-every-day toggle, copy-day action), demote `setup/BellSchedulesPanel.tsx` to a "Manage patterns" modal/drawer, remove the `SubjectsMasterPanel` tab (its create/list flow moves in-grid). `services/timetableApi.ts` — add the bulk call. Reuse existing endpoints for schedules/periods/class-schedule-map/class-subjects/entries.
**school-core:** `api/TimetableController.java` (+ bulk endpoint), the timetable application service (+ bulk upsert method) + test.

## Testing
- **school-core (TDD):** the bulk endpoint upserts N entries atomically; is school-admin-gated (non-admin → 403); returns 409 on a past-year-locked section; 400 on a malformed row. Mirror the existing timetable controller/service tests.
- **frontend:** no unit tests (repo convention) — `npm run build` clean; manual acceptance that a school admin can, on ONE screen: pick class+section, pick a period pattern, drop subjects (+ add a new subject inline) into periods with breaks shown, use "same every day", and "copy Monday → all".

## Non-goals / stays the same
- No change to `school_bell_schedules`/`school_bell_periods`/`school_class_bell_map`/`school_class_subjects`/`school_timetable_entries` schema.
- Past-year immutability (`YearLockedException → 409`) unchanged. Teacher-per-slot unchanged. Read-only (non-admin) view unchanged (`editable=false`). Teacher-clash warnings are out of scope (possible later).

# Merged & Redesigned Timetable Module — Design

**Date:** 2026-07-07
**Status:** Approved for planning
**Scope:** Frontend only (`frontend/`). No backend, no migrations, no new endpoints — every API used already exists in `frontend/src/services/timetableApi.ts` and the school-core timetable controller.

---

## 1. Problem

Three tightly-coupled surfaces are scattered across two nav sections:

- **Timetable** (the weekly grid) — under *Academics* (`PanelKey` `timetable`, rendered by `TimetablePanel` → `TimetableGrid`). Pick class → section → year, fill each cell with a subject + teacher.
- **Bell schedules** — under *Setup* (`bellschedules`, `BellSchedulesPanel`). Named schedules with period timings + a class→schedule mapping. The grid's **period columns** come from here.
- **Subjects** — under *Setup* (`subjectsmaster`, `SubjectsMasterPanel`). Per-class-per-year subject master. The grid's **cell dropdown** comes from here.

To build a timetable an admin bounces between three nav locations, and the bell-schedule editor is a clunky per-row-save table (`<input>` per field, a Save button on every row, ↑/↓ swap reorder, and a separate class-mapping table at the bottom).

## 2. Goal

Consolidate all three into **one cohesive Timetable module** with internal tabs, and **redesign the bell-schedule editor** into a visual day timeline. Reduce nav-hopping, make the grid's dependencies (bell schedule, subjects) reachable in one place, and surface unconfigured state at the point of need.

## 3. Decisions (settled during brainstorming)

| # | Decision | Choice |
|---|----------|--------|
| 1 | Merge scope | **All three** — Grid + Bell Schedules + Subjects in one module |
| 2 | Layout | **Tabs**: Weekly Grid (default) · Bell Schedules · Subjects |
| 3 | Bell editor | **Visual day timeline** (time-ordered blocks, shaded breaks, chip-based class assignment) |

**Out of scope (YAGNI):** backend/API changes; a guided setup wizard; drag-and-drop on the grid itself; restyling the grid beyond light harmonization (it was revamped earlier this session). No FE tests (repo convention for these panels) unless requested.

## 4. Architecture

### 4.1 New wrapper: `TimetableModule.tsx`
A new component `frontend/src/pages/workspace/panels/TimetableModule.tsx` owns:
- **Tab state** (`'grid' | 'bell' | 'subjects'`, default `'grid'`).
- **A shared Academic-Year context** for all three tabs: the year selector (default = active year; past years are read-only, honoring the existing `editable = yearId === activeYearId` rules already enforced server-side and in the grid/subjects panels). Fetched once from `/academic-years` and passed down, so switching tabs keeps the same year.
- A tab bar rendered with the existing tab/pill styling used elsewhere (e.g. the `StudentReviewDrawer` tab bar pattern or `ModuleShell`), plus `ModuleShell` for the title/subtitle chrome.

The three tab bodies are the existing components, adapted to accept the shared year as a prop instead of each fetching/owning it:
- **Grid** → `TimetableGrid` (already fetches classes/sections/years; refactor to accept `yearId`/`years` from the module, or leave its internal fetch and just host it — see 4.4).
- **Bell** → the redesigned `BellSchedulesPanel` (§5).
- **Subjects** → `SubjectsMasterPanel` (moved in as-is, light harmonization).

### 4.2 Panel dispatch & routing
- `frontend/src/pages/workspace/config.ts`:
  - Keep `PanelKey` `'timetable'`; **remove** `'bellschedules'` and `'subjectsmaster'` from the `PanelKey` union, the nav section arrays (the *Setup* section), and `PANEL_TITLES`.
  - The *Academics* `timetable` nav item stays; its title/subtitle updated to reflect the combined module ("Timetable" / "Grid, bell schedules & subjects").
- The panel-render dispatch (where `PanelKey` maps to a component — the `timetable` case currently renders `TimetablePanel`) now renders `TimetableModule`. `TimetablePanel` is either deleted or becomes a thin alias; the plan picks one (prefer deleting it and rendering `TimetableModule` directly).
- Any code that navigates to the removed keys (`bellschedules`/`subjectsmaster`) is repointed to `timetable` (grep for the string keys). If nothing references them beyond config, no further change.

### 4.3 Permissions
Unchanged: the module is gated on the existing `timetable:read` (view) / `timetable:manage` (edit) codes already used by `TimetableGrid` and `BellSchedulesPanel`. `SubjectsMasterPanel`'s existing permission gate is preserved as-is within its tab. A user without manage permission sees read-only tabs / the existing "no permission" states.

### 4.4 Year-context refactor (minimal)
`TimetableGrid`, `BellSchedulesPanel`, and `SubjectsMasterPanel` each currently fetch their own year/class data. To share one year selector without a large rewrite: `TimetableModule` fetches `/academic-years` and renders the selector in its header; it passes the chosen `yearId` (and the `years` list) down as props. Each tab uses the passed `yearId` for its year-scoped reads (subjects, grid) instead of its own selector. Bell schedules are **not** year-scoped (they're school-level master data), so the Bell tab ignores the year for its schedule/period data but still shows the shared header. Keep each tab's own class/section pickers (they differ per tab).

## 5. Bell Schedules tab — redesign

Replaces the current table-based editor in `BellSchedulesPanel.tsx`. All existing API calls are reused unchanged: `getBellSchedules`, `createSchedule`, `renameSchedule`, `deleteSchedule`, `addPeriod`, `updatePeriod`, `deletePeriod`, `swapPeriods`, `getClassSchedules`, `setClassSchedule`.

### 5.1 Layout
Two-pane:
- **Left rail — schedule list.** Named schedules with select / rename / delete, and a "+ New schedule" input at the bottom. (Cleaned-up version of today's left card.)
- **Right — day timeline** for the selected schedule.

### 5.2 The timeline
- Each period renders as a **block ordered by start time**, with the `start`–`end` times in a left gutter and the label in the block body.
- **Breaks** (`isBreak = true`) are visually distinct (shaded background + a ☕/break marker).
- **Edit:** a pencil affordance on each block opens inline fields (label, start `type=time`, end `type=time`, break toggle); a single **Save** per block calls `updatePeriod`. (Auto-save-on-blur is acceptable as an implementation choice; a single explicit Save is the baseline.)
- **Reorder:** ↑/↓ controls (baseline, reusing `swapPeriods` exactly as today); drag-to-reorder is an acceptable enhancement if it maps to the same swap calls. Order is otherwise implied by the time gutter.
- **Add:** a "+ Add period" affordance at the bottom appends a period (sort order = max+1, mirroring today's `handleAddPeriod`).
- **Delete:** per-block delete calls `deletePeriod` (the server already blocks deleting a period referenced by a past-year timetable → 409 with a clear message; the panel surfaces it via the existing `errMsg` pattern).

### 5.3 Class assignment — chip row
The separate class→schedule mapping table is replaced by an **"Applies to:"** chip row at the top of the selected schedule's timeline:
- Shows the classes currently mapped to this schedule as chips (derived from `getClassSchedules`, filtering `scheduleId === selectedId`).
- **"+ Assign class"** opens a picker of classes not yet on this schedule; choosing one calls `setClassSchedule(classId, selectedId)`.
- Because each class maps to **exactly one** schedule (server enforces `UNIQUE(school_id, class_id)` via upsert), assigning a class here moves it off whatever schedule it was on — the chip rows on other schedules update on reload. This inversion (per-schedule "which classes use it") replaces today's per-class dropdown.
- **Unassigned classes** (any class with `scheduleId == null` in `getClassSchedules`) surface as a subtle prompt/badge somewhere in the tab (e.g. "2 classes have no bell schedule") so they aren't forgotten.

## 6. Weekly Grid & Subjects tabs

- **Grid** (`TimetableGrid`) moves into the Grid tab **as-is** (it was revamped earlier this session). Only light visual harmonization (spacing/typography to sit inside the tabbed module rather than its own `ModuleShell`). It keeps its class/section pickers; it consumes the shared `yearId` from the module.
- **Subjects** (`SubjectsMasterPanel`) moves into the Subjects tab as-is, consuming the shared `yearId`, with the same light harmonization.
- **Cross-tab prompt:** when the Grid tab's selected class has **no bell schedule** (the grid already detects this — `TimetableView.noSchedule === true`), show an inline "Set up this class's bell schedule →" link that switches the module to the **Bell** tab with that class pre-selected/scrolled-to. This makes the dependency actionable at the point of need. (One-directional; no other cross-tab wiring.)

## 7. Error handling

No new error surfaces. Each tab keeps its existing `errMsg(err) → err.response.data.message` pattern and inline alert. The past-year period-delete 409 and the subject year-lock 409 already return clear messages that the panels display.

## 8. Files

**frontend**
- Create: `src/pages/workspace/panels/TimetableModule.tsx` (tab wrapper + shared year header)
- Modify: `src/pages/workspace/config.ts` (drop `bellschedules`/`subjectsmaster` keys, nav entries, titles; update `timetable` label/subtitle)
- Modify: the panel-render dispatch so `timetable` → `TimetableModule` (and delete/alias `TimetablePanel.tsx`)
- Modify: `src/pages/workspace/panels/setup/BellSchedulesPanel.tsx` (timeline redesign + chip assignment; accepts it's now a tab body)
- Modify: `src/pages/workspace/panels/TimetableGrid.tsx` (accept shared `yearId`; light harmonization; keep `noSchedule` → cross-tab link)
- Modify: `src/pages/workspace/panels/setup/SubjectsMasterPanel.tsx` (accept shared `yearId`; light harmonization)
- No change: `src/services/timetableApi.ts` (all endpoints already present)

## 9. Testing

No frontend tests (repo convention for these workspace panels), unless requested. Acceptance is manual:
- The Timetable nav item opens the module on the Grid tab; Bell Schedules and Subjects are reachable as tabs; the two old Setup entries are gone.
- Bell tab: create/rename/delete schedules; add/edit/reorder/delete periods on the timeline; breaks render shaded; assign/unassign classes via chips (assigning moves a class off its previous schedule); unassigned-class prompt shows.
- Grid tab: unchanged behavior; the "no bell schedule" case shows the cross-tab link which lands on the Bell tab for that class.
- Subjects tab: unchanged behavior under the shared year.
- Past-year read-only and the two 409 guards (period-delete, subject year-lock) still surface their messages.
- `npm run build` clean.

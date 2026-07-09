# Timetable Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`).

**Goal:** Replace the timetable's three disconnected tabs with one class/section-anchored screen where a school admin sets the period pattern, drops subjects (+ teachers) into the grid inline, and uses "same every day" / "copy day → all" for fast setup.

**Architecture:** Frontend rewire on top of the existing timetable APIs (no data-model change) + one new bulk-save endpoint in school-core so the conveniences don't fire N×5 calls.

**Tech Stack:** React 18 + Vite + TS (frontend); Spring Boot 4.0.7 / Java 25 (school-core-service).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-08-timetable-simplification-design.md`.
- NO schema change. Reuse `frontend/src/services/timetableApi.ts` helpers (bell schedules, `getClassSchedules`/`setClassSchedule`, `getClassSubjects`/`addSubject`/`deleteSubject`, `getTimetable`/`putEntry`/`deleteEntry`). Period pattern = a class's bell schedule (`setClassSchedule(classId, scheduleId)`); the grid is per **section** (a section belongs to a class).
- Past-year immutability stays (`YearLockedException → 409`). Teacher-per-slot stays. Non-admin view stays read-only (`editable=false`). Breaks (`isBreak`) are never assignable.
- Backend endpoint guards mirror the existing single-entry endpoint: `requireToken("tenant-school:write")` + `TenantScope.requireSchoolAdmin()`.
- No FE unit tests (repo convention) — verify with `cd frontend && npm run build`. Backend gets tests (TDD).
- Do not commit local tool settings.

---

### Task 1: school-core-service — bulk timetable-entry upsert endpoint

**Files:**
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/TimetableController.java`
- Modify: the timetable application service (the bean behind `timetable.upsertEntry` — find it; e.g. `application/TimetableService.java`)
- Test: the existing timetable controller/service test (extend) or a new focused test.

**Interfaces:**
- Produces: `PUT /api/v1/timetable/entries/bulk` body `{ sectionId, entries: [ {day, periodId, subjectName, teacherId?}, … ] }` → returns the refreshed `TimetableView` (same shape `GET /api/v1/timetable` returns). Consumed by Task 3.

- [ ] **Step 1: Write the failing test.** In the timetable controller/service test (read it first for the harness — MockMvc or service-level), assert: a bulk request with 3 entries upserts all 3 (verify via the returned grid / repository); a non-school-admin caller → 403; a request whose section is in a past/locked year → 409; a row missing `subjectName`/`periodId` → 400. Mirror how the existing `PUT /timetable/entry` test drives auth/year-lock.

- [ ] **Step 2: Run — verify failure.** `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/school-core-service/pom.xml -q -Dtest='*Timetable*' test` → FAIL (endpoint/method absent).

- [ ] **Step 3: Add the service method.** In the timetable service, add `@Transactional Map<String,Object> upsertEntries(Long schoolId, String sectionId, List<Map<String,Object>> entries)` that loops the existing single-entry upsert logic per row (reuse `upsertEntry`'s validation + `YearLockedException`), then returns the grid via the existing `timetable(schoolId, sectionId, yearId)` builder. One year-locked/invalid row aborts the whole batch (transactional) with the same exception the controller maps.

- [ ] **Step 4: Add the controller endpoint.** In `TimetableController`, add `@PutMapping("/api/v1/timetable/entries/bulk")` mirroring `upsertEntry` (`:265`): `requireToken(token, "tenant-school:write")`, `TenantScope.requireSchoolAdmin()`, `schoolId = TenantScope.resolveSchoolId(null)`, read `sectionId` + the `entries` list from the body, validate each row's required fields, call `timetable.upsertEntries(...)`, and translate `YearLockedException → 409` / `IllegalArgumentException → 400` exactly as the single endpoint does.

- [ ] **Step 5: Run — GREEN + full school-core suite.** `… -q test` → BUILD SUCCESS.

- [ ] **Step 6: Commit.**
```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/TimetableController.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/application/ \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/
git commit -m "feat(school-core): bulk timetable-entry upsert endpoint (PUT /timetable/entries/bulk)"
```

---

### Task 2: frontend — one class/section-anchored timetable screen

**Files:**
- Rewrite: `frontend/src/pages/workspace/panels/TimetableModule.tsx` (drop the 3-tab switch → one screen)
- Rework: `frontend/src/pages/workspace/panels/TimetableGrid.tsx` (inline pattern dropdown, in-cell subject+teacher pickers, inline add-subject; breaks as spanning non-editable bars)
- Modify: `frontend/src/pages/workspace/panels/setup/BellSchedulesPanel.tsx` (present as a "Manage patterns" modal/drawer instead of a tab — keep its schedule/period CRUD)
- Remove usage: `setup/SubjectsMasterPanel.tsx` (its Subjects tab goes away; subject add/list moves in-grid). Leave the file or delete it if unreferenced.

**Interfaces:**
- Consumes: `timetableApi.ts` (Task-1 bulk not needed here), `/academic-years`, class + section lists (see how `TimetableGrid` currently loads classes/sections — reuse that source).

- [ ] **Step 1: Unified screen shell.** Rewrite `TimetableModule.tsx`: keep the year selector; add **class** and **section** selectors (load classes; sections filtered by class — reuse the class/section data source the current `TimetableGrid` already uses). Remove the `TABS`/tab state and the Bell/Subjects tabs. Render a single `TimetableGrid` for the selected class+section+year. Pass an `onManagePatterns` callback that opens the patterns modal (Step 4).

- [ ] **Step 2: Inline period-pattern dropdown.** In the grid header, show a **Period pattern** `<select>` bound to the selected class's schedule (`getClassSchedules()` → the row for `classId`; `setClassSchedule(classId, scheduleId)` on change). Options = `getBellSchedules()`. Show a one-line summary of the chosen schedule's periods (times + which are breaks). A **"Manage patterns"** button/link calls `onManagePatterns`. If the class has no schedule (`TimetableView.noSchedule` / null scheduleId), show a clear "Pick a period pattern to start" prompt instead of an empty grid.

- [ ] **Step 3: Grid with in-cell subject + teacher + inline add-subject.** Load the grid via `getTimetable(sectionId, yearId)` → `{days, periods, entries, editable}`. Render rows = `periods` (label + `start`–`end`); a period with `isBreak` renders as a single **spanning break bar** across all day columns (non-editable). Columns = `days`. Each teachable cell:
  - a **subject select** (options from `getClassSubjects(classId, yearId).subjects`) + an **"+ Add subject"** affordance that prompts for a name and calls `addSubject(classId, name)` then refreshes the subject list and selects it;
  - an optional **teacher select** (from the `staff` prop already passed to the module).
  - Changing subject or teacher calls `putEntry({sectionId, day, periodId, subjectName, teacherId})`; clearing calls `deleteEntry(...)`. Reflect the returned/refetched grid. When `editable === false` (non-admin), render read-only cells (no selects).

- [ ] **Step 4: "Manage patterns" modal.** Wrap the existing `BellSchedulesPanel` schedule/period CRUD (create/rename/delete schedule, add/edit/reorder/delete periods with `start`/`end`/`isBreak`) in a modal/drawer opened from Step 2's link. Reuse the panel's existing logic/markup; just host it in a modal rather than a tab. On close, refresh the pattern dropdown + grid so new/edited periods appear.

- [ ] **Step 5: Build.** `cd frontend && npm run build` — clean (no TS errors; no dangling imports from the removed tabs/`SubjectsMasterPanel`).

Manual acceptance: on ONE screen, a school admin picks class+section, picks a period pattern (or opens Manage patterns to create one), and assigns subjects (+ teachers) to periods with breaks shown as bars.

- [ ] **Step 6: Commit.**
```bash
git add frontend/src/pages/workspace/panels/TimetableModule.tsx \
        frontend/src/pages/workspace/panels/TimetableGrid.tsx \
        frontend/src/pages/workspace/panels/setup/BellSchedulesPanel.tsx \
        frontend/src/pages/workspace/panels/setup/SubjectsMasterPanel.tsx
git commit -m "feat(fe): unified one-screen timetable (inline pattern dropdown, in-grid subjects+teachers, manage-patterns modal)"
```

---

### Task 3: frontend — "same every day" toggle + "copy day → all", via the bulk endpoint

**Files:**
- Modify: `frontend/src/services/timetableApi.ts` (add `putEntriesBulk`)
- Modify: `frontend/src/pages/workspace/panels/TimetableGrid.tsx` (toggle + copy action)

**Interfaces:**
- Consumes: Task-1 `PUT /timetable/entries/bulk`.

- [ ] **Step 1: Add the bulk api helper.**
```ts
export const putEntriesBulk = (b: { sectionId: string; entries: { day: string; periodId: number; subjectName: string; teacherId: number | null }[] }) =>
  api.put<TimetableView>('/timetable/entries/bulk', b);
```

- [ ] **Step 2: "Same every day" toggle.** Add a `sameEveryDay` boolean to the grid header, auto-initialized to `true` when — for every non-break period — the assignment (subjectName + teacherId) is identical across all `days` (and at least one is set); else `false`. When **ON**, render a single **"Every day"** column: each cell edit builds one entry per weekday for that period and posts them via `putEntriesBulk({ sectionId, entries })` (all `days` × that period). When toggled **OFF**, expand to the full per-day grid (Step 3 of Task 2) pre-filled from current values — no write needed on toggle itself. When toggled **ON** from a differing state, confirm ("this will set every day to match…") then, on the next cell edit, apply to all days.

- [ ] **Step 3: "Copy day → all weekdays".** In per-day mode, add a small **⧉** button on each weekday column header. On click, read that day's assignments for every non-break period and `putEntriesBulk` them to all OTHER `days` (same subject+teacher per period; skip periods with no assignment and skip breaks). Refresh the grid from the returned view.

- [ ] **Step 4: Build.** `cd frontend && npm run build` — clean.

Manual acceptance: toggling "same every day" edits one column that applies Mon–Fri; "copy Monday → all" replicates Monday across the week; both persist and survive refresh.

- [ ] **Step 5: Commit.**
```bash
git add frontend/src/services/timetableApi.ts frontend/src/pages/workspace/panels/TimetableGrid.tsx
git commit -m "feat(fe): timetable same-every-day toggle + copy-day-to-all (bulk save)"
```

---

## Self-Review

**Spec coverage:** one class/section screen (Task 2 Steps 1-3), inline pattern dropdown + demoted Manage-patterns (Task 2 Steps 2/4), subjects in-grid / Subjects tab removed (Task 2 Step 3), bulk endpoint (Task 1), same-every-day (Task 3 Step 2), copy-day (Task 3 Step 3). No schema change; guards/year-lock reused.

**Placeholder scan:** the FE steps describe target behavior + exact API calls rather than full component source (a UI rewrite over existing components — the implementer reads `TimetableGrid`/`BellSchedulesPanel`/`SubjectsMasterPanel` and `timetableApi.ts` to preserve patterns); the non-obvious logic (same-every-day detection, copy-day fan-out, bulk payload shape) is given in full. "find the timetable service bean" / "reuse the current class/section data source" are read-first instructions at named files.

**Type/behavior consistency:** the bulk payload `{sectionId, entries:[{day, periodId, subjectName, teacherId?}]}` is identical in Task 1 (endpoint), Task 3 Step 1 (`putEntriesBulk`), and Task 3 Steps 2-3 (callers). `setClassSchedule(classId, scheduleId)` (pattern pick) and `getTimetable(sectionId, yearId)` (grid) consistent with `timetableApi.ts`. Breaks (`isBreak`) non-editable across all modes.

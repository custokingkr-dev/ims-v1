# Merged & Redesigned Timetable Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge the Timetable grid, Bell Schedules, and Subjects into one tabbed Timetable module, and redesign the bell-schedule editor as a visual day timeline.

**Architecture:** A new `TimetableModule` wrapper renders a tab bar (Weekly Grid · Bell Schedules · Subjects) over the three existing panels, with a shared academic-year header. The two Setup nav entries are removed; the panel dispatch routes `timetable` → `TimetableModule`. Frontend-only — every API already exists in `frontend/src/services/timetableApi.ts`.

**Tech Stack:** React 18 + Vite + TypeScript. Build/verify: `cd frontend && npm run build` (must be TS-error-clean). No frontend tests (repo convention for these workspace panels).

## Global Constraints

- **Frontend only.** No backend, no migrations, no new endpoints. Reuse the existing `timetableApi.ts` functions verbatim: `getBellSchedules, createSchedule, renameSchedule, deleteSchedule, addPeriod, updatePeriod, deletePeriod, swapPeriods, getClassSchedules, setClassSchedule, getClassSubjects, addSubject, deleteSubject, getTimetable, putEntry, deleteEntry`.
- Tabs: `'grid' | 'bell' | 'subjects'`, **default `'grid'`**.
- Permissions unchanged: `timetable:read` (view) / `timetable:manage` (edit). Preserve each panel's existing permission gate and read-only / no-permission states.
- Each class maps to **exactly one** bell schedule (server enforces `UNIQUE(school_id, class_id)`), so assigning a class to a schedule moves it off any previous one.
- Bell schedules are **school-level** (not year-scoped); only the Grid and Subjects tabs use the shared year.
- Past years are read-only (existing `editable = yearId === activeYearId` rules; server returns 409 on period-delete-referenced-by-past-year and on subject year-lock — surface via the existing `errMsg(err) → err.response.data.message` pattern).
- No automated tests; each task verifies with `npm run build` clean + the manual acceptance checks listed.
- Style with the existing class vocabulary already used by these panels (`ModuleShell`, `ck-card`, `ck-card-h`, `ck-card-t`, `ck-btn`, `ck-btn-g`, `ck-btn-ghost`, `ck-alert`, `ck-actions-inline`, `ck-table`, `ck-panel-stack`, `ck-import-zone`, `ts`, `var(--ink3)`, `var(--am)`). Do not invent a new design system.

---

### Task 1: Tabbed `TimetableModule` + nav/dispatch rewire

**Files:**
- Create: `frontend/src/pages/workspace/panels/TimetableModule.tsx`
- Modify: `frontend/src/pages/UnifiedWorkspacePage.tsx` (imports ~20/26/27; dispatch lines 428, 430, 436)
- Modify: `frontend/src/pages/workspace/config.ts` (PanelKey union line 24; Setup nav lines 81-82; PANEL_TITLES lines 232-233; timetable label/subtitle)
- Delete: `frontend/src/pages/workspace/panels/TimetablePanel.tsx`

**Interfaces:**
- Consumes: existing `TimetableGrid` (props `{ readOnly?, staff? }`), `BellSchedulesPanel` (no props), `SubjectsMasterPanel` (no props).
- Produces: `TimetableModule` component with props `{ readOnly?: boolean; staff?: Array<{ id: number | string; name: string }> }`, rendering a `'grid' | 'bell' | 'subjects'` tab bar (default `'grid'`) over the three panels.

- [ ] **Step 1: Create `TimetableModule.tsx` with the tab shell**

```tsx
import { useState } from 'react';
import { ModuleShell } from '../ui';
import { TimetableGrid } from './TimetableGrid';
import { BellSchedulesPanel } from './setup/BellSchedulesPanel';
import { SubjectsMasterPanel } from './setup/SubjectsMasterPanel';

interface Props {
  readOnly?: boolean;
  staff?: Array<{ id: number | string; name: string }>;
}

type TabKey = 'grid' | 'bell' | 'subjects';

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'grid', label: 'Weekly Grid' },
  { key: 'bell', label: 'Bell Schedules' },
  { key: 'subjects', label: 'Subjects' },
];

export function TimetableModule({ readOnly, staff }: Props) {
  const [tab, setTab] = useState<TabKey>('grid');
  return (
    <ModuleShell title="Timetable" subtitle="Weekly grid, bell schedules & subjects">
      <div className="ck-panel-stack">
        <div style={{ display: 'flex', gap: 4, borderBottom: '2px solid var(--border)' }}>
          {TABS.map((t) => (
            <button
              key={t.key}
              className="ck-btn ck-btn-ghost"
              onClick={() => setTab(t.key)}
              style={{
                borderRadius: '6px 6px 0 0',
                borderBottom: tab === t.key ? '2px solid var(--g2)' : '2px solid transparent',
                fontWeight: tab === t.key ? 600 : 400,
                marginBottom: -2,
              }}
            >
              {t.label}
            </button>
          ))}
        </div>
        {tab === 'grid' && <TimetableGrid readOnly={readOnly} staff={staff} />}
        {tab === 'bell' && <BellSchedulesPanel />}
        {tab === 'subjects' && <SubjectsMasterPanel />}
      </div>
    </ModuleShell>
  );
}
```

> Note: the child panels each still wrap themselves in their own `ModuleShell` at this stage — that produces a nested title. Task 4 harmonizes that (drops the child `ModuleShell` chrome for tab bodies). Leaving it for Task 1 keeps this task to structure-only and still builds/renders.

- [ ] **Step 2: Rewire the dispatch in `UnifiedWorkspacePage.tsx`**

Replace the import on line 20:
```tsx
import { TimetableModule } from './workspace/panels/TimetableModule';
```
Remove the `BellSchedulesPanel` and `SubjectsMasterPanel` imports (lines 26-27).

Replace the dispatch. Delete lines 428 and 430 (the `bellschedules` / `subjectsmaster` cases). Change line 436 to:
```tsx
          {panel === 'timetable' && <TimetableModule readOnly={isTeacher} staff={workspace?.staff} />}
```

- [ ] **Step 3: Update `config.ts`**

- Line 24: remove `| 'bellschedules' | 'subjectsmaster'` from the `PanelKey` union.
- Lines 81-82: remove the two Setup nav items (`bellschedules`, `subjectsmaster`).
- Lines 232-233: remove the two `PANEL_TITLES` entries.
- Line 66: update the Academics `timetable` item label/subtitle intent — keep `label: 'Timetable'`; update its `PANEL_TITLES` entry (line 227 `timetable: 'Timetable'`) to stay `'Timetable'` (the subtitle lives in `TimetableModule`'s `ModuleShell`, so no title change needed).

- [ ] **Step 4: Delete `TimetablePanel.tsx`**

```bash
rm frontend/src/pages/workspace/panels/TimetablePanel.tsx
```
Confirm nothing else imports it: `grep -rn "TimetablePanel" frontend/src` returns nothing.

- [ ] **Step 5: Build**

Run: `cd frontend && npm run build`
Expected: clean (no TS errors; no unused-import errors for the removed panels).

Manual acceptance: the Timetable nav item opens the module on the Grid tab; Bell Schedules and Subjects are reachable as tabs; the two old Setup nav entries (Bell schedules, Subjects) are gone.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/workspace/panels/TimetableModule.tsx frontend/src/pages/UnifiedWorkspacePage.tsx frontend/src/pages/workspace/config.ts
git rm frontend/src/pages/workspace/panels/TimetablePanel.tsx
git commit -m "feat(fe): merge timetable grid + bell schedules + subjects into one tabbed module"
```

---

### Task 2: Shared academic-year header

**Files:**
- Modify: `frontend/src/pages/workspace/panels/TimetableModule.tsx` (own the year selector; pass `yearId`/`years` down)
- Modify: `frontend/src/pages/workspace/panels/TimetableGrid.tsx` (accept `yearId`/`years` props; drop own year fetch/selector when provided)
- Modify: `frontend/src/pages/workspace/panels/setup/SubjectsMasterPanel.tsx` (accept `yearId`/`years` props; drop own year fetch/selector when provided)

**Interfaces:**
- Produces: `AcademicYearOpt = { id: string; label: string; active: boolean }`. `TimetableModule` fetches `/academic-years` once and passes `yearId: string` and `years: AcademicYearOpt[]` to the Grid and Subjects tabs. `TimetableGrid` and `SubjectsMasterPanel` gain optional props `{ yearId?: string; years?: AcademicYearOpt[] }`; when `yearId` is provided they use it and render NO internal year `<select>`; when absent they keep their current self-fetching behavior (back-compat).

- [ ] **Step 1: Add the shared year state + header to `TimetableModule`**

Add near the top of the component:
```tsx
import { useEffect } from 'react';
import api from '../../../services/api';
// ...
interface AcademicYearOpt { id: string; label: string; active: boolean }
// inside the component:
  const [years, setYears] = useState<AcademicYearOpt[]>([]);
  const [yearId, setYearId] = useState('');
  useEffect(() => {
    void api.get<AcademicYearOpt[]>('/academic-years')
      .then((r) => {
        const list = Array.isArray(r.data) ? r.data : [];
        setYears(list);
        const active = list.find((y) => y.active);
        setYearId((prev) => prev || active?.id || list[0]?.id || '');
      })
      .catch(() => setYears([]));
  }, []);
```
Render a year `<select>` in the module header (above the tab bar), and pass `yearId`/`years` to the Grid and Subjects tabs:
```tsx
        <div className="ck-actions-inline" style={{ justifyContent: 'flex-end' }}>
          <select value={yearId} onChange={(e) => setYearId(e.target.value)}>
            {years.map((y) => <option key={y.id} value={y.id}>{y.label}{y.active ? ' (current)' : ''}</option>)}
          </select>
        </div>
        {/* tab bar ... */}
        {tab === 'grid' && <TimetableGrid readOnly={readOnly} staff={staff} yearId={yearId} years={years} />}
        {tab === 'bell' && <BellSchedulesPanel />}
        {tab === 'subjects' && <SubjectsMasterPanel yearId={yearId} years={years} />}
```

- [ ] **Step 2: Make `SubjectsMasterPanel` accept the shared year**

Add props and use them:
```tsx
interface Props { yearId?: string; years?: AcademicYearOpt[] }
export function SubjectsMasterPanel({ yearId: yearIdProp, years: yearsProp }: Props = {}) {
```
When `yearIdProp` is provided: use it as the effective `yearId` (do not fetch `/academic-years`, do not render the year `<select>`); still fetch `/classes` and keep the class selector. When absent: keep the existing self-managed year state + selector. Concretely: derive `const yearId = yearIdProp ?? internalYearId;`, guard the year fetch `if (!yearIdProp) { fetch years... }`, and render the year `<select>` only when `!yearIdProp`. The rest (`getClassSubjects(classId, yearId)`, editable/lock logic) is unchanged.

- [ ] **Step 3: Make `TimetableGrid` accept the shared year**

Same pattern: add `{ yearId?: string; years?: AcademicYearOpt[] }` to its `Props`. When `yearId` is provided, use it as the effective year and do not fetch `/academic-years` or render an internal year selector; when absent, keep current behavior. The grid's `getTimetable(sectionId, yearId)` call is unchanged. Keep the class/section pickers.

- [ ] **Step 4: Build**

Run: `cd frontend && npm run build`
Expected: clean.

Manual acceptance: one year selector in the module header controls both the Grid and Subjects tabs; switching tabs preserves the selected year; the Bell tab ignores it (bell schedules aren't year-scoped). Past-year selection makes Grid/Subjects read-only as before.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/TimetableModule.tsx frontend/src/pages/workspace/panels/TimetableGrid.tsx frontend/src/pages/workspace/panels/setup/SubjectsMasterPanel.tsx
git commit -m "feat(fe): shared academic-year header across timetable tabs"
```

---

### Task 3: Bell Schedules timeline redesign + chip class-assignment

**Files:**
- Modify: `frontend/src/pages/workspace/panels/setup/BellSchedulesPanel.tsx` (replace the periods table with a day timeline; replace the class-mapping table with a chip row)

**Interfaces:**
- Consumes: all existing bell API functions (unchanged) and the existing `BellSchedule`/`BellPeriod`/`ClassScheduleRow` types from `timetableApi`.
- Produces: the same panel component (still no required props), with a redesigned body.

Keep ALL existing handlers as-is (`handleCreateSchedule`, `handleRenameSchedule`, `handleDeleteSchedule`, `handleAddPeriod`, `handleUpdatePeriod`, `handleDeletePeriod`, `handleMovePeriod`, `handleClassScheduleChange`) and the existing `load()` — only the JSX and a small amount of derived state change. The left "Schedules" list card stays functionally the same (light cleanup allowed).

- [ ] **Step 1: Replace the periods table with a vertical day timeline**

For the selected schedule, render `sortedPeriods` (already sorted by `sortOrder`) as stacked blocks instead of table rows. Each block shows the `start`–`end` in a left time gutter and the `label` in the body; break periods (`period.isBreak`) get a shaded background and a ☕/"Break" marker. Structure per block:
- Display mode: time gutter + label + a break indicator, plus small controls: a pencil/"Edit" button (enters inline edit for that block), ↑/↓ buttons (call `handleMovePeriod(period, 'up'|'down')`, disabled at ends exactly as today), and a Delete button (`handleDeletePeriod(period.id)`).
- Inline-edit mode (track `editingPeriodId` in local state): render the `label` text input, `start`/`end` `type="time"` inputs, and an `isBreak` checkbox bound to the same `setSchedules(...)` optimistic-update pattern already in the file; a single **Save** button calls `handleUpdatePeriod(period)` then clears `editingPeriodId`; a Cancel reverts by calling `load()`.
- Below the blocks, an "+ Add period" affordance that reveals the existing `newPeriod` inputs (label + start + end + break) and calls `handleAddPeriod` (unchanged, including the `sortOrder = max+1` computation).

Add the local state: `const [editingPeriodId, setEditingPeriodId] = useState<number | null>(null);`. Use the existing per-field `setSchedules(prev => prev.map(...))` optimistic updates verbatim for the edit inputs. Style blocks with `ck-card`-like surfaces; the break shade can be an inline `style={{ background: 'var(--g1)' }}` (or an existing muted token) — do not invent new CSS classes.

- [ ] **Step 2: Replace the class-mapping table with an "Applies to" chip row**

Remove the bottom "Class schedules" table. At the top of the selected schedule's timeline pane, render:
- **Assigned chips:** derive from `classSchedules` — `const assigned = classSchedules.filter((c) => c.scheduleId === selectedSchedule.id);` — render each as a chip showing `className`.
- **"+ Assign class"** control: a `<select>` (or button-triggered menu) of classes NOT on this schedule — `const unassignedToThis = classSchedules.filter((c) => c.scheduleId !== selectedSchedule.id);` — choosing one calls the existing `handleClassScheduleChange(classId, String(selectedSchedule.id))` (which calls `setClassSchedule` then `load()`). Because of the one-schedule-per-class rule, this moves the class off its previous schedule automatically; the chip rows refresh on `load()`.
- **Unassigned prompt:** compute `const unmapped = classSchedules.filter((c) => c.scheduleId == null);` and, if non-empty, show a subtle banner in the pane: `{unmapped.length} class(es) have no bell schedule` (use `ck-alert ck-alert-am` styling), listing or hinting the class names.

- [ ] **Step 3: Build**

Run: `cd frontend && npm run build`
Expected: clean.

Manual acceptance: selecting a schedule shows its periods as a time-ordered timeline with shaded breaks; editing a block saves via `updatePeriod`; ↑/↓ reorder still works via `swapPeriods`; adding/deleting periods works; the "Applies to" chips show mapped classes; "+ Assign class" moves a class onto this schedule (and off its old one on reload); the unassigned-class banner appears when some class has no schedule; the past-year period-delete 409 message still surfaces.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/workspace/panels/setup/BellSchedulesPanel.tsx
git commit -m "feat(fe): redesign bell schedules as a day timeline with chip class-assignment"
```

---

### Task 4: Cross-tab prompt + visual harmonization

**Files:**
- Modify: `frontend/src/pages/workspace/panels/TimetableModule.tsx` (lift tab control so the Grid can request the Bell tab; pass an initial class to Bell)
- Modify: `frontend/src/pages/workspace/panels/TimetableGrid.tsx` (when `TimetableView.noSchedule`, show a "Set up this class's bell schedule →" link)
- Modify: `frontend/src/pages/workspace/panels/setup/BellSchedulesPanel.tsx` (accept an optional `initialClassId` to pre-focus assignment)
- Modify: `TimetableGrid.tsx` / `BellSchedulesPanel.tsx` / `SubjectsMasterPanel.tsx` (drop the inner `ModuleShell` chrome when hosted as a tab body, to avoid the nested title from Task 1)

**Interfaces:**
- Consumes: `TimetableView.noSchedule` (already returned by `getTimetable`), the module's tab setter.
- Produces: `TimetableGrid` gains an optional `onNeedBellSetup?: (classId: string) => void` prop; `BellSchedulesPanel` gains an optional `initialClassId?: string` prop. `TimetableModule` wires them: `onNeedBellSetup(classId)` sets `tab='bell'` and stores the class so `BellSchedulesPanel` can pre-focus its "+ Assign class" on that class (best-effort; no hard requirement to auto-assign).

- [ ] **Step 1: Wire the cross-tab handoff in `TimetableModule`**

Add `const [bellInitialClassId, setBellInitialClassId] = useState<string | undefined>();`. Pass `onNeedBellSetup={(classId) => { setBellInitialClassId(classId); setTab('bell'); }}` to `TimetableGrid`, and `initialClassId={bellInitialClassId}` to `BellSchedulesPanel`.

- [ ] **Step 2: Add the Grid prompt**

In `TimetableGrid`, where it currently detects `data?.noSchedule` (the "no schedule" state), render a link/button: `Set up this class's bell schedule →` that calls `onNeedBellSetup?.(classId)`. Add `onNeedBellSetup?: (classId: string) => void` to its `Props`.

- [ ] **Step 3: Pre-focus the Bell tab (best-effort)**

In `BellSchedulesPanel`, accept `initialClassId?: string`. On mount/when it changes and classes are loaded, if that class is unassigned, scroll/expand the "+ Assign class" affordance toward it (a simple approach: pre-select the class in the assign `<select>`). No auto-mutation — the admin still confirms the assignment.

- [ ] **Step 4: Harmonize — drop nested `ModuleShell` in tab bodies**

`TimetableModule` already provides the `ModuleShell` title. Change `TimetableGrid`, `BellSchedulesPanel`, and `SubjectsMasterPanel` so that, when rendered as a tab body, they do NOT wrap in their own `ModuleShell` (render their inner `ck-panel-stack` directly). Simplest: give each an optional `embedded?: boolean` prop (passed `true` by `TimetableModule`); when embedded, skip the outer `ModuleShell` and render the body only. Preserve the no-permission / read-only states (they can stay wrapped or render inline). Keep spacing/typography consistent via the existing classes.

- [ ] **Step 5: Build**

Run: `cd frontend && npm run build`
Expected: clean.

Manual acceptance: no nested/duplicated module title across tabs; on the Grid tab, a class with no bell schedule shows the "Set up this class's bell schedule →" link, which switches to the Bell tab with that class pre-focused for assignment.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/workspace/panels/TimetableModule.tsx frontend/src/pages/workspace/panels/TimetableGrid.tsx frontend/src/pages/workspace/panels/setup/BellSchedulesPanel.tsx frontend/src/pages/workspace/panels/setup/SubjectsMasterPanel.tsx
git commit -m "feat(fe): cross-tab bell-setup prompt + harmonize timetable tab chrome"
```

---

## Self-Review

**Spec coverage:** §4.1 wrapper → Task 1 (+ year Task 2). §4.2 nav/dispatch → Task 1 Steps 2-4. §4.3 permissions → preserved (each panel keeps its `timetable:manage` gate; noted in Global Constraints). §4.4 shared year → Task 2. §5 bell timeline + chip assignment + unassigned prompt → Task 3. §6 grid/subjects as-is + cross-tab prompt + harmonization → Tasks 2/4. §7 error handling → reused `errMsg` (Global Constraints). §8 files → all covered. §9 testing → build + manual acceptance per task (no FE tests, per convention).

**Placeholder scan:** no TBD/TODO. The bell-timeline JSX (Task 3) is specified structurally with the exact reused handlers/derived-state rather than 400 lines of verbatim styled JSX — deliberate for a visual redesign; all non-obvious logic (chip derivation `filter(scheduleId === selectedId)`, unassigned detection `scheduleId == null`, edit-state, reorder via `handleMovePeriod`) is given as concrete code. Global Constraints lock the class vocabulary so "style it" isn't open-ended.

**Type consistency:** `AcademicYearOpt = { id; label; active }` identical in Task 2 across module/grid/subjects. `onNeedBellSetup: (classId: string) => void` and `initialClassId?: string` consistent between Task 4 producer (module) and consumers (grid/bell). `embedded?: boolean` consistent across the three tab bodies (Task 4). Tab keys `'grid' | 'bell' | 'subjects'` identical in Tasks 1 and 4.

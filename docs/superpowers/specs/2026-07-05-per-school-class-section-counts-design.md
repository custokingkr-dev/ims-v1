# Per-School Class/Section Counts — Editable & Consistently Reflected

**Date:** 2026-07-05
**Status:** Approved (design)
**Service:** `school-core-service` (owns `tenant_school` + `student` schemas) and `frontend`

---

## Problem

A school is onboarded with a **class count** (1–12) and a **section count** (1–26).
These are stored on the `schools` row but today:

1. They are **fixed after onboarding** — there is no edit path.
2. They are **inconsistently reflected**. `GET /classes` returns the *global* 12-class
   master list ignoring school scope, and `AddStudentPanel.tsx` hardcodes `Class 1–12`
   and `Section A–D`. So a school configured for 5 classes / 2 sections still sees 12
   classes and A–D in several pickers, while `StudentsPanel` filters (which read
   `school_sections`) correctly show only the configured amount.

We want the counts to be **editable** post-onboarding, and **every** class/section
picker a school admin sees to reflect that school's configured counts.

---

## Decisions (locked during brainstorming)

- **Edit ownership:** Superadmin **and** school admin. Superadmin edits any school from
  the SA management panel; a school admin edits **only their own** school from an
  admin-facing settings surface.
- **Shrink rule:** Growing a count is always allowed. Shrinking is **blocked** if a
  class/section that would be dropped currently has students; empty classes/sections may
  be dropped (deactivated) freely.
- **Class model:** Classes stay a **global master list**; a school "has" the first
  *configured_class_count* classes by `sort_order`. No new class table.
- **Section count is uniform per class** (matches today's model). Per-class variation is
  achieved by deactivating individual empty sections, not by separate counts.

---

## Source of Truth & Model

- `schools.configured_class_count` and `schools.configured_section_count` remain the
  source of truth (columns already exist;
  `services/school-core-service/src/main/resources/db/migration/tenant_school/V1__tenant_school_schema.sql:21-22`).
- **Classes:** global `tenant_school.school_classes(id, name, sort_order)`. A school's
  classes = the first `configured_class_count` rows ordered by `sort_order, name`. This
  is exactly how onboarding already selects them
  (`SchoolStructureReadRepository.ensureSchoolSections`).
- **Sections:** per-school `tenant_school.school_sections(id, name, teacher_name, active,
  school_class_id, school_id)`. `configured_section_count` is the uniform number of
  sections (A, B, …) generated **per class**. The `active` flag governs visibility.
- **The rule every read surface obeys:** pickers show **active** sections and the
  **first-N** classes. Counts drive **generation**; `active` + first-N drive
  **visibility**.

### Student-occupancy check (needed for the shrink rule)

Students live in the `student` schema, sections/classes in `tenant_school` — both owned
by `school-core-service`, so an in-service query can join them. We need two counts,
scoped to a school:

- Students per class name (to block dropping a class that has students).
- Students per section name (to block dropping a section letter that has students).

Students carry `class_name` / `section_name` (used by
`StudentReadRepository.workspaceStudents`), so occupancy is a `GROUP BY` over the student
table filtered by `school_id`. No cross-service call.

---

## API

### New: edit structure

```
PUT /api/v1/schools/{id}/structure
Body: { "classCount": <int 1..12>, "sectionCount": <int 1..26> }
```

Handled by `TenantSchoolController` → `SchoolStructureReadRepository`.

- **Internal route token:** `requireToken(token, "tenant-school:write")` (same as the
  other write routes in that controller).
- **End-user permission:** a **new** permission code `school:structure:edit`, added to the
  permission catalog and granted to the **superadmin** and **school admin** roles.
  (We add a new code rather than reuse `school:write` — which gates school *creation*, a
  superadmin-only action — so granting a school admin structure-edit does not also grant
  school creation.)
- **Tenant scope:** `schoolId` resolved through the existing `TenantScope.resolveSchoolId`
  guard. A school admin editing any `{id}` other than their own scoped school → **403**
  (same guard used by every other school-scoped endpoint). Superadmin bypasses.
- **Validation:** `classCount` bounded [1,12], `sectionCount` bounded [1,26] via the
  existing `boundedInt` helper. Out-of-range → 400.

**Grow/shrink semantics (server-side, transactional):**

1. Load current `configured_class_count` / `configured_section_count`.
2. Compute the set of classes and section-letters that would be **dropped**
   (class indices `> newClassCount`; section letters with index `>= newSectionCount`).
3. If any dropped class or section letter has **≥1 student** for this school →
   **reject 409 Conflict** with a message naming the first offender
   (e.g. `"Cannot reduce classes: Class 8 has 14 students"` /
   `"Cannot reduce sections: Section C has 3 students"`).
4. Otherwise apply:
   - Persist the new `configured_class_count` / `configured_section_count` on the school.
   - **Grow classes:** call the existing generation path so newly-in-range classes get
     their sections created (`active=true`, empty).
   - **Grow sections:** for every in-range class, insert missing section letters up to
     `newSectionCount` (`active=true`, empty). Reactivate a previously-deactivated letter
     rather than duplicating (idempotent on the `{schoolId}-{classId}-{name}` id).
   - **Shrink sections:** set `active=false` for dropped section letters across the
     school's in-range classes. (Rows preserved; a later grow reactivates them.)
   - **Shrink classes:** lowering `configured_class_count` hides the tail classes via the
     first-N filter; also deactivate their sections for cleanliness.
5. Return the updated school (same `SchoolResponse` shape, including
   `configuredClassCount` / `configuredSectionCount`).

Idempotent: re-issuing the same counts is a no-op.

### Changed: school-scoped classes

`GET /api/v1/classes` (`TenantSchoolController` → `SchoolStructureReadRepository.classes()`):

- When a school is resolved (school-admin scope, or an explicit `schoolId` param),
  return only the first `configured_class_count` classes for that school
  (`ORDER BY sort_order, name LIMIT configured_class_count`).
- Superadmin with **no** school context → full global list (unchanged), so the SA panel
  still shows all 12.

### Unchanged but verified

- `GET /sections`, `GET /schools/{id}/sections`, `GET /classes/{classId}/sections`:
  already per-school. Confirm they return only `active` sections by default (add an
  `active = true` predicate where missing).

---

## Frontend

- **AddStudentPanel.tsx** (`frontend/src/pages/workspace/panels/AddStudentPanel.tsx:150-151`):
  remove the hardcoded `Class 1–12` and `Section A–D` `<option>`s; fetch classes from
  `/classes` and sections from `/classes/{classId}/sections` (same pattern as FeesPanel),
  scoped to the current school. Section dropdown populates after a class is chosen.
- **StudentsPanel.tsx:** filters already read `studentsView.filters.classes/sections` from
  the per-school `workspaceStudents` response — no change; verify still consistent.
- **FeesPanel.tsx / FeeStructurePanel.tsx:** already call `/classes` with school-scoped
  params; they automatically pick up the now-scoped `/classes` response — no change beyond
  confirming the count is respected.
- **Superadmin edit (SaSchoolsPanel.tsx / SchoolManagementPage.tsx):** add an **Edit**
  action on an existing school that PUTs `/schools/{id}/structure` with the two counts;
  surface the **409** in-use message inline (do not fake success). Reuse the existing
  1–12 / 1–26 client-side validation.
- **School-admin settings:** add a **School Settings** control in the admin workspace with
  the same two fields, gated on `school:structure:edit` (via `usePermissions().can(...)`),
  editing only the admin's own school. Exact navigation placement confirmed during
  planning (candidate: a settings entry in the workspace side nav).

---

## Error Handling

- Count out of range (1–12 / 1–26) → **400**.
- School admin targeting a school that is not theirs → **403** (TenantScope guard).
- Shrink that would orphan students → **409** with a human message naming the offending
  class/section. The frontend shows this message; it does **not** report success.
- Missing/invalid internal token → fail closed (existing `requireToken` behaviour).

---

## Testing

**Backend (`school-core-service`, Mockito + existing MockMvc/standalone patterns):**

- Grow classes → new in-range classes get active empty sections.
- Grow sections → each in-range class gains the new active letters; a previously
  deactivated letter is **reactivated**, not duplicated.
- Shrink sections with students in the dropped letter → **409**, no mutation.
- Shrink sections when dropped letters are empty → letters set `active=false`; student-
  bearing letters untouched.
- Shrink classes with students in a dropped class → **409**.
- School admin editing another school's structure → **403**.
- Superadmin editing any school → allowed.
- `classCount=13` or `sectionCount=0` → **400**.
- `GET /classes` under a 5-class school returns exactly Class 1–5; superadmin (no school)
  returns all 12.
- Re-issuing identical counts → no-op (idempotent).

**Frontend (Vitest):**

- AddStudentPanel renders classes/sections **fetched** from the API (not the hardcoded
  1–12 / A–D); section dropdown populates after class selection.
- Superadmin Edit form surfaces the 409 in-use message and does not show success on 409.
- School-admin settings control is hidden without `school:structure:edit`.

---

## Out of Scope (YAGNI)

- Per-class section counts (uniform count + per-section deactivation covers the need).
- Renaming classes per school (classes stay a shared master list).
- Bulk student reassignment when shrinking (we block instead of migrating).
- Section teacher assignment changes (unrelated existing behaviour).

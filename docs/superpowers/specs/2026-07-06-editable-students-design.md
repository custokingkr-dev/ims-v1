# Editable Students (School-Admin Student Edit)

**Date:** 2026-07-06
**Status:** Approved (design)
**Service:** `school-core-service` (`student` schema) and `frontend`

---

## Context & problem

Under **School ERP → Students**, the list and its "View" detail modal are **read-only**, and
there is **no student-update endpoint** (`StudentReadController` has GET/import/photo/reviews
but no `PUT/PATCH`). A school admin cannot fix a typo, update a parent contact, or move a
student to a different section. This adds student editing — all demographic/contact fields
**and** a class/section transfer — via an editable detail modal, gated on `student:update`.

## Decisions (locked during brainstorming)

- **Editable: everything, including class/section transfer.** All demographic/contact fields
  plus moving the student to a different class + section (with validation). `academic_year_id`
  and `school_id` stay fixed; the photo keeps its existing separate upload flow.
- **UI: an editable detail modal.** The existing student **View** modal gains an **Edit**
  toggle (visible only with `student:update`) → fields become inputs (incl. class/section
  dropdowns) → **Save** / **Cancel**.
- **Backend: mirror the create path.** A new `PUT /api/v1/workspace/students/{id}` beside the
  existing `POST /api/v1/workspace/students`, using the same internal token +
  `applyResolvedSchool` tenant resolution, delegating to a new `StudentReadRepository.updateStudent`.

## Backend (`school-core-service`, `student` schema)

### Endpoint — `StudentWorkspaceCompatibilityController`

```
PUT /api/v1/workspace/students/{id}
```
- `requireToken(token, "student:read")` (same internal scope as `createFromWorkspace`);
  `applyResolvedSchool(request)` injects the resolved school; delegate to
  `students.updateStudent(id, request)`; map `IllegalArgumentException` → 400 (as create does).
- The **end-user** gate is `student:update`, enforced on the frontend (the internal token is
  the transport auth, matching the existing create pattern).

### Repository — `StudentReadRepository.updateStudent(String/Long id, Map request)`

- **Tenant check:** load the student; if its `school_id` ≠ the resolved school → `SecurityException`
  (→ 403). A school admin cannot edit another school's student.
- **Editable fields** (update when present in the request; keep current otherwise): `full_name`,
  `roll_no`, `admission_no`, `board_reg_no`, `dob`, `gender`, `father_name`, `father_contact`,
  `mother_name`, `phone`, `address` (+ `house_number`, `street`, `locality`, `city`, `state`,
  `pin_code`), `class_id`, `section_id`.
- **Required (kept):** `full_name`, `class_id`, `section_id`, `phone` — reuse the create
  validation (`validatePreviewRow`/`createStudent` rules) so an edit can't blank a required field.
- **Class/section transfer validation:** the new `section_id` must belong to the new `class_id`
  **and** to the student's school (reuse the section-belongs-to-class-and-school check the
  create/attendance paths use). Reject otherwise → 400.
- **Admission-no uniqueness:** `students` has `UNIQUE(school_id, admission_no)`. If `admission_no`
  changes, reject a collision with another student in the same school (clear 400), not a raw
  500. (Catch the unique-violation `DataIntegrityViolationException` as a backstop → 400,
  per the RLS/FK-guard lesson.)
- **Bookkeeping:** set `updated_at`, `updated_by` (actor), bump `version`. Last-write-wins (no
  strict optimistic-lock rejection this pass — noted below).
- Returns the updated student (same shape as `GET /students/{id}` so the modal can refresh).

> **Transfer side effects (no special handling needed):** attendance records are per-date and
> stay with the old section historically; the student simply appears in the new section's roster
> going forward. Fee assignments reference the student (not the section), so they are unaffected;
> the class change only affects which fee **band** would *match* for a *new* assignment. No data
> migration is required — just the field update.

## Frontend (`frontend`)

`StudentsPanel.tsx` — the student **View** modal becomes view/edit:

- Add an **Edit** button in the modal header (rendered only when `can('student:update')`).
- Edit mode swaps the read-only `Info` fields for inputs, and class/section for dropdowns
  (reuse the `/classes` → `/classes/{c}/sections` chain the other panels use), pre-filled from
  the loaded detail.
- **Save** → `PUT /api/v1/workspace/students/{id}` with the edited fields (+ `schoolScopedParams`);
  on success, refresh the modal + the list, show a success toast; on error, show the server
  message in the modal.
- **Cancel** reverts to view mode. The photo keeps its existing flow (out of scope here).
- Non-`student:update` users see the modal exactly as today (read-only) — no Edit button.

## Scoping, auth, permission

- Internal token (`student:read` scope) + `applyResolvedSchool` tenant resolution on the
  endpoint; the repo re-checks the student's `school_id` against the resolved school (403 on
  mismatch). Frontend gates the Edit affordance on `student:update`.

## Error handling

- Missing required field / invalid class-section / duplicate admission no → 400 with a clear
  message shown in the modal.
- Cross-school student → 403.
- Unknown student id → 404 (map "not found" appropriately).

## Testing

Proposed (confirm at plan handoff): **Testcontainers** repo tests for `updateStudent` —
field update round-trip; class/section transfer validation (reject a section not in the class
or not in the school); admission-no duplicate → 400; cross-school edit → `SecurityException`.
Frontend: `npm run build` + a manual dev check (edit a student's contact + move their section,
confirm it persists and they appear in the new section's attendance roster).

## Out of scope (later)

- Bulk edit / multi-student transfer; a dedicated "transfer / promote" workflow with history.
- Strict optimistic-locking (version-mismatch rejection) — last-write-wins for now.
- Editing `academic_year_id` (promotion) or `school_id` (inter-school transfer).
- Photo editing (keeps its existing upload flow).
- Audit-trail surfacing of who changed what (the record stamps `updated_by`, but no history UI).

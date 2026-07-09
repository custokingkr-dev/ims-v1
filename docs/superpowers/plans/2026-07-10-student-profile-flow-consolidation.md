# Student Profile Flow Consolidation

## Goal

Make Add Student and Edit Student use one canonical profile model and one shared form surface, then deploy the result through the existing dev-to-prod pipeline.

## Scope

- Replace separate add/edit student form state with one shared frontend `StudentProfileFormState`.
- Use `classId` and `sectionId` as the canonical create/update payload fields.
- Keep backend compatibility for existing name-based callers (`gradeLevel`, `className`, `sectionName`) so imports and old clients do not break.
- Remove misleading Add Student fee-assignment fields and copy. Fee assignment remains in the Fee Structure flow until a real student-create-to-fee-assignment workflow is designed.
- Add focused frontend mapper tests and backend controller tests for canonical class/section ids.
- Run local verification, push to `main`, and promote the same image SHA through dev and prod.

## Implementation Steps

1. Add a shared student profile model and mapper module under `frontend/src/features/students`.
2. Add a shared workspace `StudentProfileForm` component used by both `AddStudentPanel` and `StudentsPanel`.
3. Update `AddStudentPanel` to load class/section options by id and post `classId` / `sectionId`.
4. Update `StudentsPanel` edit mode to reuse the same form component and mapper.
5. Update `CreateStudentRequest` and `StudentReadController` to accept and forward `classId` / `sectionId`.
6. Update `StudentReadRepository.createStudent` to prefer explicit ids and fall back to the legacy name-based path.
7. Add tests:
   - frontend mapper round trip: detail -> form -> create/update payload
   - backend controller: create forwards `classId` and `sectionId`
8. Verify locally:
   - `npm run test -- profileForm`
   - `npm run build`
   - `npm test` in `services/api-gateway`
   - school-core focused tests with JDK 25
9. Commit, push, watch CI/CD, approve prod promotion, and verify deployed gateways.

## Risk Controls

- No destructive migration in this change.
- Existing bulk import/name-based create path remains supported.
- Reporting projections are not changed.
- Fee assignment is not silently created from student creation; the UI stops claiming it is.

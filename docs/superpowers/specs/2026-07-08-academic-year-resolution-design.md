# Command-Center Academic-Year Resolution — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** platform-service.

Replace the two remaining hardcoded `academic_year_id = 'ay_2025_26'` literals in reporting with the already-built current-year resolver, so the command-center KPIs don't break when the academic year rolls over.

## Findings (from design research)
- Only **two** hardcoded sites exist, both in `ReportingReadRepository.commandCenterSummary(Long schoolId, boolean platform)`:
  - `:756` `overdueCount` (fee_assignment, per-school).
  - `:783` `attendanceSections` (attendance_daily, per-school).
- `ReportingReadRepository.activeAcademicYearId()` (`:815`) already exists — `SELECT id FROM reporting.dim_academic_year WHERE active = true LIMIT 1` — and is **already used** by `dashboardCommandCenter`, `lowAttendanceSections`, and `feeDefaulters`, each with an `if (yearId == null)` zero/empty fallback. `dim_academic_year` is kept current by school-core `academic-year.upserted` events → `ReferenceDimensionProjector`. Academic year is **global** (no `school_id`) by schema — pre-existing product fact, unchanged here.

## Fix
In `commandCenterSummary`'s per-school branch (after the `schoolId == null` early return), resolve `String yearId = activeAcademicYearId();` once, then:
- `:756` → `academic_year_id = :yearId` (bind `yearId`).
- `:783` → `academic_year_id = :yearId` (bind `yearId`).
- Apply the same `yearId == null` fallback the sibling methods use (treat `overdueCount`/`attendanceSections` as `0` when no active year is configured — avoids a null-bind SQL error on a freshly-bootstrapped environment).
Keep the existing `school_id = :schoolId` predicates (added in the prior hardening) and the `attendance_date = CURRENT_DATE` filter.

## Testing
Extend the command-center summary test: seed `dim_academic_year` with an active year + `fact_*` rows tagged with it under the caller's school; assert the KPIs count those rows. Add a case with **no** active year → KPIs are `0` (not an error). Do not hardcode the year literal in assertions.

## Files
`services/platform-service/.../persistence/ReportingReadRepository.java` (2 query lines + one `activeAcademicYearId()` call) + test.

## Out of scope
The `activeAcademicYearId()` helper's own `LIMIT 1`-without-`ORDER BY` ambiguity if >1 active row exists is a pre-existing school-core invariant concern — not changed here.

# School And Student Lifecycle

Last verified: 2026-08-13.

This document describes implemented behavior. It is not a future-state proposal.

## School Onboarding

```text
Superadmin
  -> POST /api/v1/schools
  -> school-core creates tenant_school.schools
  -> school-core creates the configured class/section structure
  -> school-core emits school.upserted.v1 through its transactional outbox
  -> school-core attempts the current-year Google Drive intake hierarchy
  -> API returns the school plus photoImportFolder.status
  -> superadmin assigns an ADMIN identity account separately
  -> /api/v1/sa/schools derives setup readiness for the UI
```

Setup is `Ready` only when an active administrator assignment exists and the current academic
year's Drive intake binding is `READY`. The state is derived, not stored as a second mutable
status column, so identity assignments and Drive retries cannot leave a stale onboarding flag.
An Operations account is supported but is not a readiness requirement.

Drive statuses are `PENDING`, `PROVISIONING`, `READY`, `FAILED`, or `NOT_CONFIGURED`.
Creating the school is not rolled back when Drive fails. Both superadmin school screens now
report that partial outcome, and School accounts exposes `Retry Drive`, which calls
`POST /api/v1/student-photo-imports/folders/{schoolId}/provision`.

## School Localization

`tenant_school.schools` owns these settings:

| API field | Database column | Format |
| --- | --- | --- |
| `timeZone` | `time_zone` | IANA timezone validated by `ZoneId.of` |
| `countryCode` | `country_code` | ISO 3166-1 alpha-2 |
| `locale` | `locale` | BCP 47 language tag |
| `currencyCode` | `currency_code` | ISO 4217 |
| `phoneRegion` | `phone_region` | ISO 3166-1 alpha-2 |

Migration `tenant_school/V25__school_localization.sql` backfills existing schools to `IN`,
`en-IN`, `INR`, and `IN`; migration V24 already backfilled `Asia/Kolkata`. This preserves all
existing tenants. New onboarding forms submit the complete setting bundle and changing the
country preset changes timezone, locale, currency, and phone region together; each can then be
edited before submission.

`school.upserted.v1` carries the settings to platform-service. Its
`reporting/V25__dim_school_localization.sql` projection stores them in `reporting.dim_school`,
and `GET /api/v1/workspace` returns them under `school`. The active fee workspace and dashboard
use the projected locale/currency; dashboard greeting uses the projected school timezone.

## Student Creation And Editing

Manual create and edit use the same profile mapper and repository boundary:

- DOB and admission date are date-only `YYYY-MM-DD` values.
- DOB uses `StudentImportDateParser.parseDateOfBirth`, accepts the supported import date forms,
  and rejects dates before 1900 or after the current date.
- Admission date persists in `student.students.admission_date` from student migration V15.
- Academic year is read-only in the profile. Create assigns the school's current academic year;
  placement changes are recorded through enrollment history. Profile payloads do not pretend
  to update academic-year assignment.
- Phone input supports an optional leading `+` and up to 15 digits. Postal codes are not forced
  into India's six-digit PIN format.
- Creating a profile no longer inserts Hyderabad/Telangana defaults when address fields are blank.
- Editing profile-owned fields invalidates active profile verification. Photo changes invalidate
  active photo verification through the existing photo path.

## Student Bulk Import

The bulk template and parser support optional `DateOfBirth`, `AdmissionDate`, and `PostalCode`.
Legacy `PinCode` headings remain accepted by the backend. DOB follows the same validity boundary
as manual entry. Excel calendar cells are serialized from calendar fields without UTC conversion.
Admission date is parsed as a date-only value and written with the imported student.

Bulk import still assigns the school's current academic year. It does not accept an arbitrary
academic-year label from the workbook because assignment belongs to the enrollment lifecycle.

## Permanent Student Deletion

`DELETE /api/v1/students/{id}` is a non-recoverable hard delete and requires the caller to have
`student:delete`. The exact admission number is URL-encoded in the
`X-Student-Delete-Confirmation` header, avoiding an unreliable DELETE request body. The archive
list, archive command, restore command, and superadmin restore UI have been removed.

School-core deletes the student's attendance and absentee-notification rows, fee assignments and
payments, import links, review items, promotion items, enrollment history, guardian links and
consents, and orphaned guardians in one transaction. Attendance-day aggregates are recalculated.
Private student photo objects are removed after the database transaction commits. The same
transaction emits a minimal `student.deleted.v1` tombstone containing only student id and school id;
platform-service consumes it to remove student reporting facts, event contributions, notification
logs, and the student dimension. Migrations `student/V18` and `reporting/V27` keep these deletion
lookups index-backed.

## Data That Is Not Rewritten

The localization migrations add/backfill tenant settings only. They do not alter student DOBs,
admission dates, timestamps, addresses, phone numbers, enrollments, photos, fees, or verification
records. The historical DOB repair remains the separate, evidence-bounded process documented in
`docs/operations/bulk-import-dob-reconciliation.md`.

## Verification

Required release checks for these contracts:

1. Run frontend TypeScript/Vite build and the full Vitest suite.
2. Run the full school-core suite with Java 25; it applies all tenant-school and student migrations.
3. Run the full platform suite; it applies reporting migrations and validates projection behavior.
4. In dev, create a disposable non-India school and verify its returned localization and Drive status.
5. Create/edit a disposable student, verify DOB/admission date round-trip, then delete the test data.
6. Promote the exact tested commit to production and run read-only health/migration checks before a
   bounded disposable write smoke. Remove only records created by that smoke.

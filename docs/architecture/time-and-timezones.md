# Time And Timezones

## Storage Rules

Use the data type that matches the business meaning:

- Calendar dates such as DOB, admission date, and academic-day keys use Java `LocalDate`,
  PostgreSQL `date`, and API `YYYY-MM-DD`. Never convert them through UTC or a JavaScript ISO
  timestamp.
- Instants such as created, updated, submitted, and audit times use `OffsetDateTime`, PostgreSQL
  `timestamptz`, and UTC ISO-8601 API values.
- A future local schedule such as "attendance closes at 09:00 school time" needs the local date
  and time plus the school's IANA timezone. Resolve it to an instant only when executing the
  schedule so daylight-saving rules use the current timezone database.

Do not store fixed UTC offsets such as `+05:30` as a school timezone. Offsets do not model
daylight-saving or government timezone changes. Use identifiers such as `Asia/Kolkata`,
`Europe/London`, or `America/New_York` and validate them with `ZoneId.of`.

## School Migration

Migration `V24__school_time_zone.sql` adds `tenant_school.schools.time_zone` as a non-null IANA
identifier. Every school onboarded before this migration is set to `Asia/Kolkata`, preserving the
application's existing India behavior. New schools also default to `Asia/Kolkata` for API
compatibility, while both superadmin onboarding screens require an explicit visible timezone
selection. Superadmins can update an existing school's timezone through `PATCH /schools/{id}`.

Changing a school timezone does not rewrite DOBs, historical timestamps, or any other stored
data. It changes only how future timezone-aware features interpret local schedules and present
instants for that school.

Migration `V25__school_localization.sql` adds the school's ISO country, BCP 47 locale, ISO
currency, and phone region. Existing schools are backfilled to `IN`, `en-IN`, `INR`, and `IN`.
The `school.upserted.v1` projection copies these fields plus `timeZone` into
`reporting.dim_school`; `GET /api/v1/workspace` returns the projected values under `school`.

## Application Rules

- Keep service and database runtime clocks in UTC.
- Use the authenticated school's stored timezone for school-local display and scheduling, not
  the browser timezone and not a global deployment region.
- A superadmin viewing multiple schools must format each school-owned timestamp with that
  record's school timezone.
- Persist the timezone used when a business event's local interpretation must remain auditable.
- Test positive offsets, negative offsets, midnight boundaries, and a daylight-saving zone.
- Do not infer timezone from school name, city, state, IP address, browser, or GCP region.
- Format currency with the school's locale and ISO currency; do not infer it from timezone.

## Bulk Import DOB

Excel stores a date cell as a calendar serial. SheetJS can expose that value as a JavaScript
`Date` at local midnight. Read `getFullYear()`, `getMonth()`, and `getDate()` and serialize those
calendar fields directly. Calling `toISOString()` changes the day in positive-offset zones and
was the source of the historical bulk-import defect.

Manual student create/edit calls the same bounded DOB parser as bulk import. Admission date is
also persisted as PostgreSQL `date` by student migration V15. Neither date is converted through
the school timezone.

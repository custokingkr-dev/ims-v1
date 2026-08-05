# Bulk Import DOB Reconciliation

## Purpose

This runbook repairs the historical one-day date-of-birth shift caused when the browser
converted an Excel calendar date to UTC in a positive-offset timezone. It applies only to
students created by a completed bulk import for which the immutable original workbook proves
both the intended date and the shifted value.

DOB is a date-only value. It has no timezone and is stored in PostgreSQL as `date`. The import
client must serialize an Excel date from its local calendar fields as `YYYY-MM-DD`; it must not
call `Date.toISOString()`. This works for existing and future schools in every country without
consulting the school timezone. School timezones are stored independently for timestamp-based
features such as attendance cutoffs and notifications, but are not applied to DOB.

## Data Boundary

The repair is allowed to update only `student.students.dob`. It must not update `updated_at`,
`version`, verification state, audit/outbox rows, or any other student field. The script hashes
the complete student JSON except `dob` before and after each update and aborts when that hash
changes.

A candidate must satisfy every condition below:

- original workbook SHA-256 matches the immutable bulk-import object;
- workbook batch ID, stored source SHA-256, and parsed row number match an import row;
- the completed import row has an `applied_student_id`, and its normalized DOB equals the
  reproduced historical shifted value;
- applied student ID and school ID match that import row and batch;
- current DOB equals the exact historical UTC-shifted value;
- workbook calendar DOB is exactly one day after that shifted value.

Rows rejected during import remain unmatched and are never changed. A record already corrected
is reported as `alreadyIntended`. A record changed to any other value after import is reported
as `other` and is never overwritten. School names are display-only and are never identity keys,
so two schools with the same name cannot affect one another.

Historical batches stored source rows as one-based data-row indexes, while newer batches store
physical workbook row numbers that include the header. The repair detects the convention once
per batch and requires that exact row identity. This also handles legacy workbooks where Excel
formatted an admission-number cell as a date and its text serialization changed during import.
The normalized DOB, source hash, applied student ID, and school must still agree.

## Build Evidence

Download only the original workbook objects listed on completed bulk-import batches into an
ignored directory. Name each file `<batch-id>.xlsx` and verify its SHA-256 against the database
and object-name hash before continuing. Do not commit workbooks or generated evidence because
they contain student data.

```powershell
node scripts/build-bulk-import-dob-evidence.mjs `
  --workbooks-dir artifacts/dob-repair/original `
  --output artifacts/dob-repair/evidence.json `
  --legacy-timezone Asia/Calcutta
```

The legacy timezone is evidence about how the defective client behaved. It is not a new school
or application timezone setting.

## Dry Run

The command defaults to read-only mode. Review `matched`, `unmatched`, `eligible`,
`alreadyIntended`, and `other` globally and per batch. Keep `ChunkSize` below the Linux
single-argument ceiling; 350 is suitable for the current record shape.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/repair-bulk-import-dob.ps1 `
  -EvidenceJson artifacts/dob-repair/evidence.json `
  -Environment prod `
  -ChunkSize 350 `
  -OutputPath artifacts/dob-repair/prod-dry-run.json
```

Stop if a workbook hash is unverified, a batch belongs to an unexpected school, or `other` is
not understood. The output contains aggregate counts only; keep row-level evidence restricted.

## Apply And Verify

Use the reviewed dry-run `eligible` count as the mandatory expected count. The script repeats
the dry run immediately before writing, uses guarded predicates in a transaction, verifies each
chunk updated exactly its expected count, and checks all non-DOB columns were preserved.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/repair-bulk-import-dob.ps1 `
  -EvidenceJson artifacts/dob-repair/evidence.json `
  -Environment prod `
  -Apply `
  -ExpectedCandidateCount <reviewed-eligible-count> `
  -ChunkSize 350 `
  -OutputPath artifacts/dob-repair/prod-apply.json
```

Run the dry-run command again. A successful reconciliation reports `eligible: 0`, moves the
updated records to `alreadyIntended`, and leaves `other` unchanged. No test student data is
created by this process.

## Rollback

Do not perform a blanket one-day subtraction. If rollback is required, use the same immutable
evidence and exact identity joins, require current DOB to equal `intendedDob`, set only DOB back
to `observedBuggyDob`, and repeat the non-DOB hash assertion. Retain the reviewed aggregate
results with the restricted operations case.

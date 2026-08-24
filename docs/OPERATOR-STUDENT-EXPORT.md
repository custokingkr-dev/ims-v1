# Operator Student Details and Photo Export

Status: implemented on `codex/operator-student-export`; not deployed by this change.

## Purpose

An Operations user can download the current student details and portraits for a school assigned
to that operator. The result is suitable for handing to an ID-card photographer:

```text
<school-code>-student-details-and-photos-YYYY-MM-DD.zip
├── Student-Details.xlsx
└── photos/
    ├── <Admission Number>.jpg
    ├── <Admission Number>.png
    └── ...
```

The Excel file contains one row for every active (not deleted) student. `Photo Filename` is the
exact corresponding file name and `Photo Status` is `Exported` or `Missing`. A missing photo does
not remove the student row. Each exported photo is copied from its current private stored object
byte for byte; export does not resize, crop, re-encode, or otherwise change the image. The file
extension is selected from the stored content type and the exact filename is recorded in Excel.

## Verified local worktree state

The similarly named local directories are Git worktrees, not four independent or divergent
copies of the product:

| Worktree | Branch at audit | Unique/unmerged code found |
|---|---|---|
| `D:\Projects\ims-v1` | `codex/scale-readiness-dev-20260810` | No unique code commit; it does contain uncommitted documentation and `.gitignore` edits, which were preserved. |
| `D:\Projects\ims-v1-cost-main-20260813` | `codex/fix-cost-control-main-20260813` | No; its commit is already contained in `origin/main`. |
| `D:\Projects\ims-v1-dev-release-20260813` | `codex/student-permanent-delete-dev-20260813` | No; it is behind `origin/dev` with no commits ahead. |
| `D:\Projects\ims-v1-ops-otel-20260813` | now `codex/operator-student-export` | This is the clean worktree used for this feature. |

Three detached worktrees also remain under
`C:\Users\Shubham-Work\AppData\Local\Temp\ims-v1-worktree-*`. Their commits are ancestors of
`origin/main`; their checked-out contents appear stale/removed. They were not deleted because
worktree removal is destructive and was not part of this implementation.

## User flow

1. Superadmin assigns one or more schools to the Operations user through the existing
   operator-school assignment UI.
2. The user signs in again or refreshes the session after the `student:export` permission migration
   is deployed.
3. In the Operations workspace, open **Student data export**.
4. The screen loads only active schools in the authenticated `ops_schools` claim and shows current
   student-row and mapped-photo counts.
5. Select one school and choose **Download Excel and all photos**.
6. Chrome or Edge prompts for a save location and streams the response directly to disk. Other
   browsers use a Blob fallback, which can consume substantial browser memory for large schools.

## Authorization and tenant isolation

The UI school list is not the security boundary. Every archive request is checked in
`StudentExportController` and requires all of the following:

- a valid gateway-injected student service token;
- the `OPERATIONS` or `SUPERADMIN` role;
- the dedicated `student:export` permission; and
- for Operations, the requested `schoolId` must exist in the trusted operator-school claim.

The API gateway strips all client-supplied `X-Authenticated-*` and `*-Service-Token` headers,
verifies the JWT, and injects the verified `ops_schools` list. The repository then selects the
explicit school RLS context before reading school, class, section, academic-year, student, or audit
records. An unassigned school request fails with HTTP 403 before student rows are loaded.

Only `SUPERADMIN` and `OPERATIONS` receive `student:export` in identity migration V6. School admins,
teachers, viewers, and other roles do not receive bulk export access by default.

## Exported columns

The plain workbook contains the complete set of student fields currently held in
`student.students` plus joined school-structure labels needed by the photographer:

1. Admission Number
2. Student Name
3. Class
4. Section
5. Roll Number
6. Board Registration Number
7. Date of Birth
8. Admission Date
9. Gender
10. Father Name
11. Father Contact
12. Mother Name
13. Student / Alternate Phone
14. House Number
15. Street
16. Locality
17. City
18. State
19. PIN Code
20. Full Address
21. Academic Year
22. Fee Status
23. Attendance Percent
24. Photo Filename
25. Photo Status

Phone numbers, admission numbers, roll numbers, PIN codes, and other identifier-like values are
written as strings so leading zeroes are preserved.

## File-name handling

For a normal admission number, the image base name is exactly the admission number. Characters
that are invalid in Windows file names (`< > : " / \\ | ? *` and control characters) are replaced
with `_`. Trailing spaces/dots and Windows device names are made safe. If sanitization creates a
case-insensitive collision, the internal student ID is appended. The final exact name is always
recorded in the workbook.

## Performance and cost controls

- No new GCP service, queue, database, or export bucket is introduced.
- The archive is generated on demand and is not retained in Cloud Storage, avoiding duplicate PII
  storage, lifecycle rules, and extra storage charges.
- The server holds only the student row list and a bounded batch of portrait bytes in memory. It
  does not build the full ZIP in RAM.
- Portrait reads use eight workers in batches of sixteen to avoid 10,000 serial GCS round trips
  while bounding memory and GCS concurrency.
- ZIP compression level is zero because normalized JPEG/PNG/WEBP and XLSX files are already
  compressed. This reduces Cloud Run CPU time without changing the archive format.
- Apache POI's streaming workbook keeps only 100 spreadsheet rows in memory.
- At most two exports run concurrently per school-core instance (`STUDENT_EXPORT_MAX_CONCURRENT=2`);
  excess attempts receive HTTP 429 and can be retried.
- Cloud Run min instances remain zero. The gateway request timeout is raised from 300 to 600
  seconds to match school-core; a timeout setting has no standing cost.
- The gateway already streams upstream response chunks, so it does not buffer the ZIP.

For a 10,000-student school, archive size is dominated by the stored portraits. Newly processed
portraits preserve their source aspect ratio and have a longest edge of at most 512 pixels, and the
exporter copies those stored bytes unchanged. A dev load test with representative production photo
sizes and object count is still required before production release; source inspection alone cannot
prove the actual GCS latency distribution.

## Audit and privacy

Student exports contain minors' sensitive information. Migration student V19 creates
`student.student_export_audit`, recording only export metadata: school, requesting user ID, status,
student count, exported/missing photo counts, timestamps, and a bounded failure type. It does not
duplicate names, admission numbers, contact information, or photo object keys.

Responses use `Cache-Control: no-store`, `Pragma: no-cache`, and `X-Content-Type-Options: nosniff`.
Application logs contain counts and IDs, not student PII.

## Deployment order and validation

Deploy through the repository's existing branch-owned CI/CD process; do not apply the SQL files
manually.

1. Merge/release identity-service so V6 creates and grants `student:export`.
2. Release school-core so student V19, the context endpoint, archive endpoint, and audit writer are
   available.
3. Release the API gateway with the 600-second request timeout.
4. Release the frontend with the new Operations navigation item and direct-to-disk downloader.
5. Sign out/in as a test Operations user so the new permission is present in the access token.
6. In dev, assign two schools to the operator and confirm:
   - both assigned schools appear;
   - an unassigned `schoolId` returns 403;
   - the ZIP opens and `Student-Details.xlsx` has one row per active student;
   - normal photo file names exactly equal admission numbers;
   - unsafe names are mapped to the exact sanitized file name in Excel;
   - missing photos remain as workbook rows with `Missing` status;
   - a completed audit row exists;
   - a cancelled/interrupted download records failure without retaining an archive.
7. Run a representative large-school dev export while observing Cloud Run request duration, memory,
   instance count, GCS read errors, archive size, and audit completion.

Production deployment is intentionally not included here; it should follow successful dev
validation and the existing production approval/canary process.

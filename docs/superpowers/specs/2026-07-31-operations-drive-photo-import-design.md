# Operations Drive Photo Import

**Status:** Production workflow implemented; DEV pilot and PROD environment approval remain operational gates
**Date:** 2026-07-31
**Owner:** Student Operations

## 1. Decision

Build a manually triggered Operations workflow that scans one dedicated Google Drive
intake folder, reads the photographer-supplied workbook, maps camera filenames such as
`DSC5747.jpg` through the workbook's numeric `ImageNo`, stages normalized images, and
updates student photos only after an Operations or Superadmin user approves the batch.

This is not an external platform login. The photographer receives access only to an
empty, job-specific Drive folder. They never receive Custoking credentials, student API
access, or Cloud Storage permissions.

The import remains manually triggered. It does not use Drive webhooks and does not poll
folders in the background. Folder creation is automatic: school onboarding provisions
the current academic-year intake hierarchy in the connected Custoking personal Google
Drive.

### Managed Drive hierarchy

Each environment has one configured root folder owned by a dedicated personal Google
account:

`Custoking Student Photo Intake / <SHORT CODE> - <School> / <Academic year> / Student Photo Intake`

School onboarding creates or resolves all three managed child folders. Drive
`appProperties` carry the immutable school UID, academic-year ID, and folder purpose,
so retries resolve the same hierarchy even if a display name changes. The platform
stores the returned Drive IDs in `student.photo_import_drive_folders`.

The Operations screen displays the generated folder link. Operations opens Drive,
shares that restricted intake folder with the photographer as Editor, and sends the
link. The platform never makes the folder public and never gives the photographer a
Custoking account.

The school-core service accesses this My Drive as the human owner through a Google OAuth
client and offline refresh token. It does not use the Cloud Run service account for
Drive. Google documents that service accounts cannot own Drive files and that OAuth on
behalf of a human user is the supported personal-Drive alternative in
[Create and populate folders](https://developers.google.com/workspace/drive/api/guides/folder).

Use a dedicated personal Google account for this intake, not an employee's everyday
account. Enable two-step verification and recovery controls. Store the OAuth client ID,
client secret, and refresh token in GCP Secret Manager. Never store or return them from
the application database or frontend.

The import requests `https://www.googleapis.com/auth/drive` because it must list and
download files that a photographer uploads directly through the Drive UI. Google
classifies that as a restricted scope. The OAuth app is for a small, known personal-use
audience and can remain unverified under Google's documented personal-use exception;
the connected user will see the unverified-app warning. Publish the OAuth app rather
than leaving it in `Testing`, where offline refresh tokens expire after seven days.

### Implemented scope

The implementation auto-maps the supplied five exact headings and records the mapping
in the batch. Operators can correct admission/image identifiers per row, exclude a row,
adjust horizontal and vertical crop focus, preview the exact normalized portrait, cancel
an unfinished batch, and download a formula-safe CSV result. Configurable headings for
future workbook variants remain outside the initial contract.

Execution is resumable in bounded ten-row requests. The UI continues the requests to a
terminal result, and an interrupted browser request can resume an `EXECUTING` batch
without reapplying completed rows.

## 2. Supplied Workbook

The supplied `adm_no_imag_no_mapping.xlsx` was inspected on 2026-07-31:

- one sheet named `Sheet1`;
- range `A1:E21`;
- 20 data rows;
- columns `AdmissionNo`, `Name`, `Class`, `Section`, and `ImageNo`;
- 20 unique, nonblank admission numbers;
- 18 unique, nonblank image numbers; and
- blank `ImageNo` values on Excel rows 13 and 14.

The workbook has no hidden sheets, formulas, merged ranges, defined names, or VBA
content. Only `Sheet1` is parsed; future workbooks with additional sheets require an
explicit operator selection rather than silently combining them.

The workbook stores `AdmissionNo` and `ImageNo` as numeric Excel cells. The importer must
read their displayed values with a formatter and then treat them as identifiers. It must
not convert identifiers through floating-point values or discard an Excel number format
that preserves leading zeroes.

This workbook contains no school or academic-year metadata. It cannot identify the
tenant or year by itself.

## 3. Required Mapping

A camera filename contains no student identity. Automatic mapping is possible only when
the workbook has both:

- an image number that deterministically resolves to one file in the Drive folder; and
- a stable school-scoped student key, preferably admission number.

Names, class names, roll numbers, and section names are secondary verification fields.
They must not be the only automatic key because they are not reliably unique.

The importer supports a column-mapping step so the photographer can keep the supplied
workbook headings. The canonical fields are:

| Canonical field | Required | Purpose |
| --- | --- | --- |
| `ImageNo` | Yes per photo | Numeric suffix resolved from an accepted camera filename |
| `AdmissionNo` | Yes | Primary student lookup within the selected school |
| `Name` | Recommended | Human cross-check; mismatch produces a warning |
| `Class` | Recommended | Human and academic-year cross-check |
| `Section` | Recommended | Human and academic-year cross-check |

The exact supplied headings are mapped automatically. A configurable column-mapping
screen remains available for future workbook variants.

The supplied workbook uses Roman numeral `I` for class. Class is not part of the primary
student lookup. For cross-checking, normalize Roman numerals `I` through `XII` to class
sort order `1` through `12` and compare that with the student's resolved class record.
Section `A` is compared case-insensitively with the student's resolved section. Do not
depend on an exact global class-name string for this verification.

### Image number resolution

The first release accepts JPG, JPEG, and PNG files whose basename matches:

`^DSC_?0*([0-9]+)$`

Matching is case-insensitive. The captured decimal number is normalized by removing
leading zeroes and compared with normalized `ImageNo`.

Examples:

- `ImageNo=5747` matches `DSC5747.jpg`.
- `ImageNo=5747` also matches `DSC_05747.JPG`.
- `ImageNo=5747` does not match `IMG5747.jpg` or `DSC5747-copy.jpg`.

The prefix rule is configuration, not a fuzzy matcher. More than one Drive file
resolving to the same image number is a blocking duplicate. A blank `ImageNo` becomes
`MISSING_IMAGE_NUMBER` and is held without attempting name-based or face-based matching.

## 4. School, Year, and Drive Identification

The authenticated platform selection is authoritative because the workbook does not
contain school or year identifiers.

During school onboarding, the backend:

1. resolves the new school's immutable `school_uid`;
2. resolves the school's current `academic_year_id`;
3. creates or finds the managed school, academic-year, and intake folders;
4. stores each immutable Google Drive folder ID; and
5. records `READY` or a retryable `FAILED` status without rolling back the school.

When an operator manually starts an import, the backend takes the already provisioned
intake folder for the selected school/year. It no longer requires a pasted folder URL.
The folder remains permanently bound to the school UID and academic-year ID. Sequential
jobs reuse that managed intake folder; only one unfinished batch may use it at a time.
Environments without a complete personal OAuth connection and configured root retain
the prior paste-and-verify path as a temporary compatibility fallback; it is hidden
when managed Drive is ready.

Folder names, workbook names, school names, short codes, and year labels are display
evidence only. They are not accepted as database keys.

Operations users may select only schools in their assigned operator-school set.
Superadmin may select any active school. A new write-scope resolver must require a
non-null school and enforce this assignment; the existing unbounded platform read scope
must not be reused for execution.

The first release permits execution only for the selected school's current academic
year, calculated through the existing school-specific academic-year start month. This
is required because `student.students.photo_url` is currently a profile-level value,
not a year-specific photo. Supporting historical-year photos would require a separate
year-scoped student-photo table.

Student lookup always includes all of:

```sql
WHERE school_id = :schoolId
  AND academic_year_id = :academicYearId
  AND admission_no = :admissionNo
  AND deleted_at IS NULL
```

Changing the school or academic year invalidates the Drive scan and mappings. A Drive
folder cannot have two active batches, and an unchanged snapshot cannot be processed
again. After a terminal batch, Operations replaces the source files and starts a new
batch against the same managed folder.

## 5. Source Folder Rules

Custoking creates one reusable restricted intake folder for the school's current
academic year:

`GFA - Greenfield Academy / 2026-27 / Student Photo Intake`

The folder contains:

- one `.xlsx`, `.xls`, UTF-8 `.csv`, or UTF-8 `.tsv` mapping file;
- JPG, JPEG, or PNG files; and
- no unrelated school documents.

The folder is shared directly with the photographer's Google account, not with
`Anyone with the link`. It starts empty, editor re-sharing is disabled, and the
photographer's permission is removed after the batch reaches a terminal result. The
application records a 14-day access-removal reminder and lets Operations record when
revocation is complete; the actual Google permission change remains a manual action.

Google Drive folder editors can normally view, add, edit, move, and delete items in the
folder. The dedicated empty folder limits this exposure to the current job only. Drive
is an intake surface, not the permanent student-photo store.

## 6. Operator Workflow

1. Open `Operations > Students > Photo imports`.
2. Select the school and confirm that its managed folder is `READY`.
3. Copy or open the generated Drive folder, share it with the photographer as Editor,
   and send the link.
4. After the workbook and photos are uploaded, click **Start manual import**.
5. Click **Scan folder**. The system records a source snapshot containing Drive file IDs,
   names, sizes, MIME types, checksums where available, and modified times.
6. Select the workbook. The exact supplied headings auto-map to the canonical fields;
   future variants can be mapped manually.
7. Validate the batch without changing student records.
8. Resolve missing students, filename duplicates, name/class mismatches, and unmapped
   images. Individual rows can be held or excluded.
9. Freeze the source snapshot. Later Drive changes do not silently enter the batch.
10. Review the processed portrait and adjust the square crop when required.
11. Confirm the school, academic year, ready count, replacement count, and held count.
12. Execute the batch. Only rows in `READY` state are applied.
13. Review the result report and revoke the photographer's Drive access.

## 7. Matching Rules

The operator-selected `school_id`, resolved `school_uid`, and `academic_year_id` are
authoritative. Workbook or folder labels never override them.

For each mapping row:

1. Read `ImageNo` and `AdmissionNo` as formatted identifier strings.
2. Resolve the normalized image number to exactly one accepted Drive filename.
3. Resolve `AdmissionNo` to exactly one active student in the selected school and year.
4. Verify that the student's academic-year enrollment matches the selected year.
5. Compare normalized name, class, section, and roll number when provided.
6. Calculate a deterministic row status.

Statuses:

- `READY`: filename and student matched; no blocking conflict.
- `WARNING`: primary match is valid but a secondary field differs.
- `MISSING_IMAGE_NUMBER`: workbook row has no `ImageNo`; rows 13 and 14 in the supplied
  workbook start in this state.
- `UNMATCHED_PHOTO`: no accepted Drive filename resolves to the workbook `ImageNo`.
- `UNMAPPED_FILE`: Drive image has no workbook row.
- `STUDENT_NOT_FOUND`: admission number is absent in the selected school.
- `DUPLICATE_IMAGE_NUMBER`: more than one row or Drive file resolves to the same image
  number.
- `DUPLICATE_STUDENT`: multiple photos target one student; operator must choose one.
- `YEAR_MISMATCH`: student is not enrolled in the selected academic year.
- `INVALID_IMAGE`: image decode or safety validation failed.
- `HELD`, `APPROVED`, `APPLIED`, `FAILED`.

No fuzzy filename match or automatic face recognition is used. Suggestions may be shown
to the operator, but a suggestion never becomes an executable mapping without approval.

## 8. Image Processing

Drive bytes are streamed into a private staging prefix; they are never made public.

The server:

- verifies decoded media rather than trusting the extension or Drive MIME type;
- rejects empty, oversized, malformed, or excessive-pixel images;
- applies EXIF orientation before stripping all metadata, including GPS;
- converts the image to sRGB;
- creates a normalized review image with a bounded long edge;
- detects portrait/landscape orientation and proposes a centered square crop;
- lets the operator adjust the crop for warnings;
- produces the final 512 by 512 JPEG at the existing quality setting; and
- writes the final object through the existing tenant-scoped photo storage path.

Approval updates `student.students.photo_url` only after the final object is stored.
Each row is idempotent by batch ID, Drive file ID, source version, student ID, and image
checksum. Retrying a partial batch cannot attach the same row twice.

## 9. Service Design

Add dedicated tables:

- `student.photo_import_drive_folders`
- `student.photo_import_batches`
- `student.photo_import_sources`
- `student.photo_import_mappings`
- `student.photo_import_rows`

Every table carries `school_id`; row-level security follows the existing student import
tables. The batch also stores `school_uid`, `academic_year_id`, and `drive_folder_id`.
Store Drive IDs and metadata, not Drive access tokens. Personal OAuth credentials
remain in Secret Manager.

Add a partial unique index on `drive_folder_id` for unfinished statuses. The managed
folder can be reused sequentially for the same school and academic year, but concurrent
jobs are rejected. A terminal snapshot hash prevents accidental replay of unchanged
source files.

Suggested internal APIs:

- `POST /api/v1/student-photo-imports`
- `POST /api/v1/student-photo-imports/{id}/scan`
- `POST /api/v1/student-photo-imports/{id}/column-mapping`
- `POST /api/v1/student-photo-imports/{id}/validate`
- `GET /api/v1/student-photo-imports/{id}/rows`
- `POST /api/v1/student-photo-imports/{id}/rows/{rowId}`
- `POST /api/v1/student-photo-imports/{id}/freeze`
- `POST /api/v1/student-photo-imports/{id}/execute`
- `GET /api/v1/student-photo-imports/{id}/result`
- `POST /api/v1/student-photo-imports/{id}/cancel`
- `POST /api/v1/student-photo-imports/{id}/access-revoked`

All endpoints require an authenticated `OPERATIONS` or `SUPERADMIN` principal plus a
new `student:photo-import` permission. School administrators do not receive this
permission in the first release.

Drive import uses `files.list` to enumerate the folder and `files.get?alt=media` to
stream supported binary files. Provisioning uses `files.create` only for managed
folders under the configured personal My Drive root. It does not upload, rename, move,
or delete photographer files.

## 10. Execution and Audit

Before execution the server rechecks:

- role and permission;
- tenant scope;
- `school_id`, `school_uid`, current `academic_year_id`, and permanent Drive-folder
  binding;
- frozen Drive file IDs and versions;
- student existence and enrollment;
- row status and checksum; and
- whether the target student's photo changed after validation.

The batch records the operator, request ID, selected scope, column mapping, source
snapshot, validation totals, crop data, prior object key, new object key, row outcome,
and timestamps. It emits outbox events for batch completion and student-photo changes.

Execution is row-transactional. One failed row does not roll back successful rows.
The result report clearly separates applied, held, skipped, and failed rows.

## 11. UI

The Operations screen has five stages:

1. **Scope & source**
2. **Column mapping**
3. **Validation**
4. **Review**
5. **Execute**

The school and academic year remain visible throughout the workflow with their immutable
identifiers available in the scope details. The scope screen explicitly states that the
workbook contains no school/year metadata and that Custoking is providing the binding.
The Execute screen requires an explicit confirmation checkbox and never hides warnings
behind a total. Tables use fixed action columns, responsive horizontal scrolling, and
compact text sizes consistent with the current Custoking workspace.

## 12. Delivery Plan

1. **Schema and Drive reader:** batch persistence, folder access validation, file
   inventory, workbook parser, and personal OAuth credential configuration.
2. **Validation engine:** column mapping, exact matching, tenant/year checks, row status
   calculation, and source freezing.
3. **Image pipeline:** safe decoding, orientation, metadata stripping, staging, crop
   preview, final 512px output, and idempotent attach.
4. **Operations UI:** five-stage workflow, issue resolution, crop review, confirmation,
   execution progress, and downloadable result.
5. **Verification:** unit tests, Drive adapter contract tests, tenant/BOLA tests,
   malformed-image tests, retry tests, audit assertions, and a DEV pilot with one class.

Expected effort for one full-stack engineer is approximately 12 to 18 working days,
including DEV hardening. Pilot with 30 to 50 images before a whole-school batch.

## 13. Acceptance Criteria

- A photographer can complete delivery without any Custoking account.
- An image number cannot be applied without a unique Drive file, workbook row, and
  school/year-scoped student match.
- The workbook cannot redirect a batch to another school or academic year.
- The supplied workbook's two blank `ImageNo` rows are held automatically.
- `ImageNo=5747` deterministically resolves to `DSC5747.jpg` under the configured camera
  filename rule.
- Workbook class `I` cross-checks class sort order `1`; it is not used to select the
  student.
- A Drive folder cannot be rebound to another school/year or used by concurrent batches;
  a terminal folder can be reused only after its source snapshot changes.
- Operations cannot write to a school outside their assigned operator-school set.
- Historical academic years are rejected until year-specific photo storage exists.
- Operations and Superadmin are the only roles able to scan or execute.
- No student photo changes during scan, mapping, validation, or review.
- Approved images are correctly oriented, metadata-free, square, and 512px.
- Revoked or changed Drive files cannot silently alter a frozen batch.
- Cross-school row access and student mapping are denied.
- A retry after partial failure is safe and produces an auditable result.

## 14. References

- Google Drive folder permissions:
  https://support.google.com/drive/answer/7166529
- Google Drive `files.list`:
  https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list
- Google Drive download/export:
  https://developers.google.com/workspace/drive/api/guides/manage-downloads
- Google OAuth offline access:
  https://developers.google.com/identity/protocols/oauth2/web-server
- Google Drive OAuth scopes:
  https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- Restricted-scope personal-use exception:
  https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification
- Existing final photo pipeline:
  `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/StudentPhotoStorage.java`

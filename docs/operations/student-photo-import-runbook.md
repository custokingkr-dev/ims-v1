# Student Photo Import Runbook

## Purpose

This runbook covers the personal Google Drive intake used by Operations and Superadmin.
The photographer receives access only to a school/year intake folder and never receives
a Custoking account.

## Environment Readiness

Before enabling an environment, verify all of the following:

- Google Drive API is enabled in the environment's Google Cloud project.
- A dedicated personal Google account owns the intake root and has two-step verification
  plus recovery details controlled by Custoking.
- The OAuth consent app is External and published. Do not operate with the app in
  `Testing`, because offline refresh tokens expire after seven days.
- Secret Manager has enabled `latest` versions for the client ID, client secret, and
  refresh token secrets documented in `deploy/gcp/README.md`.
- The GitHub Environment variable
  `STUDENT_PHOTO_IMPORT_DRIVE_ROOT_FOLDER_ID` contains only the Drive folder ID.
- The school-core runtime identity can read those secrets and write to the private
  `custoking-student-photos-<env>` bucket.

After any OAuth or root-folder change, deploy `school-core-service` and confirm that
**Operations > Students > Photo imports** reports Drive and managed Drive as configured.

## School Provisioning

New schools are provisioned during onboarding. For an existing school:

1. Open **Operations > Students > Photo imports**.
2. Select the school and its current academic year.
3. Click **Provision folder** if the folder state is not `READY`.
4. Open the generated link and confirm this hierarchy under the configured root:
   `<SHORT CODE> - <School> / <Academic year> / Student Photo Intake`.
5. Confirm the folder belongs to the selected school/year before sharing it.

Provisioning is idempotent. Use the repair action after a transient failure; do not
manually replace database folder IDs or move managed folders outside the configured root.

The superadmin **School accounts** view derives onboarding readiness from two independent facts:
an assigned administrator and a `READY` current-year Drive binding. A school created while Drive
returns `FAILED` or `NOT_CONFIGURED` remains valid but is shown as **Action needed**; use
**Retry Drive** after correcting credentials/root configuration. Do not create a duplicate school.

## Import Procedure

1. Empty or archive the previous delivery from the intake folder.
2. Share only the intake folder with the photographer's named Google account as Editor.
   Never use `Anyone with the link`.
3. Ask for exactly one `.xlsx`, `.xls`, `.csv`, or `.tsv` mapping file and the
   JPG/JPEG/PNG camera files. Excel files must contain one visible mapping sheet;
   hidden report/reference sheets are ignored, but multiple visible mapping sheets are
   rejected. CSV and TSV files must use UTF-8. Every format uses the exact headings
   `AdmissionNo`, `Name`, `Class`, `Section`, and `ImageNo`. A batch can contain up to 1000 mapping rows.
   Each source image can be up to 20 MB. Execution applies the source EXIF orientation,
   preserves the complete source frame and aspect ratio, bounds the longest edge to 512 pixels,
   and stores a metadata-stripped JPEG without modifying the original Drive file.
4. Start a manual import and scan the folder.
5. Review the immutable school/year scope and validation totals.
6. Correct admission/image identifiers, exclude intentionally blank rows, and preview the
   full-frame processed portrait. Legacy crop coordinates can still appear in API/data records
   for compatibility, but they do not crop the preview or stored photo.
7. Resolve all errors and freeze the batch. A changed Drive snapshot requires a rescan.
8. Confirm the displayed school, year, and ready count, then execute. Execution uses
   bounded chunks and can be resumed after an interrupted browser request.
9. Download the CSV result and retain it with the operations ticket.
10. Remove the photographer's Drive permission, then click **Mark revoked**. The UI
    shows an overdue reminder 14 days after batch creation until this is recorded.

Only one unfinished batch can use an intake folder. `PARTIAL` and `FAILED` batches must
be retried or cancelled before a new batch starts. Cancelling does not roll back photos
already applied by a partial batch. An unchanged terminal source snapshot is rejected;
replace the workbook or images before creating a new job.

## Failure Handling

- `drive_access_denied`: restore the dedicated owner's access and verify the refresh
  token was granted by that same account.
- `source_changed`: stop, inspect the Drive delivery, rescan, repeat review, and freeze.
- `source_already_imported`: the folder still contains an already processed delivery.
- `PARTIAL` or `FAILED`: correct the reported source/storage issue and resume execution.
  Applied rows are idempotent and are not processed again.
- Folder provisioning `FAILED`: verify root ownership, OAuth scope, and root folder ID,
  then run repair from the Photo imports screen.
- OAuth `invalid_grant`: generate a new offline refresh token, add a new Secret Manager
  version, redeploy school-core, and disable the compromised/expired secret version.

### Recovering photos imported with the former crop behavior

For a terminal batch with applied rows, use **Restore full-frame photos** in the Photo imports
screen. Recovery downloads each selected row's retained Drive file ID, verifies the retained
source metadata and checksum, applies EXIF orientation, and writes a new aspect-ratio-preserving
JPEG with a longest edge of at most 512 pixels. Requests are sent in batches of at most 100 rows.

Recovery is audited and idempotent: repeating a completed recovery does not download or rewrite
the photo. It refuses to overwrite a student photo that has changed since that import, and it
reports a failure when the retained Drive original is missing, changed, or inaccessible. Resolve
those rows individually; do not replace newer/manual student photos merely to make the recovery
count reach zero.

Do not delete batch or row records to unblock an import. Cancel the batch through the UI
so the audit event and operator identity are retained.

## Evidence And Privacy

The database retains the workbook mapping, Drive metadata, snapshot hash, legacy crop values,
prior/final photo keys, recovery audit, outcomes, and operator timestamps. Legacy crop values are
retained for compatibility but are not used when processing or recovering photos. When the private
photo bucket is configured, the original workbook remains under the school-scoped import evidence prefix.
Applied source images are retained for 14 days under the isolated
`temporary/photo-imports/` prefix, then deleted by bucket lifecycle. Final student images are
EXIF-oriented, metadata-stripped JPEGs that preserve the complete source aspect ratio and have a
longest edge of at most 512 pixels. They remain under
`schools/<school-storage-id>/students/<student-id>/photos/`; the temporary lifecycle cannot match
that permanent prefix. The original camera file also remains in the controlled Google Drive
intake until Operations archives or removes it.

Treat the intake and evidence bucket as child personal data. Access is least-privilege,
objects are private, and result CSV files belong only in the restricted operations case.
Do not broaden the lifecycle prefix or add a lifecycle rule to the permanent `students/` path.

## DEV Pilot And PROD Gate

Before promotion to PROD, complete one 30 to 50 student DEV pilot and attach evidence:

- correct school and academic-year folder binding;
- blank and invalid image numbers held without student changes;
- one manual remap, one exclusion, and portrait/landscape full-frame previews;
- freeze rejects a changed Drive snapshot;
- successful execution, CSV export, retry/cancel behavior, and access revocation;
- no cross-school access using an Operations account assigned to another school;
- no application HTTP 5xx errors in school-core/gateway logs for the pilot window.

PROD requires separate personal-account OAuth secrets, a PROD root folder variable,
approved bucket retention, GitHub Environment approval, and promotion of the exact image
tag already validated in DEV. DEV credentials or folder IDs must never be reused in PROD.

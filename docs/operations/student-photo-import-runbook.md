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

## Import Procedure

1. Empty or archive the previous delivery from the intake folder.
2. Share only the intake folder with the photographer's named Google account as Editor.
   Never use `Anyone with the link`.
3. Ask for exactly one `.xlsx`, `.xls`, `.csv`, or `.tsv` mapping file and the
   JPG/JPEG/PNG camera files. Excel files must contain one visible mapping sheet;
   hidden report/reference sheets are ignored, but multiple visible mapping sheets are
   rejected. CSV and TSV files must use UTF-8. Every format uses the exact headings
   `AdmissionNo`, `Name`, `Class`, `Section`, and `ImageNo`. A batch can contain up to 1000 mapping rows.
   Each source image can be up to 20 MB; execution reduces the stored student portrait
   to a normalized JPEG without modifying the original Drive file.
4. Start a manual import and scan the folder.
5. Review the immutable school/year scope and validation totals.
6. Correct admission/image identifiers, exclude intentionally blank rows, adjust crop
   focus where necessary, and preview the processed portrait.
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

Do not delete batch or row records to unblock an import. Cancel the batch through the UI
so the audit event and operator identity are retained.

## Evidence And Privacy

The database retains the workbook mapping, Drive metadata, snapshot hash, crop values,
prior/final photo keys, outcomes, and operator timestamps. When the private photo bucket
is configured, the original workbook and each applied source image are stored under the
school-scoped import evidence prefix. Final student images are normalized 512 by 512
JPEGs with metadata removed.

Treat the intake and evidence bucket as child personal data. Access is least-privilege,
objects are private, and result CSV files belong only in the restricted operations case.
Apply the approved data-retention policy before PROD; do not invent a lifecycle deletion
period without the data owner's written approval.

## DEV Pilot And PROD Gate

Before promotion to PROD, complete one 30 to 50 student DEV pilot and attach evidence:

- correct school and academic-year folder binding;
- blank and invalid image numbers held without student changes;
- one manual remap, one exclusion, and left/right crop previews;
- freeze rejects a changed Drive snapshot;
- successful execution, CSV export, retry/cancel behavior, and access revocation;
- no cross-school access using an Operations account assigned to another school;
- no application HTTP 5xx errors in school-core/gateway logs for the pilot window.

PROD requires separate personal-account OAuth secrets, a PROD root folder variable,
approved bucket retention, GitHub Environment approval, and promotion of the exact image
tag already validated in DEV. DEV credentials or folder IDs must never be reused in PROD.

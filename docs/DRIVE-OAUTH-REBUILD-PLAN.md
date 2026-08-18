# Drive Photo Import — OAuth Rebuild Plan

**Prepared:** 2026-08-18
**Why this exists:** the OAuth client behind Drive photo import is owned by the source project
`custoking`. OAuth clients cannot be moved between projects, so it dies when that project is deleted.
**This gates deleting `custoking`. It does not gate the migration** — photo import works today in
`custoking-dev` using the existing client, and will keep working in `custoking-prod`, right up until the
source project is removed.

## 1. What was measured, not assumed

A live token exchange on 2026-08-18 returned:

- granted scope **`https://www.googleapis.com/auth/drive`** — the full Drive scope, which Google
  classifies as **restricted**, the most tightly controlled tier;
- Drive API `HTTP 200`, so the credential is healthy;
- the prod refresh token was minted 2026-08-02 and still worked 16 days later, so the consent screen is
  **published, not in Testing** (Testing-mode refresh tokens expire after 7 days).

The client ID's numeric prefix is the source project's number, which is how ownership was established.

## 2. Why the scope cannot simply be reduced

`GoogleDrivePhotoImportClient` does three things that decide this:

1. **creates** folders (school → academic year → intake) via `files.create` with the folder MIME type;
2. **lists** the intake folder with `q='<folderId>' in parents and trashed = false`;
3. **downloads** each file with `alt=media`.

Steps 2 and 3 read files **uploaded by photographers**, which the application did not create.

| Candidate scope | Restricted? | Works here? |
| --- | --- | --- |
| `drive` (current) | **yes** | yes |
| `drive.readonly` | **yes** | reading yes, folder creation no — and no verification saving |
| `drive.file` | no | **no** — covers only files the app created or the user picked in the Picker, so third-party uploads are invisible |

So a like-for-like rebuild carries the restricted-scope burden. The only way out is to stop using a
user credential at all — see option A.

## 2a. How the flow actually works, and why that lowers the risk

Verified in code, because it decides how much the folder-creation question matters:

- **Folder provisioning is automatic.** `POST /schools` calls `photoFolders.ensureForSchool(schoolId)`,
  which builds school → academic year → intake in Drive.
- **It fails soft.** `DriveFolderProvisioningService` wraps every Drive call and returns
  `ProvisioningResult.failed(...)` instead of throwing, recording `status` and `last_error` in
  `student.photo_import_drive_folders`. A Drive outage therefore does **not** break school creation.
- **There is a retry path**: `POST /folders/{schoolId}/provision`.
- **Import is operator-triggered**, and its hot path is list + download only.

So creation is rare (once per school per academic year), non-fatal, and retryable, while the frequent path
is pure reads. That is the opposite of the risk profile that would rule out a service account.

Measured in production 2026-08-18: **7 bindings, all `READY`, zero failures**. Four of the eleven schools
have no binding at all, which matches exactly the four schools with no photos — they predate or do not use
the feature.

### Known gap: provisioning failure is silent

`DriveFolderProvisioningService` has **no logger**. Failures are swallowed into the returned result and
the database column, and nothing is written to Cloud Logging, so no log-based metric or alert can see
them. Provisioning could fail for every new school and the only symptom would be an operator eventually
finding an import has nowhere to read from.

Nothing is currently being hidden, but this must be fixed **as part of whichever option is chosen** —
add a warning log on the failure branch, then a log-based metric and alert alongside the existing
outbox/inbox metrics. It is a code change, so it is deliberately not bundled into migration week.

## 3. Option A — replace the user credential with a service account (evaluate first)

Instead of an OAuth client plus a user refresh token, give the runtime service account access by
**sharing the Drive folders with its email address**. A service account authenticates as itself, so
there is no consent screen, no verification, no CASA assessment, and no refresh token to expire.

Steps:
1. Decide the identity — reuse `ims-school-core-<env>@custoking-<env>.iam.gserviceaccount.com` or create
   a dedicated one.
2. Share the Drive root folder with that email, with Editor access, from the account that owns it today.
3. Change `GoogleDrivePhotoImportClient` to build `GoogleCredentials` from the runtime service account
   (Application Default Credentials on Cloud Run) scoped to `https://www.googleapis.com/auth/drive`,
   instead of `UserCredentials` from client id / secret / refresh token.
4. Retire the three `student-photo-import-drive-oauth-*` secrets per environment.

**Verify before committing to this.** Two things need a live test, not reasoning:
- whether folder *creation* succeeds — files a service account creates are owned by the service account,
  and outside Google Workspace service accounts have awkward Drive storage quota behaviour. Reading
  shared files is well-trodden; creating them is where this approach usually breaks.
- whether every existing intake folder is reachable once shared, including any on a Shared Drive
  (the client already sends `supportsAllDrives=true`).

If folder creation fails, a hybrid works: keep provisioning folders manually or under the existing user
credential, and use the service account only for the list/download path.

## 4. Option B — rebuild the OAuth client in the destination organization

Like-for-like. Do this per environment, or once with both redirect URIs.

1. **Consent screen** — in `custoking-prod` (and `custoking-dev` if you want them independent), open
   *APIs & Services → OAuth consent screen*. User type **External**, since the operator accounts are
   consumer Google accounts rather than Workspace members.
2. **Enable the Drive API** in the destination project. Already enabled: `drive.googleapis.com` is in the
   19-API set applied to `custoking-dev`.
3. **Add the scope** `https://www.googleapis.com/auth/drive`. The console will mark it restricted.
4. **Create the client** — *Credentials → Create credentials → OAuth client ID*. Type **Desktop app** is
   simplest, because the refresh token is minted once by an operator and then stored; a Web application
   client also works but needs a redirect URI you control.
5. **Add the operator as a test user** while the app is unverified. This works immediately, but
   **refresh tokens expire after 7 days in Testing** — fine for validating the plumbing, not for
   unattended running.
6. **Mint the refresh token** with a one-time consent, as the account that owns the Drive folders:
   authorise with `access_type=offline` and `prompt=consent`, exchange the authorisation code, and keep
   the `refresh_token` from the response.
7. **Store the three values** in Secret Manager in each destination project, under the existing names so
   no deployment configuration changes:
   `student-photo-import-drive-oauth-client-id-<env>`,
   `student-photo-import-drive-oauth-client-secret-<env>`,
   `student-photo-import-drive-oauth-refresh-token-<env>`.
8. **Submit for verification**, which for a restricted scope also requires an annual third-party
   **CASA security assessment**. This is the long pole: expect weeks to months, and a cost. Start it as
   soon as the client exists — it runs in parallel with everything else.
9. **Verify end to end** before relying on it: exchange the refresh token, confirm the granted scope
   comes back as `.../auth/drive`, and run one real import.

## 5. What does not change

- **Drive folders survive.** They belong to a Google account, not to a project, so folder IDs and the
  seven rows in `student.photo_import_drive_folders` stay valid. Only the credential is rebuilt.
- `STUDENT_PHOTO_IMPORT_DRIVE_ROOT_FOLDER_ID` is unchanged in both environments.
- No application code changes are needed for option B; the secret names are already what the services read.

## 6. Sequencing

Deleting `custoking` must wait for whichever option is chosen to be **proved with a real import** in the
destination. Until then the source project must stay alive purely to host the OAuth client, even after
everything else has moved.

Recommended: test option A first, because it removes the verification problem entirely and is days of
work rather than weeks of waiting. Start option B's verification in parallel as the fallback, since its
cost is calendar time rather than effort.

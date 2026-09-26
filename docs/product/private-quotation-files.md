# Private quotation files

Urgent procurement drafts can attach one PDF, PNG, or JPEG to each saved quotation. A selected file is explicitly pending until the upload API confirms its metadata. If uploading fails, the saved request and quotation IDs are retained; retry sends the file again without creating another quotation. Approval reviewers download the same attachment through their authenticated session. Legacy document references remain plain text.

## Authorization and API

All routes use the existing `/api/v1/ff/` gateway route and internal firefighting service token. The operations service also requires an authenticated user and effective `firefighting:read` permission for capabilities/downloads or `firefighting:update` for uploads/removal. Superadmin access follows the existing tenant bypass. School scope is enforced in the query and PostgreSQL row-level security; a caller cannot supply a school or storage key.

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/v1/ff/quotation-documents/capabilities` | Availability, upload authorization, limits, actionable unavailable reason |
| POST | `/api/v1/ff/requests/{code}/quotations/{quotationId}/document` | Multipart `file`; confirmed attachment metadata |
| GET | Same document path | Authenticated attachment bytes, private/no-store, nosniff, sandbox CSP |
| DELETE | Same document path | 204 after detaching the file |

Metadata includes `id`, `filename`, `contentType`, `sizeBytes`, and `uploadedAt`. Request detail and quotation lists include this as nullable `document`. Storage keys, bucket names, signed URLs, and public URLs are never returned. Both modification endpoints require the parent request to remain a draft. Submission and quotation mutations lock the parent request before the quotation so an in-flight upload cannot attach new evidence after submission.

## Storage and deployment prerequisites

Apply operations Flyway migrations `firefighting/V12__private_quotation_documents.sql` and `V13__procurement_creation_replays.sql` during an authorized deployment. Configure `FIREFIGHTING_QUOTATION_DOCUMENT_BUCKET` for the operations service and grant its workload identity `storage.buckets.get` on that bucket plus `storage.objects.create`, `storage.objects.get`, and `storage.objects.delete` for its quotation objects. No list permission is required. The bucket must have uniform bucket-level access and explicitly enforced public access prevention. The service checks both before each object operation and fails closed when configuration, credentials, or bucket privacy are unavailable. No local-disk fallback is enabled.

The servlet allows a 5 MiB file and 6 MiB total multipart request, within the gateway's 8 MiB body limit. File signatures and parseability are checked independently of caller MIME and filename. Images are bounded to 20 megapixels; PDFs must be unencrypted, contain 1–200 pages, and have no document-level JavaScript. Files are served as attachments rather than embedded content. Filenames are sanitized and extensions follow validated content. A SHA-256 and byte count check protects retrieval integrity. These checks do not constitute antivirus scanning.

No cloud resources, IAM grants, bucket changes, or deployed migrations were executed during implementation. With the bucket unset, the UI reports that private file storage is not configured and continues allowing quotation details and honest text references.

## Replacement and interrupted uploads

Each upload uses a fresh private object key, `schools/{schoolId}/firefighting/quotations/{uuid}.{extension}`, with a create-only storage precondition. A durable `PENDING` row is committed before contacting storage. The existing attachment is retained until the new object is confirmed and its `READY` row is attached in one database transaction. Replacement, explicit removal, and quotation deletion retire the previous document through a database trigger.

The internal cleanup job checks up to 20 due, unreferenced rows every five minutes by default (`FIREFIGHTING_QUOTATION_DOCUMENT_CLEANUP_DELAY_MS`). The existing Cloud Run IAM-protected Scheduler request to `/api/v1/internal/outbox/relay` also runs the same cleanup and returns `quotationDocumentsChecked`. No new public route or continuously running worker is required. Failed/retired uploads are cleaned when due; crash-left pending reservations expire after one hour. Storage deletion failures retry after ten minutes. Deleted tombstones are reconciled daily to cover a storage write that finishes after a lost response or first cleanup attempt. The job restores its previous tenant context after its narrowly scoped maintenance transaction.

Cleanup starts no further object after a 20-second elapsed budget and uses a 45-second database transaction timeout. GCS calls have a ten-second retry budget, at most two attempts, and explicit three-second connection/five-second read timeouts; an already-started metadata check/delete can finish after the start budget. Durable rows survive idle periods and retry failures. The existing Scheduler trigger supplies CPU even when the service scales to zero; deployment verification must confirm that Scheduler is enabled and can invoke its private relay endpoint. The in-process timer alone cannot guarantee execution while idle CPU is throttled.

## Validation

Tests cover mocked GCS privacy and create-only writes, real PDF/image validation, controller token/multipart/response boundaries, PostgreSQL migrations and row-level security under a non-bypass application role, cross-school denial before storage access, permission denial, submitted-request protection, replacement cleanup, lost storage responses, expired reservations, and cleanup retries. UI tests cover pending versus confirmed files, retry without duplicate quotation creation, unavailable storage, authenticated downloads, reviewer error recovery, and object URL release. Cloud upload and deployed infrastructure verification remain deployment checks.

## Lost responses and creation retries

Request creation (`POST /api/v1/ff/requests`, including the existing workspace compatibility alias) and quotation creation (`POST /api/v1/ff/requests/{code}/quotations`) now require `idempotencyKey`: 1–128 characters matching `[A-Za-z0-9._:-]+`. This is an intentional client compatibility change. Reload/update older clients before creating requests or quotations; no unkeyed creation path remains. The canonical and compatibility request endpoints both stamp actor identity from authenticated context.

V13 stores a tenant- and operation-scoped key, canonical payload SHA-256, and entity identifier in the same transaction as creation and its outbox event. A replay returns the original entity without another insert/event. A different payload using that key returns 409. Receipts survive quotation removal, so replaying a removed quotation returns 410 instead of recreating it. Receipt rows contain no request description, vendor text, or file content. Runtime grants permit SELECT/INSERT and RLS restricts school access. Concurrent replay and rollback are covered by real PostgreSQL tests.

Submission is monotonic: replay returns the current request status without a second transition/event, including when an approver has already advanced it. The editor reconciles lost submission responses using authenticated request detail, and requires a matching request identifier and recognized submitted status before confirming. A missing or malformed success response remains unresolved. Confirmed quotation identifiers are kept, and file-upload errors say that the upload may already have completed; a replacement retry safely retires the previous attachment.

Before a create or submit call, the editor records its exact pending payload and key in `localStorage`, scoped by authenticated user, school, and editor entry. Pending business fields can include request title/description/budget/date or vendor name/amount/notes/document reference and selected filename. It stores no authentication token, student data, or file contents. Pending fields are removed after authoritative confirmation, leaving the known draft identifier until the workflow completes. Unresolved records deliberately survive logout and tab/browser closure so signing in again cannot silently start a duplicate. Another user's UI does not read them. The recovery screen discloses this retention; manually clearing browser/site data removes recovery, so review saved requests before recreating an uncertain operation. Files must be selected again after reopening.

Same-scope saves hold an exclusive Web Lock across HTTP, reconciliation, and recovery-record cleanup; stale tabs verify the persisted record before any mutation and cannot replace another tab's pending record. Browser storage failure, malformed saved data, unsupported secure Web Locks, or a busy lock fails closed with a visible explanation. Serve the application over HTTPS (localhost works in local development) in a supported browser. Known title/text/date and whole-number amount constraints are checked before recording a creation attempt, keeping invalid input editable.

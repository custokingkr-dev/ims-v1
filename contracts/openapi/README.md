# Browser API contracts

Run `node scripts/generate-openapi-typescript-client.js` after a contract change,
and `node scripts/generate-openapi-typescript-client.js --check` in verification.
The route inventory must first be current (`node scripts/generate-api-route-inventory.js`).
The always-run CI architecture job checks generation and the generator/routing tests.

Current coverage is **130 canonical operations across 13 contract files/clients**:

| Contract | Operations | Covered workflow |
| --- | ---: | --- |
| Identity | 13 | Login, refresh, logout, password reset, MFA and passkey enrollment/authentication/recovery |
| Fees | 6 | Idempotent payment, receipt JSON/PDF, collection/overdue reports, reminders |
| Students | 40 | Directory/detail/history, admission/profile/roster/photos, file preview/confirmation/progress/evidence, promotion batches, review campaigns/items/verification, confirmed erasure |
| Drive photo-import | 15 | Scoped context/batches, creation/provisioning, reviewed scan/freeze/execute/cancel, row review, recovery/progress, private JPEG and CSV |
| Attendance | 3 | Read/save register, submit section |
| Billing | 1 | Complete invoice statistics; Java-bound payment DTO exported with exact compatibility-route binding |
| Broadcasts | 8 | Draft/list, capabilities, preview, fingerprint approval, explicit live confirmation, dry-run/live outcomes |
| Quotation documents | 4 | Capabilities, multipart upload, authenticated download, removal |
| Catalog | 6 | Paginated order read, scoped statistics, guarded status/delivery, annual-plan review/confirmation |
| Reporting | 7 | Workspace, command-center dashboard, fee defaulters, low-attendance sections/students, vendor dues and reorder signals |
| Firefighting creation | 2 | Stable-key request and quotation creation |
| Workflow decisions | 3 | Read versioned instance, approve and reject with required reviewed expectedVersion |
| Notebook order tracker | 22 | Worklist and tracker read, step drafts/stage notes/complete/reopen/sign-off, assignment, follow-ups, design send/approve/request-changes, vendor/designer/internal notes, designers and per-school time slots. Request and response schemas bind to the Java records; the browser sends `expectedVersion` from the loaded step. The two machine-only endpoints (tracker creation and design-image access) are deliberately not in a browser contract. |

These are critical browser contracts, not a claim of complete coverage of every
controller. Legacy report row extensions and receipt detail fields remain explicit
extensible objects; payment results, upload metadata, attendance registers, billing
totals and broadcast policy/outcome envelopes have named fields. Runtime validation
still belongs to the server and existing frontend response parsers.

The reporting drilldowns use the shared authenticated Axios transport and retain optional school,
date, fee-filter and pagination parameters. Section identifiers are encoded once in the path.
Nullable projected vendor timestamps remain nullable in the contract and browser types.
Serialized server-response checks cover all six added endpoints, alongside negative tests for
cross-school requests, missing permissions and invalid service tokens. Existing PostgreSQL/RLS
tests verify the underlying query behavior; generated clients do not retry denied requests.

Each specification declares its owning service/controller and gateway service.
Per-operation controller overrides are supported. Generation rejects aliases,
internal paths, missing gateway routes, ambiguous success representations,
undeclared path parameters, unresolved references and unsupported media. Selected
schemas bind to Java records to detect property/type drift; map-returning handlers
also need behavioral tests. This source check does not replace server tests.

Generated clients share the authenticated Axios instance, return response **data**,
encode path segments, pass filters through Axios `params`, build multipart bodies
without overriding the browser boundary, and request Blob responses for downloads.
No browser contract contains an internal service token. Payment retries preserve
the original request and key; they never generate retry keys inside the client.

Broadcast capabilities are school-scoped. LIVE queueing requires an explicit
`{mode: "LIVE", previewFingerprint}` request after the current audience is reviewed;
dry runs send `{}`. Approval and dispatch modes remain attached to each saved
broadcast, so a configuration change cannot convert a dry run into live delivery.
Provider acceptance is not delivery: only confirmed `DELIVERED` rows count as
such. `SUBMITTING`, `UNKNOWN`, and conflicting reports require outcome refresh and
reconciliation using the same broadcast ID; the browser never resends them.

The generator intentionally supports a bounded OpenAPI subset. New media formats
or parameter locations must gain implementation and regression coverage before
being added to a contract. No third-party code generation dependency is required.

Workflow decisions send the version from the reviewed instance, including zero. A409 requires reloading and reviewing current state before another deliberate decision; generated clients never silently retry or refresh. The browser source currently has no generic workflow decision or B2B billing-payment callers: firefighting approvals use separate domain handlers and fee collections use their own stable-key client. The billing payment request schema documents POST /api/v1/billing-payments, explicitly classified compatibility and excluded from canonical client operations. It requires a stable idempotencyKey, invoiceId, integer amount and paymentMode; actor/branch are server-owned. There is no invented /billing/payments endpoint.

Student lifecycle contracts preserve the actual review pagination envelope (`content`,
`totalElements`, `page`, `size`), nullable timestamps/names/photos, and both empty and
active campaign status. PostgreSQL-backed serialization checks cover preview rows,
structure analysis, skipped rows, completed-token replay, import progress, promotion
items/batches, review items/pages/status, and verification summaries. Controller tests
cover school/permission denials and the server-owned review item scope. Admission
confirmation stays in the required `X-Student-Delete-Confirmation` header, encoded
once by the client and decoded once by the controller. The generator permits that
header only on the canonical student DELETE; authentication, tenant identity, cookie
and peer headers cannot be declared in browser contracts.

Import multipart fields support explicit strings and binary files; the browser sets
the boundary. The existing `/students/import/upload-preview` route is canonical in
`StudentReadController` despite its singular spelling; confirm/status use their
plural canonical paths. The form sends the parsed `rowsJson` string alongside its
source file, with school scope in query parameters. The UI and server use the same
10 MB file limit. Job failure stops polling without resubmitting confirmation.
Generated types and these test assertions do not introduce a production response
validator or replace server-side validation/RLS. The full controller inventory still
has broader payload work beyond these 83 operations.

The photo-import client opts into bounded request options: signal, timeout and onDownloadProgress. Generation rejects other options; runtime calls select only these fields so caller config cannot override paths, authentication headers, query scope or Blob handling. The panel and school-folder retry share the authenticated Axios transport. See [the photo-import findings and fix plan](../../docs/current-state/photo-import-contracts-dev-2026-10-12.md).

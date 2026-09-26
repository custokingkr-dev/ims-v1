# API Contracts and Client Migration

Last reconciled: 2026-09-26 against source controllers, the generated route inventory,
Java DTOs, and browser contracts.

The gateway route inventory records controller ownership and compatibility use.
OpenAPI adds payload schemas for **42 canonical operations across 10 contract files/clients**. The browser migration now has **0 compatibility call sites**, down
from 95. Static source coverage is not production traffic telemetry, and retained
aliases can still have external consumers.

## Contract coverage

| Contract | Operations | Workflow |
| --- | ---: | --- |
| Identity | 6 | Login, refresh, logout, password-reset capabilities/request/confirmation |
| Fees | 6 | Stable-key collection, JSON/PDF receipt, collection/overdue reports, reminders |
| Students | 5 | Create/update, ID-based roster, multipart photo upload, authenticated photo read |
| Attendance | 3 | Read/save register and submit section |
| Billing | 1 | Complete invoice statistics and reporting period |
| Broadcasts | 8 | Draft/list, capabilities, policy preview, fingerprint approval, explicit live confirmation, dry-run/live outcomes |
| Quotation documents | 4 | Capabilities, private upload/download/removal |
| Catalog | 6 | Paginated orders, scoped statistics, guarded status/delivery, reviewed annual-plan confirmation |
| Reporting | 1 | Workspace dashboard envelope |
| Firefighting creation | 2 | Stable-key request and quotation creation |

Specifications live in `contracts/openapi/*.openapi.json`; generated TypeScript
clients live in `frontend/src/generated`. These are critical workflow contracts,
not complete schema coverage of every controller. Extensible map responses are
marked explicitly. Selected Java records are bound to schemas for field/type drift
checks; behavioral tests cover repository-backed maps and permission boundaries.

## Generation and verification

From the API gateway directory:

```powershell
npm run contracts:generate
npm run contracts:check
```

From the repository root:

```powershell
node scripts/generate-api-route-inventory.js
node scripts/generate-openapi-typescript-client.js
node scripts/generate-openapi-typescript-client.js --check
node --test scripts/tests/generate-openapi-typescript-client.test.js services/api-gateway/api-contract.test.js
```

The dependency-free generator validates canonical service/controller ownership,
public gateway routing, local schema references, path parameters, response types,
Java record bindings and generated output freshness. It rejects compatibility and
internal operations, unsupported media and ambiguous success representations.
Per-operation controller ownership supports domains spanning multiple controllers.

Clients share the configured authenticated Axios transport. They return response
bodies, encode path segments, pass query filters through Axios parameters, build
multipart FormData without overriding its boundary, and request Blob downloads.
Internal service tokens are absent from browser contracts. Existing refresh,
credential and authentication behavior remains in the shared transport.

The always-run CI architecture job verifies inventory/client freshness and runs
contract generator/routing tests independently of the affected-service matrix.
Frontend tests exercise the real generated clients with mocked transport, including
lost-response replay identity, scoped filters, page envelopes, multipart/binary
operations and reviewed broadcast/annual-plan fingerprints.

## Compatibility and upgrade boundary

All 95 identified browser compatibility calls have canonical replacements with
reviewed request/response behavior. Pagination and Operations scope are preserved;
status authorization is strengthened to match the old superadmin-only route.
See `docs/product/canonical-api-migration-2026-09-26.md` for exact mappings.

Fee collection and firefighting request/quotation creation require a stable
idempotency key, including on compatibility routes. Retain the original key and
payload after an uncertain result; payload mismatch returns 409. Annual-plan
confirmation requires the reviewed fingerprint and returns a saved immutable
revision with notificationStatus=NOT_SENT. Older clients need these request
updates; aliases do not bypass integrity checks.

Broadcast capabilities are school-scoped. LIVE queueing requires an explicit
`{mode: "LIVE", previewFingerprint}` request after the current audience is reviewed;
dry runs send `{}`. Approval and dispatch modes remain attached to each saved
broadcast, so a configuration change cannot convert a dry run into live delivery.
Provider acceptance is not delivery: only confirmed `DELIVERED` rows count as
such. `SUBMITTING`, `UNKNOWN`, and conflicting reports require outcome refresh and
reconciliation using the same broadcast ID; the browser never resends them.

No alias deletion or sunset date is introduced. Route removal still needs external
consumer evidence and separate review. Broader payload coverage can expand one
reviewed workflow at a time without weakening authorization or inventing equivalent
response shapes.

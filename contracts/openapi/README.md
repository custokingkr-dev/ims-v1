# Browser API contracts

Run `node scripts/generate-openapi-typescript-client.js` after a contract change,
and `node scripts/generate-openapi-typescript-client.js --check` in verification.
The route inventory must first be current (`node scripts/generate-api-route-inventory.js`).
The always-run CI architecture job checks generation and the generator/routing tests.

Current coverage is **42 canonical operations across 10 contract files/clients**:

| Contract | Operations | Covered workflow |
| --- | ---: | --- |
| Identity | 6 | Login, refresh, logout, password-reset capabilities/request/confirmation |
| Fees | 6 | Idempotent payment, receipt JSON/PDF, collection/overdue reports, reminders |
| Students | 5 | Admission, profile update, ID-based roster, multipart photo upload, authenticated photo download |
| Attendance | 3 | Read/save register, submit section |
| Billing | 1 | Complete invoice statistics and reporting period |
| Broadcasts | 8 | Draft/list, capabilities, preview, fingerprint approval, dry-run queue/outcomes/retry |
| Quotation documents | 4 | Capabilities, multipart upload, authenticated download, removal |
| Catalog | 6 | Paginated order read, scoped statistics, guarded status/delivery, annual-plan review/confirmation |
| Reporting | 1 | Workspace dashboard envelope |
| Firefighting creation | 2 | Stable-key request and quotation creation |

These are critical browser contracts, not a claim of complete coverage of every
controller. Legacy report row extensions and receipt detail fields remain explicit
extensible objects; payment results, upload metadata, attendance registers, billing
totals and broadcast policy/outcome envelopes have named fields. Runtime validation
still belongs to the server and existing frontend response parsers.

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

The generator intentionally supports a bounded OpenAPI subset. New media formats
or parameter locations must gain implementation and regression coverage before
being added to a contract. No third-party code generation dependency is required.

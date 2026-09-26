# Canonical browser API migration

The source inventory initially identified **95 compatibility call sites**. The
completed migration identifies **0 remaining browser compatibility call sites**,
a **95-call reduction**. Counts are static references, not request-volume telemetry;
the generated gateway inventory remains the source of truth. Compatibility
controllers stay available to older clients.

## Completed mappings

| Old surface | Canonical surface | Equivalence and adaptation |
| --- | --- | --- |
| `/workspace/fees/record-payment` | `/fees/payments` | Same repository, stable key, trusted actor, assignment/year selectors; canonical DTO now preserves and validates school scope. |
| `/fee-structure*`, `/fee-assignments` | `/fees/structure*`, `/fees/bands*`, `/fees/items*`, `/fees/assignments` | Added missing canonical lifecycle delegates with existing permission/module guards. DTOs now preserve late-fee settings, optional fee heads, discount rule and academic year. Decimal item amounts retain fractional rupees. The item-name alias is explicitly adapted. |
| `/fees/report`, `/fees/overdue` | `/fees/reports/collection`, `/fees/reports/overdue` | Canonical `{content}` responses are explicitly unwrapped at the existing fee service boundary. No array/envelope assumption is hidden in a string replacement. |
| `/fees/send-reminders`, dashboard reminder mutation | `/fees/reminders/fee` | Same scoped reminder repository operation. |
| `/fees/receipts/{paymentId}/pdf` | `/fees/payments/{paymentId}/receipt/pdf` | Added canonical authenticated PDF delegate; same payment-ID lookup. |
| `/workspace/students` | `/students` | Same admission repository and school guard; canonical DTO now preserves the legacy free-text address. Actual response is the flat student detail. Photo upload stays a separate generated-client operation. |
| `/workspace/staff*` | `/schools/{schoolId}/staff*` | Same staff repository and tenant/ERP/permission checks; school moves from request body to explicit path. |
| School/zone account provisioning aliases | `/users/provisioning/...` | Same provisioning repository, superadmin/user-create guards and trusted assignedBy. |
| `/sa/invoices*` | `/billing/sa/invoices*` | List/stats/detail delegates equivalent; audited create/update callers send DTO-supported fields. |
| Catalog create/place/design/approval/reject/detail | `/catalog/orders...` | Existing caller payloads fit canonical DTO; caller-visible results share repositories. New orders are forced to DRAFT, preventing crafted status values from bypassing the approval lifecycle. |
| Annual-plan item/confirm aliases | `/catalog/annual-plan/...` | Same repository operations; optional canonical school query resolves from TenantScope like the old body default. |
| Dashboard reporting read aliases and command-centre feed/actions/brief | `/reporting/...` | Same filters, repositories and report-read guard. Brief maps to summary with default platform=false. |
| Vendor-paid aliases | `/catalog/orders/{id}/vendor-paid`, `/ff/requests/{code}/vendor-paid` | Same mutation; callers do not consume the old catalog `{order}` response wrapper. |
| Student review update/verification aliases | `/students/reviews/items/...` | Explicit PUT-to-POST mapping to existing repository operations and student-update checks. |
| `/workspace/firefighting` | `/ff/requests` | Canonical create preserves the current form fields; quotations are already created separately. |

Root gateway changes add the previously missing canonical catalog, billing and
reporting namespace routes. Browser fixture paths are updated with their callers.
Compatibility controllers remain available for external clients; no sunset date
or automatic deletion is introduced.

## Final mappings completed after preserving behavior

| Old surface | Canonical surface | Preserved behavior and guards |
| --- | --- | --- |
| `GET /workspace` | `GET /reporting/workspace` | Both delegate to one workspace assembler with identical summary fields and defaults, report permission and school scope. `/reporting/summary` remains a different response. |
| `GET /supply/orders`, `GET /sa/orders` | `GET /catalog/orders/page` | Existing repository page envelope, stable ID tie-break, status filter, page/size clamps and total counts. Operations reads retain assigned-school RLS aggregation. |
| Supply/SA order statistics | `GET /catalog/orders/summary` | Preserves Operations platform read scope and order/plan read permission. Existing narrower `/catalog/orders/stats` remains unchanged. |
| Class/section student lists | `GET /students/roster` | Explicit classId/sectionId query parameters, resolved school, StudentRow array and existing limit semantics. The grid endpoint stays separate. |
| Catalog delivery aliases | `POST /catalog/orders/{id}/deliver` | Operations or superadmin, fulfill/update permission, assigned-school guard and authenticated actor. |
| SA order status writes | `PATCH /catalog/orders/{id}/status` | Canonical guard now requires superadmin as the old route did. Having school-level order:update alone is insufficient. |
| Student profile update | `PUT /students/{id}` | Same full-profile required-field and placement validation, resolved school, student:update permission and authenticated audit actor. Private photo object keys remain unwritable through profile updates. |
| Annual-plan confirmation stub | `GET /catalog/annual-plan/review`, `POST /catalog/annual-plan/confirm` | Persist the exact current-year reviewed fingerprint and immutable revision, with concurrent duplicate replay. The response explicitly says notificationStatus=NOT_SENT. The legacy confirmation route uses the same durable operation and requires a reviewed fingerprint. |

## Client upgrade requirements

Fee payment creation and firefighting request/quotation creation require a stable
idempotency key on canonical and retained compatibility paths. A retry must reuse
the original key and exact payload. A changed payload with an existing key returns
409. Reload older browser bundles before collecting or creating records; external
clients must upgrade their requests. Annual-plan confirmation now requires the
fingerprint returned by review. A missing or stale fingerprint returns 409, without
claiming confirmation or notification.

No route was deleted. Structured catalog forms, assets and quotation routes under
`/supply/orders/...` remain canonical controller routes despite their older-looking
namespace; they were not blindly rewritten.

## Validation

Generator/routing tests cover inventory ownership, public gateway routing, Java
record drift, forbidden aliases/internal operations, encoded path segments,
multipart boundaries and Blob/204 behavior. Frontend tests exercise real generated
clients with mocked transport, payment recovery, the report adapter, separate
admission/photo recovery, attendance save/submit, full statistics and reviewed
broadcast approval. New school-core controller tests verify lifecycle field
preservation, monetary decimals, tenant scope, permissions and trusted actors.

PostgreSQL regression coverage verifies stable catalog pages, status filters,
counts, size limits, assigned Operations scope, student audit actors and immutable
photo keys. Controller tests verify the paired canonical/legacy annual-plan guards
and equivalent workspace envelope. The gateway contract test now rejects a
nonzero browser compatibility count.

Verification on 2026-09-26:

- Generator/gateway contract tests: 15 passed; generated clients current.
- Final frontend migration batch: 40 passed across six files; TypeScript check passed.
- Reporting controller regression batch: 24 passed, zero skipped.
- Full school-core execution: 843 unique tests, 842 passed and one new pagination
  fixture error (seed omitted required monetary fields). After correcting that
  fixture, the final six-class batch passed all 45 tests with zero skips. This
  batch also includes the last annual-plan migration grant, fee migration RLS
  regression, canonical permissions, student updates and photo-key preservation.
  It is a focused correction verification, not a second green full-suite run.

Logs: `artifacts/product-followup-2026-09-26/schoolcore-final-all.log`,
`schoolcore-final-targeted.log`, and `platform-canonical.log` in the same directory.

No cloud data was changed. This migration does not claim production traffic
coverage, complete payload schemas for all 435 controller mappings, or zero
external consumers of retained aliases.

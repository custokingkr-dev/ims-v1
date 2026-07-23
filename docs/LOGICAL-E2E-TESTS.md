# Logical E2E Test Suite

`scripts/test-application-logical-e2e.ps1` is the local, write-path logical E2E runner for the split-service IMS application. It creates a disposable school, provisions real users through the public gateway, exercises the main domain flows, and verifies downstream records where the application has no public read endpoint.

## Run

Use the full local stack because the suite covers school-core, identity, operations, platform, billing, frontend-facing gateway routes, and DB outbox rows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\test-application-logical-e2e.ps1 -StartStack
```

If the full stack is already running:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\test-application-logical-e2e.ps1
```

Optional baseline smokes can be chained before the logical flow:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\test-application-logical-e2e.ps1 -IncludeRouteSmoke -IncludeFeatureSmoke
```

The runner writes a structured result file to:

```text
artifacts/logical-e2e-result.json
```

## Safety

The suite writes data and mutates state. By default it only runs against `localhost`, `127.0.0.1`, or `::1`.

`-AllowRemoteWrites` exists for a deliberately prepared non-production environment, but DB-level assertions such as outbox checks and import-file evidence are only complete when the local Docker Postgres container is available.

## Stack Setup

When `-StartStack` is passed, the runner:

1. Starts `docker compose --profile full up -d --build`.
2. Applies local `app_rt` grants for all service schemas using `scripts/ensure-app-rt-local.ps1`.
3. Recreates `api-gateway` with `docker-compose.bola.yml` unless `-SkipEnforceGateway` is passed. This puts the gateway in `GATEWAY_AUTH_MODE=enforce`, so JWT introspection and tenant headers are actually tested.
4. Seeds local bootstrap users with `scripts/ensure-local-dev-users.ps1` unless `-SkipBaselineSeed` is passed.

The bootstrap account used by the runner is:

| Role | Email | Password |
| --- | --- | --- |
| SUPERADMIN | `local-superadmin@custoking.local` | `password` |

## Covered Flows

The suite covers these application flows end to end through `/api/v1/...` gateway routes:

| Area | Coverage |
| --- | --- |
| Auth and RBAC | Superadmin login, anonymous protected-route rejection, school admin provisioning, operations-user provisioning, operator school assignment, admin/operator login, operator assignment claim readback |
| School onboarding | School creation, academic-year start month, financial-year start month, active module enablement, disabled-ERP gate check |
| Tenant isolation | Admin cross-school student-list request must return `403` under enforce gateway mode |
| Modules | Enables `STUDENTS`, `ATTENDANCE`, `FEES`, `INVOICES`, `PAYMENTS`, `REPORTS`, `ORDERS`, and `FIREFIGHTING`; verifies active module readback |
| ERP setup | Configured classes, generated sections, staff creation, bell schedule, period, subject, class schedule assignment, timetable entry, timetable readback |
| Students | Add student, read detail, update student, upload photo when `STUDENT_PHOTO_BUCKET` is configured, bulk-import preview, import confirmation, import status, original import object evidence when storage is configured, import DB metadata evidence, import structure analysis for over-configured section |
| Attendance | Section register read, save student statuses, submit/lock section, daily summary, student report, section summary report |
| Fees | Fee band, fee item, student fee assignment, rupee payment mapped to paise, receipt readback, fee report, fee/payment outbox rows |
| Supply OS | Supply order create/detail/place/superadmin approve/operator deliver/vendor paid, annual plan item, annual plan confirm |
| Urgent Procurement | Create request, quotation, submit, bursar approve, principal approve, Custoking approve, fulfill, vendor paid, timeline readback |
| Workflow | Reads workflow definitions; if definitions are seeded, creates a workflow instance and records submit/approve actions |
| Reporting and dashboard | Workspace summary, dashboard summary, command center, feed, fee defaulters, vendor dues |
| Notification | Broadcast create and delivery-status readback |
| Billing | Superadmin invoice create/update/by-order readback |
| Student lifecycle | Promotion batch, hold one item, apply promotion, delete promoted student, verify preserved history with enrollments, promotions, fee assignments, fee payments, and history years |

## DB Assertions

When the local Postgres container is available, the runner verifies these tables directly:

| Schema | Assertion |
| --- | --- |
| `tenant_school.outbox_events` | `school.upserted.v1`, `student.upserted.v1`, `attendance-daily.upserted.v1`, `fee-assignment.upserted.v1`, `payment.recorded.v1`, `catalog-order.upserted.v1` |
| `firefighting.outbox_events` | `firefighting-request.upserted.v1` |
| `billing.outbox_events` | `billing.invoice-upserted.v1` |
| `student.import_batches` | Original import filename, SHA-256, and file size are persisted |
| `tenant_school.academic_years` | A target academic year is inserted for the promotion lifecycle test |

## Known Gaps

This suite does not prove real Cloud Pub/Sub delivery locally. Local compose uses the local publisher behavior; the runner verifies outbox writes. Dev/prod Pub/Sub push, projector delivery, and subscriber auth remain covered by deployment/readiness smokes and Cloud Run evidence.

It does not do browser pixel checks or Playwright UI navigation. It is a logical API/data-flow suite.

It does not validate real SMS/WhatsApp/email provider delivery. Notification broadcast creation and delivery-status storage are tested, but external provider integration needs environment-specific smoke with provider credentials.

Default local compose leaves `STUDENT_PHOTO_BUCKET` empty, so direct student photo upload and original import object-storage proof are skipped locally. The runner still verifies import filename/hash/size metadata in `student.import_batches`. In an environment with a configured private photo bucket, the same runner requires the photo upload to return `200`, verifies a stored `photoUrl`, and requires the bulk import preview to report `originalFileStored=true`.

Class structure now uses a 15-class ordered catalog: Nursery/Pre-Nursery/Playgroup, LKG, UKG, then classes 1 to 12. The runner verifies normal numeric-class import and pre-primary alias handling through backend tests; local API flow coverage uses the disposable school's configured structure.

The workflow flow is skipped if no active workflow definitions are seeded. The skip is reported in `artifacts/logical-e2e-result.json`.

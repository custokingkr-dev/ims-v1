# Microservices Migration Roadmap

## Goal

Move IMS from a Spring Boot modular monolith toward independently deployable services without doing a risky rewrite. The first milestone is a stricter modular monolith: business domains are isolated in code, events define cross-domain communication, and database ownership is explicit before any service is physically extracted.

## Target Services

| Service | Owns | Notes |
| --- | --- | --- |
| `identity-service` | Users, auth sessions, roles, permissions, JWT/session policy | Extract first because it is a clean security boundary. |
| `tenant-school-service` | Schools, zones, branches, classes, sections, tenant access rules | Source of truth for tenant/school hierarchy. |
| `student-service` | Student profile, admission number, import lifecycle, student status | Publishes student lifecycle events. |
| `attendance-service` | Daily attendance writes, attendance summaries | High-write domain; good independent scaling candidate. |
| `fee-service` | Fee bands, fee items, fee assignments, payments, receipts, reminders | Owns fee/payment consistency and audit behavior. |
| `catalog-service` / `supply-service` | Catalog, annual plans, orders, returns, fulfilment state | Deployed as `catalog-service`; backend compatibility facades delegate order, annual-plan, superadmin-order, vendor-paid, and catalog-metrics paths to it. |
| `workflow-service` | Approval definitions, workflow instances, workflow actions | Deployed with command/read ownership for workflow instances, transitions, pending work, and action history. |
| `firefighting-service` | Firefighting requests, quotations, fulfilment | Deployed with command/read ownership for requests, quotations, approvals, fulfillment, and vendor-paid marking. |
| `billing-service` | Superadmin invoices, billing invoice commands, invoice sequence allocation | First cut owns superadmin invoice create/update/read compatibility over existing tables. |
| `notification-service` | Email/SMS/WhatsApp/reminder dispatch | Async-only service fed by events. |
| `audit-service` | Immutable audit-event ingestion and audit sink persistence | Backend publishes audit events to audit-service; compatibility reads are served by audit-service. |
| `reporting-service` | Dashboards, read models, exports | First cut is deployed as read compatibility. Later consumes events and owns denormalized reporting tables. |
| `api-gateway` / BFF | Routing, auth edge checks, rate limits, request correlation | Keeps the frontend independent of service count. |

## Data Ownership Rules

- A service owns its tables and migrations.
- Other services cannot directly query those tables.
- Cross-service read needs should use API calls or reporting read models.
- Cross-service side effects should use events, not transactional joins.
- During transition, one PostgreSQL instance with schema-per-service is acceptable.
- Long term, high-risk/high-scale services should move to separate databases.

## Initial Database Ownership

| Current Tables | Future Owner |
| --- | --- |
| `app_users`, `auth_sessions`, RBAC tables | `identity-service` |
| `schools`, `school_sections`, zone mapping tables | `tenant-school-service` |
| `students` | `student-service` |
| attendance tables | `attendance-service` |
| `fee_bands`, `fee_items`, `fee_assignments`, `payment_records` | `fee-service` |
| catalog/order/annual-plan tables | `catalog-service` / `supply-service` |
| `workflow_definitions`, `workflow_instances`, `workflow_actions` | `workflow-service` |
| `firefighting_requests` | `firefighting-service` |
| command-center feed/action, academic event read-model tables | `reporting-service` |
| `superadmin_invoices`, `superadmin_order_seq` | `billing-service` |
| `audit_log` | compatibility read table during transition |
| `audit.audit_events` | `audit-service` |

## Event Contracts

Start with internal Spring events and persist them through an outbox before publishing externally.

Core events:

- `UserLoggedIn`
- `SchoolCreated`
- `StudentCreated`
- `StudentImported`
- `AttendanceSubmitted`
- `FeePlanAssigned`
- `PaymentRecorded`
- `FeeReminderRequested`
- `SupplyOrderCreated`
- `SupplyOrderStatusChanged`
- `WorkflowApprovalRequested`
- `WorkflowApprovalCompleted`
- `FirefightingRequestSubmitted`
- `NotificationRequested`

Event rules:

- Events are immutable records.
- Event names are business facts, not commands.
- Events carry IDs and small snapshots only.
- Consumers must be idempotent.
- External publication must use an outbox/inbox pattern.

## Extraction Order

1. **Modular monolith hardening**
   - Keep Spring Modulith discovery and architecture guardrails.
   - Move new code into domain-first packages.
   - Prevent candidate domain packages from depending on legacy `service` package or each other directly.

2. **Identity service**
   - Extract users, auth sessions, roles, permissions.
   - Gateway validates tokens or delegates token introspection.
   - Other services trust signed JWT claims and tenant context.

3. **Notification service**
   - Consume `NotificationRequested` events.
   - Remove reminder/email/SMS side effects from synchronous business flows.

4. **Fee service**
   - Own fee/payment tables and receipt generation.
   - Publish `PaymentRecorded` and `FeePlanAssigned`.
   - Reporting consumes fee events rather than joining fee tables.

5. **Attendance service**
   - Own attendance writes and summaries.
   - Publish `AttendanceSubmitted`.

6. **Reporting service**
   - Build dashboards from event-fed read models.
   - Avoid cross-service joins in request paths.

7. **Catalog/supply, workflow, firefighting, billing services**
   - Catalog/supply, workflow, firefighting, and billing compatibility surfaces are now physically extracted.
   - Fee, attendance, student direct create/photo, workflow, firefighting, billing, and catalog order/annual-plan local commands now delegate to their extracted services behind backend compatibility facades.
   - Complete event/audit parity, service-level authorization hardening, and Cloud Run private routing before enabling these command delegations in production.

## Platform Requirements Before Physical Extraction

- API gateway or backend-for-frontend for route ownership.
- OpenTelemetry traces across HTTP and async message handling.
- Correlation IDs propagated through gateway, services, logs, and events.
- Per-service health/readiness checks.
- Per-service CI jobs and Docker images.
- Contract tests for service APIs and event payloads.
- Cloud SQL migration ownership per service/schema.
- Pub/Sub topics with dead-letter queues for external events.
- Secrets in Secret Manager, not environment defaults.

## Current Physical Split Status

The repository now contains physically separate Spring Boot services for `notification-service`, `audit-service`, `identity-service`, `tenant-school-service`, `student-service`, `attendance-service`, `fee-service`, `catalog-service`, `workflow-service`, `firefighting-service`, `reporting-service`, and `billing-service`, plus a local/Cloud Run `api-gateway`.

Local compose runs the split topology behind the gateway. Frontend-compatible `/api/v1/**` traffic goes through the backend compatibility layer, while explicit service-prefix smoke routes such as `/student-api/v1/**`, `/fee-api/v1/**`, `/catalog-api/v1/**`, `/reporting-api/v1/**`, and `/billing-api/v1/**` hit the physical service containers directly with gateway-injected local service tokens.

The backend no longer has active request-path repository-backed fallbacks for migrated identity, tenant-school, student, attendance, fee, catalog, workflow, firefighting, reporting, billing, notification, and audit routes. Enabled migrated paths fail explicitly when their owning service is unavailable. Backend-local repository access is now constrained to explicit infrastructure/dev compatibility areas such as dev bootstrap, outbox, and health. An architecture guardrail locks that runtime boundary.

The latest local stability pass verified:

- `scripts/verify-microservice-migration.ps1` is the repeatable migration gate. It runs backend runtime, microservice runtime, deployment, service-authorization, optional database-boundary, optional Docker-build, and optional full feature-smoke checks from one command;
- gateway/frontend edge containers are health-checked, and gateway waits for a healthy frontend in compose;
- frontend Cloud Run builds use `VITE_API_BASE_URL=/api/v1`, avoiding double `/api/v1` prefixes for RBAC calls;
- gateway service-token defaults are non-secret placeholders, while local compose injects explicit dev tokens;
- backend readiness includes enabled extracted services;
- superadmin unscoped student, class, section, and roster reads are service-backed and no longer generate null-school service calls;
- billing invoice tables are now Flyway-managed in the `billing` schema, with first-run backfill from legacy `public.superadmin_invoices` and `public.superadmin_order_seq`;
- billing invoice statistics are owned by `billing-service` through `GET /api/v1/billing/sa/invoices/stats`;
- workflow definition, step, instance, and action tables are now Flyway-managed in the `workflow` schema, with first-run backfill from legacy `public` workflow tables and service-owned default definition seeding;
- firefighting request and quotation tables are now Flyway-managed in the `firefighting` schema, with first-run backfill from legacy `public.firefighting_requests` and `public.ff_quotations`;
- attendance daily and student-record tables are now Flyway-managed in the `attendance` schema, with first-run backfill from legacy `public.attendance_daily` and `public.attendance_student_records`;
- reporting read models now query `billing`, `firefighting`, and `attendance` schemas for domains that have moved out of `public`, so dashboards and vendor-dues/attendance/invoice summaries see service-owned writes;
- reporting command-center action/feed tables and academic event/contribution read-model tables are now Flyway-managed in the `reporting` schema, with first-run backfill from legacy `public` tables;
- notification broadcast, delivery-log, and dashboard notification-log tables are now Flyway-managed in the `notification` schema, with command writes served by `notification-service` and reporting reads pointed at notification-owned tables;
- catalog item, supply-order, annual-plan, and catalog-order tables are now Flyway-managed in the `catalog` schema, with first-run backfill from legacy `public` catalog tables and reporting/tenant-school read paths pointed at catalog-owned order data;
- fee band, fee item, fee assignment, and payment-record tables are now Flyway-managed in the `fee` schema, with first-run backfill from legacy `public` fee tables and reporting fee read models pointed at fee-owned assignment/payment data;
- identity user, auth-session, RBAC, assignment, and RBAC audit tables are now Flyway-managed in the `identity` schema, with first-run backfill from legacy `public` identity/RBAC tables and identity-service JPA/JDBC reads and writes pointed at identity-owned data;
- student profile, import batch/row, and student-review campaign/item tables are now Flyway-managed in the `student` schema, with first-run backfill from legacy `public` student tables and fee/reporting/attendance read paths pointed at student-owned profile data;
- tenant/school hierarchy, staff, zone, zone-school mapping, zone-admin assignment, academic-year, class/section, and school-module entitlement tables are now Flyway-managed in the `tenant_school` schema, with first-run backfill from legacy `public` metadata tables and tenant-school-service JPA/JDBC reads and writes pointed at tenant-school-owned data;
- tenant-school writes currently maintain compatibility shadows in the legacy `public` metadata tables so service schemas that still enforce school/class/section/year foreign keys continue to resolve during the split-database transition;
- extracted service Java paths no longer read or write legacy `public` tenant metadata directly; student, attendance, fee, catalog, firefighting, reporting, identity, and tenant-school service metadata lookups now use `tenant_school` while public metadata remains only as a compatibility FK/shadow layer;
- service-owned schemas no longer enforce foreign keys to legacy `public` tables. Student, attendance, catalog, fee, workflow, firefighting, notification, and reporting Flyway migrations drop cross-boundary `public.*` FKs, leaving service-local constraints and the intentional fee-to-student schema relationship while references to users/schools/students are validated at service/API or read-model boundaries;
- extracted service schemas no longer enforce cross-schema foreign keys to each other. Fee-to-student, student-review-to-identity, and tenant-school zone-admin-to-identity constraints are dropped by Flyway migrations so each service schema can be moved to a separate physical database without DDL-level coupling;
- backend production JPA scanning is now constrained to backend-owned outbox persistence, while dev/test profiles retain legacy compatibility repositories for local seed/demo flows; `scripts/audit-backend-runtime-boundaries.ps1` guards against reintroducing implicit all-package repository scanning or request-time Spring bean imports of legacy repositories;
- backend command-center JPA entities/repositories for actions, feed, notification broadcasts, and delivery logs have been removed after reporting-service and notification-service took ownership of those persistence paths; the backend runtime boundary audit fails if those retired files reappear;
- backend audit persistence has been reduced to a plain outbound event payload; `AuditLogRepository` is removed and backend JPA compatibility scanning no longer includes audit persistence now that audit-service owns `audit.audit_events`;
- backend security no longer carries the entity-backed `AppUserDetails`; request filters and authorization use neutral principals populated from identity-service introspection through `ServiceUserDetails`;
- `scripts/audit-microservice-runtime-boundaries.ps1` now fails if extracted service Java code directly references the legacy `public` schema at runtime; historical backfill SQL can live in explicit migration/ops scripts instead of live controllers;
- `scripts/audit-microservice-db-boundaries.ps1` now provides a repeatable local/CI database guardrail that fails if extracted service schemas regain cross-schema or `public` foreign keys, and GitHub Actions runs it after applying backend plus extracted-service Flyway migrations;
- the API gateway now fails fast if any internal service-route token is missing or left at the placeholder value, and Cloud Build injects those gateway tokens from Secret Manager during Cloud Run deployment;
- `scripts/smoke-gateway-routes.ps1` now captures the service-prefix gateway read matrix as a repeatable smoke check instead of an ad hoc shell snippet, and the CI boundary job runs it after starting backend/frontend/gateway on the migrated service schemas;
- student detail reads no longer run a nested query inside the service row mapper, so direct and backend-compatible detail routes return the same service-owned payload;
- frontend-compatible gateway valid read matrix passed 38/38 routes with zero failures;
- scripted direct service-prefix gateway matrix passed 29/29 routes with zero failures;
- full frontend-compatible feature smoke passed 58/58 routes with zero failures through the local gateway.

Repeat the full local migration check with:

```powershell
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 -RunDbAudit -RunSmoke
```

Run the image compile gate as needed with:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 -RunBuilds
```

Run the read-only deployment readiness smoke after staging or production promotion with pre-issued tokens:

```powershell
$env:IMS_SMOKE_SUPERADMIN_TOKEN = "<superadmin-access-token>"
$env:IMS_SMOKE_ADMIN_TOKEN = "<school-admin-access-token>"
powershell -ExecutionPolicy Bypass -File scripts\smoke-deployment-readiness.ps1 -GatewayBaseUrl https://<gateway-url>
```

## Remaining Migration Work

Physical service separation is mostly complete for request-time behavior, but true microservice ownership is not complete until persistence and asynchronous integration are separated.

Remaining work, in principal-architect priority order:

1. Replace transitional public metadata shadows with event-fed read models or service-owned reference projections. Tenant-school is schema-owned, extracted service runtime code reads `tenant_school`, and extracted service schemas no longer enforce cross-schema FKs; legacy `public` metadata shadows remain only for backend compatibility and older/manual database consumers.
2. Retire backend compatibility controllers only after frontend/gateway routing and service authorization are production-proven. Until then, backend remains the BFF/compatibility facade for existing frontend contracts.
3. Replace remaining synchronous cross-service composition with event-fed read models where latency and ownership justify it, especially reporting dashboards, notification fanout/reconciliation, vendor-dues views, and school/student/fee aggregates.
4. Run production audit backfill from `public.audit_log` to `audit.audit_events`, validate retention, then decommission legacy audit storage.
5. Harden service-to-service authorization per route, not only per service token, and add contract tests for each service API/event payload before independent deployments are treated as stable.
6. Split CI into per-service build/test/deploy jobs with dependency-aware smoke gates. The current local validation uses Docker builds and gateway smokes; full test-suite stabilization remains a separate track.
7. Add OpenTelemetry trace propagation across gateway, backend, services, Pub/Sub/outbox, and provider adapters before high-traffic production rollout.

## Containerization Progress

The backend image remains a single modular-monolith artifact, which is intentional until persistence ownership and external contracts are finished. The Dockerfile now follows the service image pattern needed for later extraction: OCI image metadata, production profile defaults, runtime `JAVA_OPTS`, configurable `SERVER_PORT`, and configurable health path. Cloud Build passes backend service name and commit version as image build metadata, so future extracted services can reuse the same image conventions without baking environment-specific settings into the image.

## Trade-offs

Benefits:

- Independent deployability.
- Smaller blast radius.
- Clearer data ownership.
- Better scaling for fees, attendance, reporting, and notifications.
- Cleaner security ownership.

Costs:

- More infrastructure and CI/CD.
- Distributed tracing becomes mandatory.
- No distributed transactions across services.
- More explicit API/event versioning.
- Reporting requires duplicated read models.

## Current Guardrail

The backend architecture tests now treat `attendance`, `catalog`, `fees`, `firefighting`, `identity`, `rbac`, `schools`, `students`, `superadmin`, `workflow`, and `zone` as candidate domain packages. They cannot depend on the legacy `com.custoking.ims.service` package or import each other directly. This keeps new domain work extractable while the existing monolith is gradually reorganized.

The workspace composition layer is intentionally separate from those candidate domain packages because it aggregates several domains for the current frontend. Its controllers now depend on `WorkspaceOperations`, and guardrails prevent regression to the legacy workspace service path while the future reporting/BFF boundary is designed.

## Current Implementation Step

The fee flow now publishes `FeePlanAssignedEvent` and `PaymentRecordedEvent` after fee assignment and payment writes. Those events are persisted into the `outbox_events` table in the same transaction through `DomainEventOutboxRecorder`.

Fee event payload construction now lives behind `FeeEventPublisher` in the `fees` package. The fee module owns event contract mapping for assignments, payments, and reminder requests.

Payment recording now runs through `FeePaymentRecorder` in the `fees.application` package. The fee module owns payment entity creation, fee-assignment paid amount updates, persistence, and `PaymentRecordedEvent` publication.

Fee-plan assignment now runs through `FeePlanAssigner` in the `fees.application` package. The fee module owns assignment creation/update, net-payable calculation, student fee-status updates, persistence, and `FeePlanAssignedEvent` publication.

Fee-structure writes now run through `FeeStructureCatalog` in the `fees.application` package. The fee module owns fee-band and fee-item creation, updates, deletion, validation, and amount parsing.

Fee collection reads, overdue queries, workspace fee summaries, overdue counts, and reminder event selection now run through `FeeCollectionReader` in the `fees.application` package. The fee module owns fee collection query composition and reminder event publication.

Fee-structure reads, fee-structure row rendering, matching, and export PDFs now live behind `FeeStructureCatalog`. Payment list rendering and receipt PDFs now live behind `FeeDocumentService`. This keeps fee-owned query/document behavior out of the legacy service while the controllers retain their existing endpoints.

Fee assignment and payment command orchestration now routes through `FeeCommandService` in the `fees.application` package. The fee module owns student/band/assignment lookup, active academic-year resolution, tenant access checks for fee writes, and delegation to the fee assignment/payment command handlers.

Endpoint-compatible fee write parsing and response shaping now live in `FeeManagementFacade` inside `fees.application`. The fee module owns fee-band/item mutation requests, fee-plan assignment requests, payment requests, logging, and response row construction.

`FeeManagementFacade` now also owns the remaining controller-compatible fee read/report/document methods: fee-structure reads, band matching, fee-structure PDFs, payment listings, receipt PDFs, fee reports, overdue reports, reminder requests, and workspace fee summaries.

`FeeOperations` is now the fee module's controller-facing application port. Fee controllers and workspace composition depend on that port instead of the retired legacy `FeeService`, with an architecture guardrail preventing regression. This leaves new code depending on the extractable fee boundary rather than the monolith `service` package.

Supply order workflow decisions now live behind `CatalogOrderWorkflow` in the `catalog.application` package. The catalog module owns placement routing, design-approval progression, superadmin approval, and superadmin return behavior.

Catalog order read models now live behind `CatalogOrderReader` in the `catalog.application` package. The catalog module owns order list/detail/stats row shaping and pending-approval selection.

Annual-plan reads, writes, summary calculation, row shaping, and confirmation responses now live behind `CatalogAnnualPlanService` in the `catalog.application` package. The legacy supply adapter resolves tenant context and maps not-found errors to HTTP while the catalog module owns annual-plan persistence behavior.

Annual-plan per-student cost calculation now gets student counts through the neutral `AnnualPlanStudentCountProvider` common port, implemented by the students boundary. Catalog no longer reads `StudentRepository` directly for annual-plan summaries, preserving the current API shape while removing a supply-to-student table dependency before service extraction.

`CatalogAnnualPlanService` is now a thin annual-plan facade. Read aggregation lives in `CatalogAnnualPlanReader`, mutations and confirmation responses live in `CatalogAnnualPlanCommandService`, and endpoint-compatible row shaping is shared through `CatalogAnnualPlanMapper`. This keeps the public catalog application boundary stable while making the future supply-service annual-plan component easier to extract and test independently.

Annual-plan active-year lookup and request value parsing are now centralized in `CatalogAnnualPlanAcademicYearProvider` and `CatalogAnnualPlanValueParser`. This removes duplicate parsing and current-year resolution from the read/write services while keeping the behavior local to the future supply-service boundary.

`CatalogAnnualPlanService` is package-private and no longer exposes a nested public exception. Annual-plan not-found handling uses the package-local `CatalogAnnualPlanSchoolNotFoundException`, so `CatalogOperations` remains the catalog module's external application contract while annual-plan internals stay encapsulated.

Annual-plan item saves now map transport request maps into a package-local `CatalogAnnualPlanItemCommand` through `CatalogAnnualPlanItemCommandMapper`. `CatalogAnnualPlanCommandService` applies a typed command instead of parsing request keys inline, which separates API compatibility from the future supply-service write model.

Catalog order statistics now get annual-plan term budget through `CatalogAnnualPlanBudgetProvider` instead of reading annual-plan repositories directly inside `CatalogOrderReader`. This keeps annual-plan persistence ownership inside the annual-plan component while preserving the existing catalog stats response.

Catalog order response shaping and order-data JSON parsing now live in package-local `CatalogOrderMapper`. `CatalogOrderReader` focuses on repository query orchestration, ordering, pending-approval filtering, and stats aggregation while preserving the existing API map shape.

Annual-plan list reads now use package-local `CatalogAnnualPlanTermView` and `CatalogAnnualPlanSummaryCalculator`. Summary totals, pending counts, completion, and per-student cost are calculated from typed term views before response maps are rendered, separating read-model logic from API map shape.

Catalog category metadata and catalog order draft creation now live behind `CatalogOrderDraftService` in the `catalog.application` package. The catalog module owns order-data normalization, default amounts/statuses, category-specific fields, estimated delivery defaults, and initial approval flags.

Catalog order draft requests now map into package-local `CatalogOrderDraftCommand` objects through `CatalogOrderDraftCommandMapper`. `CatalogOrderDraftService` consumes typed values and focuses on school lookup, entity creation, workflow defaults, persistence, and event publication.

Superadmin catalog order create/status requests now map through `CatalogSuperadminOrderCommandMapper`. `CatalogSuperadminOrderService` keeps the `SuperadminOrderProvider` contract stable while delegating superadmin order list, stats, create, and status operations to `catalog-service`.

Catalog facade request parsing now lives in `CatalogManagementRequestMapper`. `CatalogManagementFacade` keeps the existing `CatalogOperations` and `WorkspaceCatalogProvider` contracts while focusing on tenant resolution, application orchestration, audit logging, and HTTP error mapping.

Catalog order lookup and workflow command orchestration now live behind `CatalogOrderCommandService` in the `catalog.application` package. The catalog module owns order-not-found and invalid-transition application errors.

`CatalogOperations` is now the catalog module's controller-facing application port. `SupplyController` depends on this port instead of the retired legacy `SupplyOrderService`, with an architecture guardrail preventing regression. `CatalogManagementFacade` implements the port inside `catalog.application`, owns tenant checks, audit logging, HTTP status mapping, and endpoint-compatible responses, and gives a future `supply-service` a single facade to extract without changing HTTP contracts.

Workspace composition now uses `CatalogOperations` for catalog categories, school order lists, annual-plan payloads, workspace order creation, and annual-plan saves. `WorkspaceService` no longer reaches into catalog repositories directly, and an architecture guardrail prevents `CatalogOrderRepository` or `AnnualPlanItemRepository` from returning to that legacy composition layer.

School-admin order count and GMV statistics now come from `CatalogSchoolMetricsService` in `catalog.application`. `SchoolService` no longer reads catalog orders directly when building school stats, and an architecture guardrail prevents direct catalog repository/entity usage from returning to that school-management layer.

School management is now behind `SchoolOperations` in `schools.application`. The school HTTP controller depends on that port, while shared superadmin stats use a narrower common provider. School reads, commands, and section provisioning are split internally. Catalog order metrics are consumed through the neutral `SchoolOrderMetricsProvider` common port, so the schools candidate domain does not depend directly on the catalog candidate domain.

The superadmin school stats endpoint now uses the neutral `SuperadminSchoolStatsProvider` common port instead of importing the schools application boundary directly. `SchoolService` remains the local implementation, keeping the current endpoint behavior while reducing superadmin-to-school coupling ahead of service extraction.

Superadmin catalog order listing, statistics, status updates, and order creation now live behind `SuperadminOrderProvider`, a neutral common port implemented by `CatalogSuperadminOrderService` in `catalog.application`. The superadmin boundary keeps invoice and sequence ownership without importing the catalog boundary directly.

The superadmin portal API now depends on `SuperadminOperations` in `superadmin.application` instead of the retired legacy `service.SuperadminService`. Superadmin invoice management, invoice statistics, order sequence allocation, and catalog-order delegation are isolated behind that application port, with guardrails preventing legacy-service and direct catalog dependencies from returning.

Superadmin order and invoice ID allocation now lives in package-local `SuperadminSequenceGenerator`. `SuperadminService` delegates sequencing instead of touching the sequence repository directly, keeping the superadmin facade focused on API orchestration and invoice lifecycle behavior while preserving the existing `CK-2025-*` and `INV-2025-*` ID formats.

Workspace catalog composition has been tightened further by removing stale local catalog category, annual-plan row, catalog-order row, and JSON parsing helpers. `WorkspaceService` now relies on `CatalogOperations` for catalog data shapes instead of retaining duplicate catalog read-model code, and the workspace guardrail blocks those helper/entity references from returning.

Workspace catalog composition now flows through the neutral `WorkspaceCatalogProvider` common port, implemented by `CatalogManagementFacade` in `catalog.application`. The workspace BFF no longer imports the catalog boundary directly for catalog categories, school order lists, annual-plan rows, workspace order creation, or annual-plan item saving, while the public supply/catalog APIs continue to depend on `CatalogOperations`.

Workspace composition itself now lives behind `WorkspaceOperations` in `workspace.application` instead of the legacy `service.WorkspaceService` package. Dashboard, approvals, customer, invoice, payment, user, and workspace controllers depend on that port, while the implementation remains a BFF/reporting-style composition layer that can call multiple domain application ports until those read models move into a dedicated reporting service.

Workspace dashboard statistics now use a `workspace.application` DTO instead of the legacy shared `model.DashboardStats` record. Candidate domain packages are guarded from importing the legacy `model` package, keeping extractable application boundaries free of old monolith DTOs and enums.

The unused legacy `model` records and unused `payments.domain.PaymentDomainService` have been removed. Payment records remain owned by the fee boundary, matching the target `fee-service` ownership of fees, payments, receipts, and reminders, while guardrails prevent the old shared model package from returning.

Workspace school metadata, active academic year, section counts, staff list/write behavior, and identity user-directory rows now flow through `WorkspaceSchoolProvider` and `WorkspaceUserDirectoryProvider` common ports. `WorkspaceService` no longer imports school/staff/user repositories or entities directly, keeping BFF composition dependent on application contracts rather than owned tables. Workspace school metadata, section counts, and staff lists now come from `tenant-school-service`; backend-local empty fallbacks have been removed from those provider reads, so tenant-school outages fail explicitly. Workspace user-directory rows now come from `identity-service`; backend-local `app_users` fallback has been removed from that provider, so identity-service outages fail explicitly. Staff creation now attaches the new staff member to the resolved tenant school so it is visible in the school-scoped staff list.

Student management is now behind `StudentOperations` in `students.application`. Photo and import controllers depend on that port instead of the retired legacy `service.StudentService`, while shared fee/workspace surfaces consume narrower common provider ports. Student reads, mutations, import orchestration, and shared row/tenant helpers are split into `StudentReader`, `StudentCommandService`, `StudentImportService`, and `StudentSupport`.

The fee-collection HTTP controller now uses the neutral `FeeCollectionRosterProvider` common port for class, section, and student roster reads instead of importing the students application boundary directly. `StudentService` remains the local provider while the monolith is intact, but the fee-collection surface is cleaner for a future fee-service/student-service split.

Workspace student composition now flows through the neutral `WorkspaceStudentProvider` common port, implemented by `StudentService` in `students.application`. The workspace BFF no longer imports the students boundary directly for dashboard student counts, first-student activity text, student rows, workspace student creation, or import preview/confirm/status/template behavior, while dedicated student APIs continue to use `StudentOperations`.

Student-facing fee profile and default fee-plan assignment behavior now flow through the neutral `StudentFeeProfileProvider` common port. `FeeStudentProfileService` implements that port from `fees.application`, so the student boundary does not directly import the fees boundary while still preserving class-section fee profile rows and automatic fee-plan assignment on student creation.

`StudentFeeProfileProvider` now exchanges a small `StudentSnapshot` record instead of exposing `StudentEntity`. This keeps the shared common port free of JPA/repository types, and an architecture guardrail prevents persistence types from leaking back into common application ports.

Workspace firefighting composition now flows through the neutral `WorkspaceFirefightingProvider` common port, implemented by `FirefightingWorkspaceService` in `firefighting.application`. The workspace BFF no longer imports the concrete firefighting implementation while preserving dashboard counts, request/order read models, pending approvals, workspace request creation, and simple approve/reject actions. Workspace firefighting read paths delegate to `firefighting-service` and now fail explicitly if that service is unavailable instead of returning empty dashboard data.

Workspace fee composition now flows through the neutral `WorkspaceFeeProvider` common port, implemented by `FeeManagementFacade` in `fees.application`. The workspace BFF no longer imports the fee application boundary directly for dashboard fee summaries, overdue counts, fee structures, payment recording, plan assignment, fee-item creation, or receipt PDFs, while public fee controllers continue to depend on `FeeOperations`.

The public firefighting API now depends on the `FirefightingOperations` application port. The workflow implementation moved from the legacy `service` package into `firefighting.application`, keeping existing endpoint behavior while making the controller boundary match the fee and catalog extraction pattern.

The firefighting application layer is split for extraction. The backend `FirefightingService` facade is now a service-only adapter over `firefighting-service` for public request reads, stats, detail, pending approvals, timeline composition, quotation changes, and state transitions. Local repository command/read fallback and empty-list service-outage fallback have been removed from that facade.

Firefighting request code generation is now centralized in `FirefightingRequestCodeGenerator`, so public API and workspace-originated request creation share the same `FF-###` allocation rule.

Attendance is now a tracked candidate domain package. The public HTTP attendance controller depends on the `AttendanceOperations` application port, while workspace attendance entry/summary paths use the neutral `WorkspaceAttendanceProvider` common port implemented by `AttendanceService` in `attendance.application`. The legacy `service.AttendanceService` class path is retired and guarded against regression. Internally the application layer now separates the public facade from `AttendanceReader`, `AttendanceCommandService`, and shared attendance support, reducing the amount of logic that must move together when attendance becomes a standalone service.

Workflow is now a tracked candidate domain package. The HTTP workflow controller depends on the `WorkflowOperations` application port, while workflow reads and state-changing actions are split between `WorkflowReader` and `WorkflowCommandService` under `workflow.application`. This keeps the current synchronous API behavior intact while isolating the approval engine for later extraction as a reusable workflow-service.

Zone management is now a tracked candidate domain package. The HTTP zone controller depends on the `ZoneOperations` application port, zone reads and commands live under `zone.application`, and zone-admin role assignment goes through the neutral `RoleAssignmentProvider` common port instead of coupling the zone boundary back to the RBAC implementation package.

RBAC behavior now lives behind `RbacOperations` in `rbac.application`, with the implementation moved out of the legacy service package while preserving the Spring Security bean name `rbacService` for existing SpEL permission checks. Auth session shaping, tenant-scope construction, and permission evaluation depend on the RBAC port rather than `service.RbacService`, while role-assignment side effects use neutral common ports where they cross boundaries.

Zone-admin role assignment and demo-user role seeding now use the neutral `RoleAssignmentProvider` common port. `RbacService` remains the local implementation, but zone and bootstrap no longer import the RBAC application boundary directly.

Spring Security permission evaluation now uses the neutral `IdentityAuthorizationProvider` common port. The `RbacPermissionEvaluator` extracts the authenticated user ID in the security adapter and delegates permission lookup through that common provider, so the security layer no longer imports `rbac.application` directly and RBAC no longer exposes Spring Security types in its application port.

User context and tenant-scope resolution now live under `identity.application`, while HTTP controllers consume the neutral `AuthenticatedActorProvider` common port for current-user and superadmin checks. The tenant resolver filter consumes the common `TenantScopeProvider`, and the legacy `com.custoking.ims.service` package is now guarded as retired production surface. Security adapters implement the common `AuthenticatedUserPrincipal` contract, so identity no longer imports `com.custoking.ims.security` and is now covered as a candidate domain package.

Controller actor resolution now goes through `AuthenticatedActorProvider` instead of importing the identity application boundary directly. `UserContextService` remains the local implementation while the monolith is intact, but the HTTP adapter layer is no longer tied to `identity.application.UserContextOperations` for authenticated actor lookup.

Authenticated actor exchange now uses the common `AuthenticatedActor` snapshot instead of the legacy `model.AuthUser` record. The shared provider ports no longer expose the old password-bearing actor DTO or the legacy `Role` enum, and an architecture guardrail prevents persistence or model imports from returning to `common.application` ports.

Login, refresh-token rotation, logout, session persistence, and auth response shaping now live behind `AuthOperations` in `identity.application`. The JWT and Spring Security user-details implementations remain adapters in the security package through `IdentityTokenService` and `AuthenticatedUserPrincipalLoader`, while identity consumes `IdentityAuthorizationProvider` from the common layer instead of importing the RBAC boundary directly.

Security adapter contracts for authenticated principals, JWT issuance/validation, and tenant-scope resolution now live in `common.application`. `AppUserDetails`, `JwtService`, and `TenantResolverFilter` no longer import identity application contracts, and `AuthService` builds a small `AuthenticatedUserSnapshot` instead of relying on an entity-backed security principal during login and refresh. This keeps identity as the implementation owner while making the Spring Security adapter replaceable during physical service extraction.

The request logging filter, JWT auth filter, tenant resolver, and permission evaluator now consume the neutral `AuthenticatedUserPrincipal` interface instead of casting back to concrete `AppUserDetails` and reading the backing JPA user. Request authentication now loads identity-service introspection principals in enabled mode, and tenant scope composes role assignments from `identity-service` with zone-school expansion from `tenant-school-service`. This removes another adapter-level dependency on persistence-backed security internals while keeping current Spring Security behavior unchanged.

Request logging and JWT authentication filters now depend on the common `IdentityTokenService` port rather than the concrete `JwtService` implementation. `JwtService` remains the local Spring Security adapter, but web/security filter wiring no longer couples request processing to that implementation class.

Request logging and JWT authentication filters now also depend on the common `AuthenticatedUserPrincipalLoader` port instead of the concrete `AppUserDetailsService`. `AppUserDetailsService` remains the local Spring Security adapter and implements that port, while request filters only know how to load a neutral authenticated principal.

`AppUserDetails` no longer exposes its backing `AppUserEntity` through a public accessor, and `JwtService` no longer has token-generation overloads tied to `AppUserDetails`. Token issuance and request identity lookup now stay on common principal contracts, with guardrails preventing the concrete security adapter from leaking back into token or web-filter APIs.

Controller security tests now use a local neutral `AuthenticatedUserPrincipal` test double instead of constructing production `AppUserDetails`. This keeps security coverage focused on role/permission behavior while avoiding test coupling to the concrete identity adapter.

Auth service tests now live with the identity application boundary instead of the retired legacy `service` test package. A guardrail locks that test placement so future identity changes do not revive the old service namespace.

Audit read access now lives behind `AuditLogOperations` in `audit.application`, so the HTTP audit endpoint no longer imports the audit repository or entity directly. Audit writes from identity, catalog, and firefighting now go through the neutral `AuditEventRecorder` common port, implemented by the local audit adapter today and replaceable by a shared audit sink during service extraction.

The outbox recorder now depends only on the common `DomainEvent` contract instead of importing fee-specific event classes. Each domain event owns its event key, aggregate metadata, and versioned event type, while the outbox remains reusable infrastructure for any future service boundary event.

Attendance day submission now publishes `AttendanceSubmittedEvent` with school/date/year aggregate metadata and attendance totals. The event flows through the same generic outbox recorder as fee events, giving the future attendance-service an async integration point without coupling outbox infrastructure back to the attendance package.

Supply order creation and status transitions now publish `SupplyOrderCreatedEvent` and `SupplyOrderStatusChangedEvent` from the catalog boundary. The events use `SupplyOrder` aggregate metadata and the generic outbox path, preparing the current catalog module for a future `supply-service` without changing existing supply/order HTTP contracts.

Workflow submission and terminal approval decisions now publish `WorkflowApprovalRequestedEvent` and `WorkflowApprovalCompletedEvent` from the workflow boundary. Intermediate approvals still advance steps synchronously, while terminal approve/reject/cancel/complete decisions become outbox-backed facts for future workflow-service consumers.

Firefighting request submission now publishes `FirefightingRequestSubmittedEvent` from both public draft submission and workspace-originated submitted request creation. The event captures the request aggregate, school, category, urgency, budget, and actor so a future firefighting-service can own the approval intake stream without depending on workspace composition code.

Student admission and confirmed import flows now publish `StudentCreatedEvent` and `StudentImportedEvent` from the student boundary. Manual admissions emit a student aggregate event, while bulk imports emit one import-batch event with row counts so downstream reporting and notification consumers can stay idempotent without processing every imported row synchronously.

School creation now publishes `SchoolCreatedEvent` from the school boundary after the school row is saved and class/section provisioning is requested. The event captures the tenant aggregate identifiers and initial provisioning counts needed by future tenant-school, reporting, and onboarding consumers.

Successful password login now publishes `UserLoggedInEvent` from the identity boundary after session issuance. The event is keyed by a generated occurrence id, not only by user id, so repeated logins remain valid outbox records while still aggregating under the user.

Fee reminder flow now also publishes `NotificationRequestedEvent` through the common `NotificationRequestPublisher` port. The fee module still owns the `FeeReminderRequestedEvent` business fact, while notification delivery receives an idempotent request event without introducing a direct fee-to-notification package dependency.

Event contract tests now cover every concrete `DomainEvent`. They lock event type names, aggregate metadata, and serialized payload field names, and they fail when a new event is added without a contract entry. This gives the project a lightweight compatibility gate before separate services start consuming these events outside the monolith.

`OutboxPublisherService` can drain `PENDING` rows with row locking, retry failures with backoff, and move exhausted messages to `DEAD_LETTER`. It is disabled by default through `ims.outbox.publisher.enabled=false`.

External event publishing now has two providers behind `OutboxMessagePublisher`:

- `logging`, the default local/Tilt-safe provider.
- `pubsub`, a GCP Pub/Sub provider enabled with `IMS_OUTBOX_PUBLISHER_PROVIDER=pubsub`, `IMS_OUTBOX_PUBLISHER_ENABLED=true`, `IMS_OUTBOX_PUBLISHER_GCP_PROJECT_ID=<project>`, and `IMS_OUTBOX_PUBLISHER_GCP_TOPIC_ID=<topic>`.

The Pub/Sub provider publishes the persisted outbox JSON as message data and includes event metadata as Pub/Sub attributes. Keep consumers idempotent by using the `eventId` or `eventKey` attributes.

Pub/Sub topic routing is now explicit and backwards compatible. The default `single` mode keeps publishing every event to `IMS_OUTBOX_PUBLISHER_GCP_TOPIC_ID`, preserving the current deployment path. Setting `IMS_OUTBOX_PUBLISHER_GCP_TOPIC_ROUTING=domain` derives topics from the event type domain with `IMS_OUTBOX_PUBLISHER_GCP_TOPIC_PREFIX` and `IMS_OUTBOX_PUBLISHER_GCP_TOPIC_SUFFIX`; for example, `fees.payment-recorded.v1` routes to `ims-fees-events-v1`. This lets extracted services own topic subscriptions incrementally without changing event producers.

Outbox delivery is now visible through the existing operational plane. The `outbox` health contributor reports publisher state, pending/in-progress/processed/dead-letter counts, and oldest pending age. Prometheus gauges expose `ims.outbox.events{status=...}` and `ims.outbox.oldest.pending.age.seconds` for alerting before fee-service extraction.

Fee calculation and fee-status rules now live in `FeeDomainService` and are reused by fee assignment, payment recording, student bootstrap assignment, and demo-data bootstrap. This keeps net-payable, due amount, payment accumulation, amount parsing, and paid/overdue classification behind one fee-domain seam before the service is physically extracted.

Demo-data bootstrap now depends on the neutral `BootstrapFeeCalculator` common port instead of importing the fee-domain implementation directly. The fee domain still owns the calculation, while bootstrap remains a temporary local/demo adapter that can be deleted or replaced before physical service extraction.

Fee reminder requests now publish `FeeReminderRequestedEvent` per overdue assignment. This gives the future notification-service an idempotent event stream instead of a synchronous reminder side effect in the fee workflow.

`notification-service` is now the first physically extracted service under `services/notification-service`. It owns the `notification` PostgreSQL schema, runs its own Flyway history table, exposes a Pub/Sub push-compatible endpoint at `/api/v1/pubsub/notifications`, persists messages through an inbox table, and dispatches through a delivery provider port. The current provider is `logging`, with the service package split into API, application, infrastructure, and persistence layers so SMS/email/WhatsApp adapters can be added without changing the consumer contract.

Local compose wiring now exercises the real async boundary: the backend outbox can run with `IMS_OUTBOX_PUBLISHER_PROVIDER=http` and POST Pub/Sub-shaped messages to `notification-service`. Cloud Build now builds/deploys the notification service separately and configures a Pub/Sub push subscription to the Cloud Run endpoint, while backend production deployment uses domain-routed Pub/Sub topics.

The production notification push path is now private by default. Cloud Build deploys `custoking-notification-service` with unauthenticated access disabled, creates a dedicated Pub/Sub push service account, grants it `roles/run.invoker`, removes any stale `allUsers` invoker binding, and configures the subscription with `--push-auth-service-account`. The application-level push token remains as a second guard and keeps the local compose HTTP path identical to production message shape.

Local HTTP outbox publishing now only claims events matching the configured notification event prefix. This prevents local compose from silently marking unrelated domain events as processed when the backend is wired directly to the notification service instead of Pub/Sub.

Notification inbox handling now supports retryable failure states. Processed duplicate messages return successfully, while existing `FAILED` or unprocessed inbox rows are processed again on Pub/Sub redelivery. Delivery success clears `last_error`, so transient provider failures remain visible and can recover without losing idempotency.

The notification service now also has an internal scheduled inbox retry worker. It scans failed inbox rows, processes them through the same application processor used by the Pub/Sub push endpoint, and leaves failures visible for the next retry cycle. Local compose uses a short retry interval for fast smoke checks, while Cloud Run defaults to a 30-second retry interval.

Notification service Actuator health now includes a `notificationInbox` contributor with received, processed, failed, and oldest-failed-age details. This gives operations a quick service-local view of async delivery state without querying the database directly.

Notification delivery now has an MSG91 provider behind the same `NotificationDeliveryProvider` port. It supports SMS flow messages, OTP, Email, and WhatsApp template dispatch, accepts the backend-normalized notification contract (`destination`, `template`, `variables`, `recipientName`), has a dry-run mode for local compose, and supports pass-through `msg91Body` for exact tenant/template-specific MSG91 request shapes. Production Cloud Run now selects `NOTIFICATION_DELIVERY_PROVIDER=msg91`, reads `MSG91_AUTH_KEY` from Secret Manager, and keeps the inbox retry/health behavior unchanged.

Command-center reminder workflows now publish `NotificationRequested` events instead of only writing local demo-safe notification logs. Fee-defaulter reminders, low-attendance meeting invites, and class-photography/event payment reminders create `QUEUED` request logs through the extracted notification service and publish normalized async notification requests. Backend-local reminder log persistence has been removed from these command-center flows; if notification-service is unavailable, the compatibility endpoint now fails explicitly instead of writing another service's table.

Notification delivery attempts are now owned by `notification-service`. The service persists one `notification_delivery_attempts` row per provider attempt, records provider/channel/status/error metadata, and exposes delivered/failed attempt counters through the `notificationInbox` health contributor. Backend notification logs remain request-side compatibility records only; delivery observability now lives with the service that performs delivery.

Notification service now exposes `GET /api/v1/notifications/{eventId}` for service-owned delivery status. The response includes inbox metadata plus provider delivery attempts, giving future backend/UI/reporting integration a service API instead of direct table reads.

The backend now has a neutral `NotificationStatusProvider` port and an HTTP adapter for `notification-service`. Local compose enables it with `IMS_NOTIFICATION_SERVICE_BASE_URL=http://notification-service:8080`, and a backend `notificationService` health contributor checks the dependency. Cloud Build promotes production backend delegation after deploying the private notification service, resolving its Cloud Run URL, and granting backend `roles/run.invoker`.

Backend read paths are beginning to consume the notification-service status API. Fee-defaulter reminder status now prefers service-owned delivery status when the local request log ID maps to a notification event, and broadcast delivery aggregation will use notification-service attempt status for any broadcast delivery logs that have corresponding async notification event IDs. Backend-local notification logs are now compatibility/request records rather than the delivery source of truth.

Notification-service status lookup is now protected by a separate service token. The service requires `X-Notification-Service-Token` when `NOTIFICATION_STATUS_TOKEN` is configured, and the backend client sends `IMS_NOTIFICATION_SERVICE_TOKEN`. Local compose wires a dev token; production uses the `notification-status-token` Secret Manager secret when status lookup is enabled.

Local composition now includes a physically separate `api-gateway` service. The frontend container is static-only, backend API traffic routes through `/api/v1/`, and direct service traffic routes through explicit service prefixes such as `/student-api/v1/`, `/fee-api/v1/`, `/catalog-api/v1/`, `/reporting-api/v1/`, and `/billing-api/v1/`. The gateway injects the local compose service tokens for those service-prefix routes, so direct-service smokes verify the physically split services without exposing token details to callers.

`audit-service` is now physically extracted under `services/audit-service`. It owns the `audit` PostgreSQL schema, runs its own Flyway history table, and exposes token-protected ingestion at `POST /api/v1/audit/events`. Local compose runs it separately on port `8082`, and the gateway exposes it through `/audit-api/v1/`.

The backend now publishes audit events to the extracted audit service through an HTTP adapter configured by `IMS_AUDIT_SERVICE_ENABLED`, `IMS_AUDIT_SERVICE_BASE_URL`, and `IMS_AUDIT_SERVICE_TOKEN`. Backend-local audit table writes have been removed from request-time recording; audit-service is the sink for new audit events.

Audit-service now also owns paged audit-event reads through `GET /api/v1/audit/events`. The backend `/api/v1/audit-logs` contract scopes tenant access locally and delegates reads to audit-service. Backend-local audit read fallback has been removed; if audit-service is unavailable, audit-log reads now fail explicitly. Direct service reads, gateway-routed reads, and backend audit-log reads have been smoke tested against service-owned rows.

`audit-service` no longer exposes runtime Java/API access to legacy `public.audit_log`. Historical backfill is now an explicit operator SQL script at `scripts/audit-backfill-legacy-audit-log.sql`, which copies unmatched legacy rows into `audit.audit_events` before decommissioning historical `public.audit_log` storage after retention requirements are met.

Backend-local audit persistence has now been cut from request-time audit recording. `AuditLogService` no longer injects or writes `AuditLogRepository`; it publishes audit events to `audit-service` and emits structured logs only, while audit-log reads already delegate to `audit-service`. The legacy `public.audit_log` table remains only as historical data for bounded backfill until it is decommissioned.

Notification broadcast compatibility no longer injects backend-local broadcast or delivery-log repositories. Broadcast list/create/approve/send/delivery-status paths are service-backed through reporting-service and notification-service, with backend only recording the existing command-center feed side effect. Full demo seeding also no longer writes synthetic legacy audit rows.

`identity-service` is now physically extracted under `services/identity-service` in compatibility mode. It exposes `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`, and token-protected `/api/v1/auth/introspect`, issues JWTs with the same signing contract as the backend, persists refresh-token sessions to `auth_sessions`, and resolves roles/permissions from the existing RBAC tables. Local compose runs it separately on port `8083`, and the gateway exposes direct token-protected service diagnostics through `/identity-api/v1/`.

Gateway auth routing sends the existing frontend `/api/v1/auth/**` path to backend. Backend delegates login/refresh/logout to `identity-service`, preserving the frontend contract while keeping canonical backend outbox recording for `identity.user-logged-in.v1`. Identity-service-issued tokens have been smoke tested against backend protected APIs. Backend JWT authentication and request logging load the authenticated principal through identity-service token introspection when `IMS_IDENTITY_SERVICE_ENABLED=true`, including the service-owned effective permission set; backend-local user/RBAC lookup fallback has been removed from enabled-service runtime.

`tenant-school-service` is now physically extracted under `services/tenant-school-service` in compatibility mode. It exposes token-protected read APIs for `/api/v1/schools`, `/api/v1/schools/{id}`, `/api/v1/zones`, and `/api/v1/zones/{id}`, reading the existing school and zone tables while backend write workflows remain in place. Local compose runs it on port `8084`, and the gateway exposes it through `/tenant-api/v1/`.

The next tenant/school step is to move school, section, class, zone, and entitlement writes into `tenant-school-service`, then relocate those tables from the shared `public` schema into a tenant-school owned schema or database.

`student-service` is now physically extracted under `services/student-service` in compatibility mode. It exposes token-protected read APIs for `/api/v1/students` and `/api/v1/students/{id}` using JDBC against the existing `students` table, avoiding backend JPA relationship coupling while write/import ownership remains in the backend. Local compose runs it on port `8085`, and the gateway exposes it through `/student-api/v1/`.

The next student step is to move admission/import/photo-review writes into `student-service`, then relocate student-owned tables and import batch rows into a student-owned schema or database.

`attendance-service` is now physically extracted under `services/attendance-service` in compatibility mode. It exposes token-protected read APIs for `/api/v1/attendance/daily` and `/api/v1/attendance/records` using JDBC against existing attendance tables. Local compose runs it on port `8086`, and the gateway exposes it through `/attendance-api/v1/`.

The next attendance step is to move daily attendance submission writes into `attendance-service`, then relocate `attendance_daily` and `attendance_student_records` into an attendance-owned schema or database.

`identity-service` now also owns token-protected compatibility reads for RBAC tables and user directory data. The service exposes roles, permissions, role-permission mappings, user-role assignments, RBAC audit rows, and user directory rows through `/identity-api/v1/`, without exposing password hashes.

`tenant-school-service` now also owns token-protected compatibility reads for module entitlements, classes, sections, academic years, staff, zone-school mappings, zone-admin assignments, and superadmin school stats. This moves the school hierarchy read model out of the backend while school/zone write workflows remain staged in the backend.

The existing backend module-entitlement endpoints now delegate list, active-module, enablement-check, upsert, and disable operations to `tenant-school-service` when `IMS_TENANT_SCHOOL_SERVICE_ENABLED=true`. Local compose enables this path, so module entitlement reads and writes execute through the physically split tenant-school service. Backend-local entitlement fallback has been removed from these paths; if tenant-school-service is unavailable, module entitlement checks and management routes now fail explicitly. Gateway and direct-service smokes validate list, active-module reads, upsert visibility, disable cleanup, and direct tenant-school visibility.

`student-service` now also exposes token-protected compatibility reads for import batches, import rows, student review campaigns, and review items. Import and review commands remain in the backend until the student-service write model is cut over.

`catalog-service` is physically extracted under `services/catalog-service` in compatibility mode. It exposes token-protected reads for catalog items, catalog orders, order stats, supply orders, annual-plan items, and annual-plan entries. Local compose runs it on port `8088`, and the gateway exposes it through `/catalog-api/v1/`.

`catalog-service` now owns local command execution for catalog order draft creation, placement, status updates, design approval, superadmin approval/return, superadmin order create/status operations including order ID allocation, catalog vendor-paid marking, annual-plan item saves, and annual-plan confirmation, plus catalog category reads, order list/stat/detail reads, superadmin order list/stat reads, pending-approval order reads, school catalog-metrics reads, and annual-plan aggregate reads. Backend workspace, supply, superadmin, command-center, and school-metrics compatible routes/providers delegate to `catalog-service` when `IMS_CATALOG_SERVICE_ENABLED=true`, preserving frontend `/workspace/orders`, `/workspace/annual-plan`, `/supply/**`, `/sa/orders/**`, `/dashboard/vendor-dues/catalog-orders/**`, and `SchoolOrderMetricsProvider` contracts. Backend-local fallback has been removed from the catalog compatibility facade, superadmin catalog provider, and school catalog-metrics provider for category reads, order create/list/detail/stats/pending reads, superadmin order list/stats/create/status operations, order state transitions, annual-plan list/save/confirm paths, and school order metrics. The retired local repository-backed annual-plan/order reader, command, workflow, draft, and budget-provider classes are no longer Spring beans in the backend runtime; if catalog-service is unavailable, those paths now fail explicitly. Direct-service and gateway smokes validate category reads, order workflow transitions, order list/stat/detail reads, superadmin order list/stat/create/status reads and writes, pending-approval reads, annual-plan read/write visibility, superadmin order writes with service-owned ID allocation, vendor-paid marking, catalog-backed school metrics, and cleanup.

`workflow-service` is physically extracted under `services/workflow-service` in compatibility mode. It exposes token-protected reads for workflow definitions, steps, instances, pending work, instance detail, and actions. Local compose runs it on port `8089`, and the gateway exposes it through `/workflow-api/v1/`.

`firefighting-service` is physically extracted under `services/firefighting-service` in compatibility mode. It exposes token-protected reads for firefighting requests, pending approvals, stats, request detail, and quotations. Local compose runs it on port `8090`, and the gateway exposes it through `/firefighting-api/v1/`.

`reporting-service` is physically extracted under `services/reporting-service` in compatibility mode. It exposes token-protected reads for command-center feed/actions, invoice read models and stats, academic events, event contributions, notification broadcasts, reorder signals, vendor dues, and summary counts. Local compose runs it on port `8091`, and the gateway exposes it through `/reporting-api/v1/`.

`billing-service` is physically extracted under `services/billing-service`. It exposes token-protected superadmin invoice list/detail/by-order reads and create/update commands using the existing `superadmin_invoices` table and owns invoice ID allocation through the `superadmin_order_seq` invoice counter. Local compose runs it on port `8092`, and the gateway exposes it through `/billing-api/v1/`.

The existing backend `/api/v1/sa/invoices/**` contract now delegates list, stats, create, update, and by-order lookup to `billing-service`. Local compose enables this path, so the current frontend-compatible superadmin invoice API exercises the physically split billing service. Backend-local invoice repository fallback has been removed from the superadmin invoice facade; if billing-service is unavailable, invoice list/stats/create/update paths now fail explicitly. Billing-service owns invoice ID allocation through the billing invoice sequence.

`fee-service` now owns local command execution for fee bands, fee items, fee assignments, and payment records in addition to compatibility reads, full fee-structure read/match/export models, active academic-year resolution for fee reports/dashboard/reminders, fee payment-list reads, fee receipt metadata reads, fee collection/overdue report reads, workspace fee dashboard module reads, student fee-profile enrichment reads, and overdue fee-reminder candidate selection. The backend fee facade and student fee-profile bridge delegate fee-structure CRUD/read/match/PDF export, fee assignment, payment recording, payment-list reads, receipt lookup, fee report reads, workspace fee dashboard summary/overdue reads, roster fee-profile enrichment, post-student-create fee assignment, and `/api/v1/fees/send-reminders` overdue selection to `fee-service`, preserving the existing frontend `/api/v1/fee-structure`, `/api/v1/fee-structure/match`, `/api/v1/fee-structure/export`, `/api/v1/fee-assignments`, `/api/v1/payments`, `/api/v1/receipts/{paymentId}/pdf`, `/api/v1/fees/report`, `/api/v1/fees/overdue`, `/api/v1/fees/send-reminders`, workspace dashboard, workspace receipt, and roster fee-profile contracts. Backend-local fallback has been removed from these fee facade and student fee-profile paths, and the retired local fee repository-backed `FeeCommandService`, `FeePaymentRecorder`, `FeePlanAssigner`, `FeeStructureCatalog`, and `FeeCollectionReader` are no longer Spring beans in the backend runtime; if fee-service is unavailable, these paths now fail explicitly. Gateway and direct-service smokes validate that service-owned writes are visible through fee-service reads, that fee-structure list/match/export are service-owned, that fee reminder payload selection is service-owned before backend publishes notification requests, that payment-list and receipt PDFs resolve from service-owned metadata, that roster fee-profile enrichment matches direct fee-service assignment data, and that fee collection/overdue reports plus workspace fee dashboard values are served through the split service with fee-service-owned active-year resolution.

`tenant-school-service` now owns local command execution for school create/update, workspace staff creation, zone create/update, zone school assignment/removal, and module entitlement writes in addition to compatibility reads, workspace school summary reads, active academic-year reads, section-count reads, staff-list reads, zone-admin school expansion reads, superadmin school-stat reads, and fee/student roster class-section structure reads. Backend school-management, zone-management, superadmin, fee-collection roster, and workspace-compatible routes delegate to `tenant-school-service` when `IMS_TENANT_SCHOOL_SERVICE_ENABLED=true`, preserving `/api/v1/schools`, `/api/v1/zones`, `/api/v1/sa/schools`, `/api/v1/classes`, `/api/v1/classes/{classId}/sections`, `/api/v1/workspace`, and `/api/v1/workspace/staff` contracts. Backend-local school and zone command/read fallback plus workspace section/staff and roster class/section empty fallbacks have been removed from the school/zone/workspace/structure compatibility facades; if tenant-school-service is unavailable, these paths now fail explicitly. Gateway and direct-service smokes validate school list/stat reads, direct tenant-school visibility, workspace section/staff reads, zone reads, roster class/section reads, zone create/update, school assignment/removal, direct zone-school reads, and cleanup.

`identity-service` now owns local command execution for school admin provisioning, school operations-user provisioning, zone admin provisioning, RBAC role create/update, scoped role assignment, role revocation, direct backend login/refresh/logout delegation, workspace user-directory reads, RBAC role/permission catalog reads, user role-assignment reads, effective permission reads, login/refresh/logout sessions, and token introspection for backend request authentication. The service exposes token-protected `/api/v1/users/provisioning/**`, `/api/v1/users`, `/api/v1/rbac/**`, and `/api/v1/auth/introspect` operations, hashes temporary passwords locally, retires prior scoped users/sessions, writes scoped RBAC assignments, records zone admin assignments, and writes RBAC audit rows for role and assignment changes. Backend school, zone, RBAC management, workspace, user-list, direct `/api/v1/auth/**`, JWT-authentication, request-logging, and tenant-scope construction paths delegate identity-owned writes/reads/principal loading/role assignments to `identity-service` when `IMS_IDENTITY_SERVICE_ENABLED=true`; tenant-scope zone expansion delegates zone-school mapping reads to `tenant-school-service` when enabled. This preserves `/api/v1/schools/{id}/admin`, `/api/v1/schools/{id}/operations-user`, `/api/v1/zones/{id}/admin`, `/api/v1/rbac/**`, `/api/v1/users`, direct backend auth compatibility, and workspace user payloads. Backend-local identity fallback has been removed for role catalog reads/writes, permission reads, user assignment reads, effective permission reads, scoped assignment writes, assignment revocation, direct backend auth, enabled-service request authentication, enabled-service tenant-scope reads, and workspace user-directory payloads; if identity-service or tenant-school-service is unavailable, those management/authentication/scope/user-directory paths now fail explicitly. Direct-service and gateway smokes validate scoped school/zone RBAC writes, role catalog writes/reads, permission catalog reads, user role-assignment reads, effective permission reads, user-directory reads, token introspection, protected-route principal loading, direct backend and gateway login payloads, tenant-scope enforcement for superadmin and school-admin routes, workspace user payloads, and cleanup.

`attendance-service` now owns local command execution for daily attendance entry and day submission in addition to compatibility reads, daily summary reads, section-info reads, and workspace attendance summary reads. The backend attendance command/read services delegate `/api/v1/attendance/daily-entry`, `/api/v1/attendance/submit-day`, `/api/v1/attendance/daily-summary`, `/api/v1/attendance/section-info`, and workspace attendance composition to `attendance-service`, preserving existing frontend routes. Backend-local attendance repository fallback has been removed from these paths; if attendance-service is unavailable, they now fail explicitly. Gateway and direct-service smokes verify service-owned daily row writes, direct reads, summary/section-info reads, workspace attendance composition, submitted/locked state, and cleanup.

`student-service` now owns local command execution for direct student creation, student photo URL updates, student import preview/confirm/status, and student review campaign/item writes in addition to compatibility reads, student lifecycle status reads, review campaign item-list reads, workspace student-count reads, annual-plan student-count reads, first-student metadata reads, workspace student table reads, paged student-list reads, student detail/contact reads, and class-section roster student identity reads. The backend workspace student create path delegates the student row write to `student-service` when `IMS_STUDENT_SERVICE_ENABLED=true`, then keeps the existing fee-assignment side effect through the fee boundary. Existing `/api/v1/students/import/**`, workspace import routes, `/api/v1/dashboard/student-lifecycle/**` review initiation/status routes, `/api/v1/student-review-campaigns/{campaignId}/items`, `/api/v1/student-review-items/**` update routes, workspace dashboard student counts/table data, `/api/v1/students`, `/api/v1/students/{id}`, `/api/v1/classes/{classId}/sections/{sectionId}/students`, command-center fee-defaulter reminder target lookup, low-attendance meeting-invite target lookup, class-photography payment-reminder target lookup, recent-activity first-student metadata, and annual-plan per-student calculations now execute through the split service. Backend-local fallback has been removed from direct student create/photo, student workspace/list/detail/count/first-student reads, class/section roster structure reads, roster student reads, import preview/confirm/status, student-review status/item facades, and dashboard student contact lookups; the retired backend-local student command/import helper code and repository injections for student, school, class, section, academic-year, import-batch, and import-row tables have also been removed from active delegated student services. If student-service or tenant-school-service is unavailable, those paths now fail explicitly. Gateway and direct service smokes validate service-owned student create/photo/import/review writes, student lifecycle status reads, review item-list reads, workspace student metadata/table reads, paged student-list reads, student detail/contact reads, roster identity reads with fee-profile enrichment, fee-defaulter reminder contact lookup, low-attendance invite contact lookup, class-photography reminder contact lookup using a temporary event/contribution, student-review status delegation, student import template download, and cleanup.

The unused backend-local `StudentDomainService`, `SchoolDomainService`, catalog/firefighting domain helpers, catalog annual-plan active-year helper, zone support helper, and old fee/catalog/firefighting/workflow implementation services are no longer Spring beans in the backend runtime. `AttendanceSupport` remains only as a lightweight date parser and no longer reads `AcademicYearRepository`; active attendance reads delegate date-scoped queries to `attendance-service`. Gateway and direct-service smokes validate attendance summary/section reads, zone reads, student reads, and school reads after this runtime bean cleanup.

Backend module-entitlement reads/writes now depend only on `TenantSchoolServiceClient`; the stale local `SchoolModuleEntitlementRepository` injection has been removed. The retired local school onboarding and section-provisioning services are also no longer Spring beans, because active school, module, class, and section compatibility routes are delegated to `tenant-school-service` and identity-service. Gateway and direct-service smokes validate school list, module list/active reads, class list, and class-section reads after this cleanup.

Backend direct auth, RBAC authorization, request-principal loading, and tenant-scope construction are now strict identity/tenant-service compatibility paths. `AuthService` delegates login/refresh/logout to `identity-service` without local user/session fallback. `RbacService` delegates permission and role reads to `identity-service`, while generic legacy role mutation remains disabled; public scoped RBAC writes continue through `RbacManagementService` and `IdentityServiceClient`. `AppUserDetailsService` loads authenticated principals through identity token introspection only, and `TenantScopeService` resolves assignments through `identity-service` and zone-school expansion through `tenant-school-service` only. The retired local RBAC audit writer and fee event publisher/domain-service runtime wiring are no longer Spring beans; bootstrap fee math now uses a repository-free `DefaultBootstrapFeeCalculator`. Gateway and direct-service smokes validate login/refresh/logout, protected route principal loading, tenant-scope school access, RBAC role/permission reads, and identity-service permission parity after this cleanup.

Backend repository-backed startup seeders and the legacy backend JPA entity/repository source tree have been removed. Local compose starts the backend and extracted services with the `prod` profile, backend JPA scans only the outbox, and identity, tenant-school, student, fee, attendance, catalog, workflow, firefighting, billing, reporting, notification, and audit data ownership lives in the extracted services. Initial users and demo data are now an operator/identity-service responsibility, not backend startup behavior.

`workflow-service` now owns local command execution for workflow instance create/get and state transitions, plus pending-approval and action-history reads. The backend workflow facade delegates create/get, submit, approve, reject, cancel, complete, pending, and action-read operations to `workflow-service`, preserving existing backend controller contracts for approval actions. Backend-local workflow command/read fallback has been removed from this facade, and the retired local repository-backed `WorkflowReader` and `WorkflowCommandService` are no longer Spring beans in the backend runtime; if workflow-service is unavailable, command and read operations now fail explicitly. Smokes validate backend pending/action read delegation against direct workflow-service reads for the available local data set.

`firefighting-service` now owns local command execution for firefighting request drafts, quotation CRUD, submit, bursar/principal approval, Custoking approval, rejection, fulfillment, and vendor-paid marking. Backend firefighting, workspace-compatible, and vendor-dues routes delegate to `firefighting-service`, preserving frontend `/workspace/firefighting`, `/ff/requests/**`, and `/api/v1/dashboard/vendor-dues/firefighting/{code}/mark-paid` contracts. Backend-local fallback has been removed from the public firefighting facade and workspace firefighting provider, and the retired local repository-backed request reader/command/mapper/code-generator classes are no longer Spring beans in the backend runtime; if the split service is unavailable, command paths now fail explicitly. Gateway and direct-service smokes validate request/detail/timeline reads, pending approvals, stats, direct service read visibility, and prior command-path coverage for draft create, workspace-originated draft create, quotation add, submit, approval chain, fulfillment, vendor-paid marking, and cleanup.

`notification-service` now owns local command execution for notification broadcast draft creation, approval, send state transitions, command-center reminder request-log creation, and broadcast delivery-status reads. The backend broadcast and reminder APIs delegate those writes and delivery-status reads to `notification-service` when `IMS_NOTIFICATION_SERVICE_ENABLED=true`, preserving `/api/v1/notifications/broadcasts/**`, fee-defaulter reminders, low-attendance meeting invites, and class-photography/event payment reminders. Backend-local fallback has been removed from broadcast list/create/approve/send/delivery-status and reminder request-log flows; if notification-service is unavailable, these compatibility paths now fail explicitly. Recipient fanout and delivery-log reconciliation remain staged behind the notification event pipeline and the MSG91 adapter, which is the current provider direction for OTP, SMS, Email, and WhatsApp.

`reporting-service` now owns local command execution for command-center action accept/dismiss transitions with scoped action access validation, command-center feed append operations, academic event contribution reminder target validation/selection, and academic event contribution reminder timestamp updates. It also owns command-centre summary/action/feed reads, notification broadcast list reads, class-photography payment-status reads, low-attendance dashboard reads, fee-defaulter dashboard reads, dashboard command-center aggregate reads, daily brief source data, reorder-signal reads, and the vendor-dues dashboard read model over catalog orders and firefighting requests. The backend command-centre API, notification broadcast list API, internal feed recorder, class-photography payment-status/reminder flows, low-attendance dashboard routes, fee-defaulter dashboard route, dashboard command-center route, reorder dashboard route, and vendor-dues dashboard route delegate to `reporting-service` when `IMS_REPORTING_SERVICE_ENABLED=true`, preserving `/api/v1/command-centre/**`, `/api/v1/notifications/broadcasts`, `/api/v1/dashboard/events/class-photography/payment-status`, `/api/v1/dashboard/events/{eventId}/payment-reminders`, `/api/v1/dashboard/attendance/**`, `/api/v1/dashboard/finance/fee-defaulters`, `/api/v1/dashboard/command-center`, `/api/v1/dashboard/reorder-signals`, and `/api/v1/dashboard/vendor-dues`. Backend-local and default-empty fallbacks have been removed from command-centre action/feed/summary/daily-brief/dashboard aggregation, student-review, low-attendance, fee-defaulter, class-photography, reorder, and vendor-dues command-center paths; if reporting-service is unavailable, these paths now fail explicitly instead of returning zeroed dashboards. The retired command-centre local summary fallback code and repository injections for fee, attendance, firefighting, catalog-order, school, and command-center action tables have also been removed, so active backend command-centre routes now depend only on `ReportingServiceClient`. Direct-service and gateway smokes validate action state changes with scoped service-side validation, feed visibility, command-centre summary/action/feed reads, notification broadcast list reads, class-photography payment-status reads, class-photography reminder target validation/selection and timestamp marking with temporary rows, low-attendance section/student reads, fee-defaulter reads, dashboard command-center aggregate reads, daily brief generation, reorder-signal reads, event-contribution reminder updates, vendor-dues reads, notification request creation, and cleanup.

The local `api-gateway` now routes service-prefix traffic directly to Docker service DNS names and injects the required local service-token headers for extracted services. Gateway route-matrix smokes validate tenant, student, attendance, fee, catalog, workflow, firefighting, reporting, and billing service prefixes without callers supplying internal tokens.

Cloud Build now promotes production backend delegation after all extracted Cloud Run services are deployed. The promotion step resolves each private service URL, grants the backend runtime service account `roles/run.invoker` on notification, audit, identity, tenant-school, student, attendance, fee, catalog, workflow, firefighting, reporting, and billing services, then updates backend service env vars so migrated routes use the physically split services in production. This closes the deployment gap created by removing backend-local request-time fallbacks.

Backend service clients now have a shared Cloud Run service-auth RestClient customizer. It attaches Google identity tokens only for outbound `https://*.run.app` calls, caching tokens per audience, so private Cloud Run services can enforce `roles/run.invoker` while Docker/local service URLs continue to use only the existing service-token headers.

Backend readiness now includes an `extractedServices` health contributor. Each enabled extracted service is pinged at `/actuator/health`; a missing base URL or unreachable service makes aggregate backend health fail, which matches the current no-fallback runtime stance for migrated routes.

`api-gateway` is now environment-rendered instead of hardcoded to Docker DNS names. Local compose keeps Docker defaults and direct service-prefix smoke routes; Cloud Build deploys the gateway to Cloud Run with frontend/backend Cloud Run URLs and points production `/api/v1/**` traffic at backend so backend can perform authenticated private service calls with identity tokens.

# Custoking IMS Low-Level Design

## Repository Layout

```text
frontend/                 React/Vite SPA
services/api-gateway/     nginx gateway
services/*-service/       Domain microservices
scripts/                  Audits, smokes, deployment checks
deploy/gcp/               GCP runbooks and Cloud Run job manifests
docs/                     Architecture, contracts, runbooks
cloudbuild.yaml           Build and deploy pipeline
docker-compose.yml        Local split-service topology
```

## Request Flow

1. Frontend calls `/api/v1/**` through the API gateway.
2. Gateway routes public API traffic directly to the owning service.
3. Domain service validates its internal token, executes against its schema, and returns the existing frontend-compatible payload.

## Service Contracts

| Gateway route group | Header | Owning service |
| --- | --- | --- |
| Identity client | `X-Identity-Service-Token` | `identity-service` |
| Tenant school client | `X-Tenant-School-Token` | `tenant-school-service` |
| Student client | `X-Student-Service-Token` | `student-service` |
| Attendance client | `X-Attendance-Service-Token` | `attendance-service` |
| Fee client | `X-Fee-Service-Token` | `fee-service` |
| Catalog client | `X-Catalog-Service-Token` | `catalog-service` |
| Workflow client | `X-Workflow-Service-Token` | `workflow-service` |
| Firefighting client | `X-Firefighting-Service-Token` | `firefighting-service` |
| Reporting client | `X-Reporting-Service-Token` | `reporting-service` |
| Billing client | `X-Billing-Service-Token` | `billing-service` |
| Audit client | `X-Audit-Ingest-Token` | `audit-service` |

## Database Design

Each domain service has its own Flyway history table and schema. Runtime code should use schema-qualified SQL for owned tables. Cross-domain foreign keys have been removed or retired through forward migrations because they couple service deployment order and block independent ownership.

| Schema | Example tables |
| --- | --- |
| `identity` | `app_users`, `roles`, `permissions`, `user_role_assignments`, `auth_sessions` |
| `tenant_school` | `schools`, `school_classes`, `school_sections`, `staff_members`, `school_module_entitlements`, `zones` |
| `student` | `students`, `import_batches`, `import_rows`, `student_review_campaigns` |
| `attendance` | `attendance_daily`, `attendance_student_records` |
| `fee` | `fee_bands`, `fee_items`, `fee_assignments`, `payment_records` |
| `catalog` | `catalog_items`, `catalog_orders`, `annual_plan_items`, `supply_orders` |
| `workflow` | `workflow_definitions`, `workflow_instances`, `workflow_actions` |
| `firefighting` | `firefighting_requests`, `ff_quotations` |
| `reporting` | `command_center_feed`, `academic_events`, `event_student_contributions` |
| `notification` | `notification_inbox`, `notification_broadcasts`, `notification_logs`, `sender_profiles` |
| `audit` | `audit_events` |
| `billing` | `superadmin_invoices`, `superadmin_order_seq` |

## Events

Domain services expose synchronous compatibility APIs today. Event publishing remains the target integration model for cross-service projections; consumers must be idempotent and store processed event IDs in their own schema when those publishers are enabled.

## MSG91 Notification Design

Notification delivery is centralized in `notification-service`.

- Custoking-shared launch mode sends email from tenant aliases such as `school@custoking.com`.
- WhatsApp uses Custoking-managed MSG91 setup initially.
- Hybrid onboarding allows a school sender profile to move to its own WhatsApp Business account later.
- Sender profiles and onboarding status live in the notification schema.

## Cloud Run Configuration

Service containers use Java 21 with constrained JVM memory. Cloud Run services are private except the frontend/API gateway. Domain services need:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- optional `FLYWAY_*` for services that run migrations
- service-specific schema env var
- service-specific internal token secret

## Verification Commands

```powershell
powershell -ExecutionPolicy Bypass -File scripts\audit-microservice-runtime-boundaries.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-microservice-db-boundaries.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-legacy-compatibility-cloudsql.ps1
```

Direct private service smoke:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ensure-direct-service-smoke-identity.ps1 -ProjectId custoking -Region asia-south2
powershell -ExecutionPolicy Bypass -File scripts\new-direct-service-smoke-job.ps1 -ProjectId custoking -Region asia-south2 -OutputPath artifacts\direct-service-smoke-job.generated.yaml
gcloud run jobs replace artifacts\direct-service-smoke-job.generated.yaml --project=custoking --region=asia-south2
gcloud run jobs execute ims-direct-service-smoke --project=custoking --region=asia-south2 --wait
```

Gateway smokes require production tokens or credentials:

```powershell
$env:IMS_SMOKE_SUPERADMIN_TOKEN = "<token>"
$env:IMS_SMOKE_ADMIN_TOKEN = "<token>"
powershell -ExecutionPolicy Bypass -File scripts\smoke-deployment-readiness.ps1 -GatewayBaseUrl "https://custoking-api-gateway-xkv7oenbna-em.a.run.app" -SchoolId 4
powershell -ExecutionPolicy Bypass -File scripts\smoke-production-write-paths.ps1
```

## Migration Rules

- Do not reintroduce runtime SQL against retired public domain tables.
- Do not edit already-applied Flyway migrations unless a repair/baseline plan is part of the deployment.
- Use forward migrations to remove constraints, triggers, views, and compatibility shims.
- Archive or verify data before dropping old compatibility structures.

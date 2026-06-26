# Custoking IMS High-Level Architecture

## Purpose

Custoking IMS is a multi-tenant school operations platform. The current architecture is a Cloud Run microservice deployment where the API gateway preserves the existing frontend API while domain services own runtime data.

## Runtime Topology

```text
Browser
  -> custoking-frontend (Cloud Run nginx SPA)
  -> custoking-api-gateway (nginx route gateway)
  -> domain services (Cloud Run, private IAM)
  -> Cloud SQL PostgreSQL
  -> notification/audit/reporting service APIs
```

## Services

| Service | Responsibility | Data owner |
| --- | --- | --- |
| `identity-service` | Login, refresh/logout, token introspection, users, roles, permissions, scoped assignments | `identity` schema |
| `tenant-school-service` | Schools, zones, classes, sections, staff, school module entitlements | `tenant_school` schema |
| `student-service` | Student records, imports, review campaigns, photos metadata | `student` schema |
| `attendance-service` | Daily attendance summaries and student attendance records | `attendance` schema |
| `fee-service` | Fee bands/items, assignments, payments, fee status updates | `fee` schema |
| `catalog-service` | Catalog items, catalog orders, annual plans, supply orders | `catalog` schema |
| `workflow-service` | Workflow definitions, instances, steps, actions | `workflow` schema |
| `firefighting-service` | Firefighting requests, quotes, approval/fulfilment workflow | `firefighting` schema |
| `reporting-service` | Command center projections, fee/attendance/event reporting | `reporting` schema |
| `notification-service` | MSG91 sender profiles, email/SMS/WhatsApp delivery, broadcast logs | `notification` schema |
| `audit-service` | Audit ingestion and read models | `audit` schema |
| `billing-service` | Superadmin invoices and order sequences | `billing` schema |

## Data Ownership

The old monolithic public tables have been retired in production. Domain tables now live in bounded schemas. Cross-service access is allowed only through:

- service APIs over HTTP for request/response workflows
- Pub/Sub event projection for asynchronous read models
- explicit compatibility audit scripts during migration verification

Direct runtime reads from retired public domain tables are not allowed.

## Communication

- Browser traffic enters through `custoking-api-gateway`.
- The API gateway routes public `/api/v1/**` compatibility paths to the owning private Cloud Run service and injects service tokens from Secret Manager.
- Domain services publish events to Pub/Sub using a shared event envelope.
- Notification and reporting consume event projections asynchronously.

## Security

- User authentication is JWT plus refresh cookie.
- Cloud Run domain services are private and require Cloud Run IAM.
- Service-to-service authorization uses per-service tokens from Secret Manager.
- Tenant isolation is enforced in application services using scoped roles and school/zone checks.
- Secrets are stored in Secret Manager and injected into Cloud Run.

## Deployment

GitHub Actions authenticates to GCP through Workload Identity Federation and delegates builds/deploys to `cloudbuild.yaml`. Cloud Build builds all service images, pushes to Artifact Registry, and deploys Cloud Run services. The deploy workflow can run `ims-direct-service-smoke` after deployment to verify private service paths.

## Current Migration State

- Physical service directories exist under `services/`.
- Production public legacy domain tables were archived/dropped after compatibility audit.
- Legacy tenant shadow triggers were removed and a forward migration preserves that state.
- Catalog annual-plan runtime SQL no longer reads the old public `students` table.
- Remaining legacy references are historical Flyway backfill statements in already-applied migrations; do not edit those migration files without a Flyway repair/baseline strategy.

## Stability Gates

- Compile changed services.
- Run microservice static boundary audits.
- Run direct private service smoke for catalog and tenant-school.
- Run full gateway read/write smokes after deployment.
- Export image digests and Cloud Run revisions before promotion.

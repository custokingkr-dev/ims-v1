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
| `frontend` | React/Vite SPA served from Cloud Run | n/a |
| `api-gateway` | Public `/api/v1/**` compatibility routing, auth context propagation, frontend fallback | n/a |
| `identity-service` | Login, refresh/logout, token introspection, users, roles, permissions, scoped assignments | `identity` schema |
| `school-core-service` | Schools, zones, classes, sections, students, attendance, fee, catalog, student photos | `tenant_school`, `student`, `attendance`, `fee`, `catalog` schemas |
| `operations-service` | Workflow and firefighting operations | `workflow`, `firefighting` schemas |
| `platform-service` | Reporting projections, notification, audit | `reporting`, `notification`, `audit` schemas |
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

The previous GitHub Actions plus `cloudbuild.yaml` deployment path was retired on 2026-08-03. The active path uses affected-service GitHub Actions builds, content-addressed Artifact Registry images, direct Cloud Run deployment for normal dev changes, and Google Cloud Deploy canaries for production. Production promotes the exact digest approved by dev checks. `dev` branch owns dev; `main` owns prod. Stage templates exist in source but stage is not active. See `docs/current-state/deployment-cicd.md`.

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

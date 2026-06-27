# Custoking IMS

Custoking IMS is a multi-tenant school operations platform. The repository is now organized as a split-service system: a React frontend, a programmable API gateway, and domain microservices deployed to Cloud Run.

## Architecture

Detailed architecture docs:

- [High-Level Architecture](docs/ARCHITECTURE-HLD.md)
- [Low-Level Design](docs/ARCHITECTURE-LLD.md)
- [Event Envelope Contract](docs/EVENT-ENVELOPE-CONTRACT.md)
- [GCP Deployment Runbook](deploy/gcp/README.md)
- [MSG91 Production Setup](docs/MSG91-PRODUCTION-SETUP.md)

Runtime topology:

```text
frontend -> api-gateway -> private domain services -> Cloud SQL
                         -> notification/reporting/audit service APIs
```

Primary services:

| Path | Service |
| --- | --- |
| `frontend/` | React/Vite SPA |
| `services/api-gateway/` | Programmable route gateway with JWT introspection and Cloud Run service-token support |
| `services/identity-service/` | Auth, users, RBAC |
| `services/tenant-school-service/` | Schools, zones, classes, sections, staff, modules |
| `services/student-service/` | Students, imports, review campaigns |
| `services/attendance-service/` | Attendance |
| `services/fee-service/` | Fees and payments |
| `services/catalog-service/` | Catalog, orders, annual plans |
| `services/workflow-service/` | Workflow engine data |
| `services/firefighting-service/` | Firefighting workflow |
| `services/reporting-service/` | Command center/reporting projections |
| `services/notification-service/` | MSG91 notification delivery |
| `services/audit-service/` | Audit events |
| `services/billing-service/` | Superadmin billing |

## Local Development

Prerequisites:

- Java 21
- Node 20+
- Docker Desktop with WSL2
- Google Cloud SDK for deployment scripts

### Migrating a laptop from the old monolith `master`

If a machine was previously running the monolith stack (postgres + backend + frontend on
ports 5432/8080/80), use the setup script to tear that down, switch to this branch, and
bring the split-service stack up cleanly. It removes the orphaned `custoking-backend`
container, wipes the stale Postgres volume by default, checks out the branch, and waits
for every service to report healthy.

```powershell
# Windows / PowerShell
powershell -ExecutionPolicy Bypass -File scripts\setup-from-master.ps1
```

```bash
# macOS / Linux
./scripts/setup-from-master.sh
```

Common flags (`-ComposeProfile`/`--profile core|full`, `-KeepData`/`--keep-data`,
`-Force`/`--force` to auto-stash local edits, `-SkipBuild`/`--skip-build`). Pass
`-KeepData`/`--keep-data` only if you want to retain the existing database volume.

Frontend:

```powershell
cd frontend
npm ci
npm run dev
```

Low-memory local stack for login, school/student, attendance, fees, frontend, and gateway:

```powershell
docker compose --profile core up -d --build
```

Full local split-service stack for complete migration smoke testing:

```powershell
docker compose --profile full up -d --build
```

Infrastructure-only database startup:

```powershell
docker compose up -d postgres
```

The compose topology has explicit memory limits and profiles for local stability. The `api-gateway` does not use `depends_on` for every service; health and smoke scripts are responsible for readiness checks. To reclaim WSL memory after stopping services:

```powershell
docker compose --profile full stop
wsl --shutdown
```

Recommended `%USERPROFILE%\.wslconfig`:

```ini
[wsl2]
memory=8GB
processors=4
swap=2GB
localhostForwarding=true
pageReporting=true
autoMemoryReclaim=gradual
sparseVhd=true
```

## Verification

Compile changed Java services:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\catalog-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\tenant-school-service\pom.xml -DskipTests compile
```

Static and migration guardrails:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\audit-microservice-runtime-boundaries.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-microservice-db-boundaries.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-legacy-compatibility-cloudsql.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-compose-profiles.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-legacy-public-retirement-readiness.ps1
```

Production direct private-service smoke:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ensure-direct-service-smoke-identity.ps1 -ProjectId custoking-ims -Region asia-south2
powershell -ExecutionPolicy Bypass -File scripts\new-direct-service-smoke-job.ps1 -ProjectId custoking-ims -Region asia-south2 -OutputPath artifacts\direct-service-smoke-job.generated.yaml
gcloud run jobs replace artifacts\direct-service-smoke-job.generated.yaml --project=custoking-ims --region=asia-south2
gcloud run jobs execute ims-direct-service-smoke --project=custoking-ims --region=asia-south2 --wait
```

Gateway read/write smokes require production user tokens:

```powershell
$env:IMS_SMOKE_SUPERADMIN_TOKEN = "<superadmin token>"
$env:IMS_SMOKE_ADMIN_TOKEN = "<school admin token>"
powershell -ExecutionPolicy Bypass -File scripts\smoke-deployment-readiness.ps1 -GatewayBaseUrl "https://custoking-api-gateway-xkv7oenbna-em.a.run.app" -SchoolId 4
powershell -ExecutionPolicy Bypass -File scripts\smoke-production-write-paths.ps1
```

Frontend:

```powershell
cd frontend
npm ci
npm test
npm run build
```

## Deployment

GitHub Actions deployment:

1. Open **Actions -> Deploy to GCP**.
2. Choose `production` or `staging`.
3. Leave `commit_sha` empty to deploy the workflow commit, or provide an image tag.
4. Keep `run_direct_smoke` enabled for production.

Manual Cloud Build deployment:

```powershell
gcloud builds submit --config=cloudbuild.yaml --substitutions=_COMMIT_SHA=<tag>,_REGION=asia-south2 --project=custoking-ims .
```

Cloud Build builds all service images, pushes to Artifact Registry, deploys Cloud Run services, and wires Secret Manager values.

## Secrets

Production secrets live in GCP Secret Manager. Important secrets include:

- `ims-app-password`
- `db-password`
- `jwt-secret`
- `aadhar-secret`
- per-service internal tokens such as `catalog-read-token`, `tenant-school-read-token`, and `identity-introspection-token`
- MSG91 secrets when notification delivery is enabled

Never commit `.env`, production smoke tokens, MSG91 auth keys, or generated evidence artifacts.

## Migration Notes

The monolithic public domain tables have been retired in production after compatibility audit. Runtime code must not read or write retired public domain tables. Already-applied historical Flyway migrations may still contain backfill SQL from public tables; do not edit those files without a Flyway repair/baseline plan.

Forward migrations should be used for every post-split database change.

Before retiring legacy public tables in any environment, run the compatibility audit, generate archive-first SQL, review it, and only enable `-IncludeDropStatements` during an approved cleanup window.

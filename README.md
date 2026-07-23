# Custoking IMS

Custoking IMS is a multi-tenant school operations platform. The repository is now organized as a split-service system: a React frontend, a programmable API gateway, and domain microservices deployed to Cloud Run.

## Architecture

Detailed architecture docs:

- [High-Level Architecture](docs/ARCHITECTURE-HLD.md)
- [Low-Level Design](docs/ARCHITECTURE-LLD.md)
- [Event Envelope Contract](docs/EVENT-ENVELOPE-CONTRACT.md)
- [Local Setup](docs/LOCAL-SETUP.md)
- [Logical E2E Tests](docs/LOGICAL-E2E-TESTS.md)
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
| `services/school-core-service/` | Schools, zones, students, attendance, fees, catalog, supply orders |
| `services/operations-service/` | Workflow and urgent procurement |
| `services/platform-service/` | Reporting, notification, audit projections |
| `services/billing-service/` | Superadmin billing |

## Local Development

Prerequisites:

- JDK 25+
- Node.js 24 LTS (`>=24 <25`) with npm 11
- Docker Desktop with WSL2
- Google Cloud SDK for deployment scripts

Recommended Windows runtime location:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\configure-windows-runtime.ps1
```

Run this after cloning the repo. It creates `D:\Projects\Runtime` by default
and points future IntelliJ IDEA launches at runtime/config/cache/log directories
under that location. If the laptop does not have a D: drive, pass
`-RuntimeRoot "<path>"`. Close IntelliJ IDEA before copying existing IDE state:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\configure-windows-runtime.ps1 -MigrateIntelliJ
```

Docker Desktop's supported disk move is **Settings -> Resources -> Advanced ->
Disk image location**. Set it to `D:\Projects\Runtime\Docker`. If Docker
Desktop does not expose that setting on a laptop, the script can move the large
Docker WSL data disk through a Windows junction after Docker is shut down. The
fallback script requires Docker Desktop to have created
`%LOCALAPPDATA%\Docker\wsl\disk` at least once:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\configure-windows-runtime.ps1 -MoveDockerDisk
```

Normal WSL distros should be installed or moved under `D:\Projects\Runtime\WSL`.
Do not rely on moving Docker Desktop's small `docker-desktop` WSL main distro;
Docker Desktop manages it and may recreate it under `%LOCALAPPDATA%` at startup.

### New laptop setup

For a new laptop, run the bootstrap script first:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-local-dev.ps1
```

Then start the lightweight local stack:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-local-dev.ps1 -ComposeProfile core -SkipDependencyInstall
```

Starting a compose profile also applies the local `app_rt` runtime grants after service
migrations complete. Local compose disables OTLP export by default; dev/prod deployments
set the Cloud Trace OTLP endpoint separately.

If the laptop has stale Custoking containers or an old local database volume, reset the
local compose state and remove orphans:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-local-dev.ps1 -ComposeProfile core -SkipDependencyInstall -ResetData -RemoveOrphans
```

`-ResetData` deletes the local compose Postgres volume, so use it only for disposable
developer data.

### Migrating a laptop from the old monolith `master`

If a machine was previously running the monolith stack (postgres + backend + frontend on
ports 5432/8080/80), use the migration script to tear that down and bring the current
split-service stack up cleanly. It removes the orphaned `custoking-backend` container,
wipes the stale Postgres volume by default, checks out `main`, and waits for services to
report healthy.

```powershell
# Windows / PowerShell
powershell -ExecutionPolicy Bypass -File scripts\setup-from-master.ps1
```

```bash
# macOS / Linux
./scripts/setup-from-master.sh
```

Common flags (`-ComposeProfile`/`--profile core|full`, `-KeepData`/`--keep-data`,
`-Force`/`--force` to auto-stash local edits, `-SkipBuild`/`--skip-build`). For
`setup-local-dev.ps1`, use `-ResetData` for disposable local database resets and
`-RemoveOrphans` to clean old local containers. Pass `-KeepData`/`--keep-data` only if
you want to retain the existing database volume when using the old-monolith migration
script.

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

Tilt uses the same compose profiles:

```powershell
# Full split-service stack
tilt up

# Low-memory local stack
$env:TILT_COMPOSE_PROFILE='core'
tilt up
```

`tilt up` also runs the `local-runtime-grants` and `local-dev-users` setup resources. They apply runtime DB grants and create or refresh one
local login per role and prints the credentials in Tilt logs. See
[docs/LOCAL-SETUP.md](docs/LOCAL-SETUP.md#tilt) for the full credential table.

For a complete setup guide, use [docs/LOCAL-SETUP.md](docs/LOCAL-SETUP.md).

## Verification

Compile changed Java services:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\identity-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\school-core-service\pom.xml -DskipTests compile
```

Or use the repo test runner, which detects JDK 25+ and uses the root Maven wrapper:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\invoke-microservice-tests.ps1 -Services identity-service
```

Static and migration guardrails:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\audit-microservice-runtime-boundaries.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-microservice-db-boundaries.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-legacy-compatibility-cloudsql.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-compose-profiles.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-legacy-public-retirement-readiness.ps1
```

Local logical E2E for the full application flow:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\test-application-logical-e2e.ps1 -StartStack
```

This creates disposable local data and writes `artifacts\logical-e2e-result.json`.
Use the full compose profile because the suite covers identity, school-core,
operations, platform, billing, gateway routing, local DB evidence, and outbox rows.
See [docs/LOGICAL-E2E-TESTS.md](docs/LOGICAL-E2E-TESTS.md).

Production direct private-service smoke:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ensure-direct-service-smoke-identity.ps1 -ProjectId custoking -Region asia-south2
powershell -ExecutionPolicy Bypass -File scripts\new-direct-service-smoke-job.ps1 -ProjectId custoking -Region asia-south2 -OutputPath artifacts\direct-service-smoke-job.generated.yaml
gcloud run jobs replace artifacts\direct-service-smoke-job.generated.yaml --project=custoking --region=asia-south2
gcloud run jobs execute ims-direct-service-smoke --project=custoking --region=asia-south2 --wait
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
2. Choose `prod` or `dev`.
3. Leave `commit_sha` empty to deploy the workflow commit, or provide an image tag.
4. Choose `deploy_services`; default `frontend` deploys one service only.
5. Choose `all` only for an approved full fleet rollout.

Each deploy writes a visual GitHub Actions summary and uploads `deployment-evidence/`,
including the Mermaid deploy flow, stage timings, Cloud Build ID/log URL, smoke gate
durations, and current Cloud Run revisions. See `docs/current-state/deployment-cicd.md`
for the detailed CI/CD model.

Manual Cloud Build deployment:

```powershell
gcloud builds submit --config=cloudbuild.yaml --substitutions=_COMMIT_SHA=<tag>,_REGION=asia-south2,_DEPLOY_SERVICES=frontend --project=custoking .
```

Cloud Build builds and deploys the selected service. Use `_DEPLOY_SERVICES=all` for the previous all-service rollout.

## Secrets

Production secrets live in GCP Secret Manager. Important secrets include:

- `db-password` (appuser — single app + Flyway DB user)
- `jwt-secret`
- `aadhar-secret`
- per-service internal tokens such as `catalog-read-token`, `tenant-school-read-token`, and `identity-introspection-token`
- MSG91 secrets when notification delivery is enabled

Never commit `.env`, production smoke tokens, MSG91 auth keys, or generated evidence artifacts.

## Migration Notes

The monolithic public domain tables have been retired in production after compatibility audit. Runtime code must not read or write retired public domain tables. Already-applied historical Flyway migrations may still contain backfill SQL from public tables; do not edit those files without a Flyway repair/baseline plan.

Forward migrations should be used for every post-split database change.

Before retiring legacy public tables in any environment, run the compatibility audit, generate archive-first SQL, review it, and only enable `-IncludeDropStatements` during an approved cleanup window.

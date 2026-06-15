# Custoking IMS

A React + Spring Boot + PostgreSQL multi-tenant school operations platform supporting RBAC, Supply OS, and Firefighting workflows.

## Architecture

- **Frontend:** React 18 + TypeScript + Vite
- **Backend:** Spring Boot 3.4, Java 21, Spring Security (JWT + RBAC)
- **Database:** PostgreSQL 16 with Flyway migrations
- **Auth:** JWT (Bearer) + HttpOnly refresh cookie, role-based + permission-based access
- **Tenant isolation:** Application-layer via `TenantScopeService` + scoped `user_role_assignments`. PostgreSQL RLS is currently disabled — isolation is enforced in Java code, not at the DB layer.

## Local development

### Prerequisites
- Java 21, Maven 3.9+
- Node 20+, npm
- Docker + Docker Compose (for full stack)

### Backend only
```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Frontend only
```bash
cd frontend
npm ci
npm run dev
```

### Full stack with Docker Compose
```bash
docker compose up --build
```

Services:
| Service    | URL                      |
|-----------|--------------------------|
| Frontend  | http://localhost         |
| Backend   | http://localhost:8080    |
| PostgreSQL | localhost:5432          |

## Testing

### Backend tests
```bash
cd backend
mvn clean test
```

Integration tests use [Testcontainers](https://testcontainers.com/) — Docker must be running.

### Frontend tests
```bash
cd frontend
npm ci
npm test         # vitest unit tests
npm run build    # type-check + production build
```

## Packaging a clean source ZIP

To produce a ZIP that excludes build artifacts, secrets, and IDE files:

**Linux / macOS:**
```bash
./package-clean.sh
# Output: custoking-ims-clean.zip
```

**Windows (PowerShell):**
```powershell
.\package-clean.ps1
# Output: custoking-ims-clean.zip
```

The ZIP **includes:** source code, Flyway migrations, Dockerfiles, `docker-compose.yml`, GitHub Actions, `cloudbuild.yaml`, README files, `.env.example`.

The ZIP **excludes:** `.git/`, `node_modules/`, `dist/`, `target/`, `.env`, `.env.*`, `logs/`, `tmp/`, `.idea/`, `.vscode/`.

## Production deployment

### Environment variables (required)
| Variable | Description |
|----------|-------------|
| `SPRING_DATASOURCE_URL` | JDBC URL for PostgreSQL |
| `SPRING_DATASOURCE_USERNAME` | DB app user (read/write, no DDL) |
| `SPRING_DATASOURCE_PASSWORD` | DB app user password (use Secret Manager) |
| `FLYWAY_URL` | Separate JDBC URL for Flyway migrations |
| `FLYWAY_USERNAME` | DB migration user (DDL rights) |
| `FLYWAY_PASSWORD` | Migration user password (use Secret Manager) |
| `APP_JWT_SECRET` | HS256 JWT signing key (32+ chars) |
| `APP_AADHAR_SECRET` | Encryption key for Aadhaar data |
| `SUPERADMIN_EMAIL` | Initial superadmin email (bootstrap) |
| `SUPERADMIN_PASSWORD` | Initial superadmin password (Secret Manager) |
| `SPRING_PROFILES_ACTIVE` | Set to `prod` |
| `APP_BOOTSTRAP_USERS` | `true` only on first deploy; `false` thereafter |
| `SWAGGER_ENABLED` | `false` in production (default); `true` only for controlled internal access |

### Tenant isolation
Tenant isolation is enforced **at the application layer** using:
- `TenantScopeService` — derives accessible schools/zones from `user_role_assignments`
- Scoped RBAC — `school_id`/`zone_id` columns on `user_role_assignments`
- Repository/service-level filtering — all queries include a school scope check

PostgreSQL RLS is currently **disabled** (see `V117` migration). Re-enabling it is a Phase 2 task.

### Swagger in production
Swagger/OpenAPI is disabled by default in production (`SWAGGER_ENABLED=false`).
To enable for a controlled internal session:
```bash
SWAGGER_ENABLED=true  # set in Cloud Run env temporarily
```
When enabled, endpoints `/v3/api-docs/**` and `/swagger-ui/**` require a valid JWT.

### RBAC roles (Phase 1)
| Role | Scope | Key permissions |
|------|-------|-----------------|
| SUPERADMIN | Platform-wide | All permissions incl. `school:create`, `system:actuator` |
| ZONE_ADMIN | Zone-scoped | Zone + school read, role:read |
| ADMIN | School-scoped | Full school admin, no platform permissions |
| OPERATIONS | School-scoped | Students, attendance, orders, firefighting |
| ACCOUNTANT | School-scoped | Fees, payments, invoices |
| TEACHER | School-scoped | Students, attendance, timetable |
| VIEWER | School-scoped | Read-only access |

## CI/CD

GitHub Actions (`ci.yml`) runs on every push and PR:
1. **backend-test** — Maven tests + JaCoCo coverage (Testcontainers)
2. **db-migration-test** — Flyway migrations on fresh PostgreSQL
3. **frontend-build** — `npm ci && npm audit && npm run build`
4. **owasp-scan** — Dependency check (fails on CVSS ≥ 9, push only)
5. **secret-scan** — Gitleaks on full git history
6. **docker-build** — Backend + frontend image build smoke test
7. **trivy-scan** — Container vulnerability scan (fails on CRITICAL, push only)

Security scan suppression file: `backend/.owasp-suppressions.xml` — all entries require justification and expiry dates.

## GCP / Cloud Run deployment

### Architecture overview

```
Cloud Build → Artifact Registry (Docker image)
                      ↓
               Cloud Run (backend)   ← Secrets from Secret Manager
               Cloud Run (frontend)  ← Static SPA served via nginx
                      ↓
               Cloud SQL (PostgreSQL 16)
```

### Prerequisites

- GCP project with Cloud Run, Cloud Build, Artifact Registry, Cloud SQL, Secret Manager enabled
- A Cloud SQL PostgreSQL 16 instance with two users:
  - `ims_app` — app user (`SELECT / INSERT / UPDATE / DELETE` only)
  - `ims_flyway` (or a superuser) — migration user (`CREATE TABLE / ALTER / DROP`)
- All secrets created in Secret Manager (see list below)

### Secrets in Secret Manager

Create each secret, then grant the Cloud Run service account `roles/secretmanager.secretAccessor`:

```bash
# Create secrets (replace values with real ones)
gcloud secrets create APP_JWT_SECRET      --replication-policy=automatic
gcloud secrets create APP_AADHAR_SECRET   --replication-policy=automatic
gcloud secrets create SPRING_DATASOURCE_PASSWORD --replication-policy=automatic
gcloud secrets create FLYWAY_PASSWORD     --replication-policy=automatic
gcloud secrets create SUPERADMIN_PASSWORD --replication-policy=automatic

# Populate a secret version
echo -n "$(openssl rand -hex 32)" | \
  gcloud secrets versions add APP_JWT_SECRET --data-file=-
```

### Initial deploy (first time)

```bash
# 1. Build and push the backend image via Cloud Build
gcloud builds submit ./backend \
  --tag=us-central1-docker.pkg.dev/<PROJECT>/custoking/backend:latest

# 2. Deploy to Cloud Run
gcloud run deploy custoking-ims-backend \
  --image=us-central1-docker.pkg.dev/<PROJECT>/custoking/backend:latest \
  --region=us-central1 \
  --platform=managed \
  --allow-unauthenticated \
  --set-env-vars="SPRING_PROFILES_ACTIVE=prod,APP_BOOTSTRAP_USERS=true" \
  --set-secrets="APP_JWT_SECRET=APP_JWT_SECRET:latest,\
APP_AADHAR_SECRET=APP_AADHAR_SECRET:latest,\
SPRING_DATASOURCE_PASSWORD=SPRING_DATASOURCE_PASSWORD:latest,\
FLYWAY_PASSWORD=FLYWAY_PASSWORD:latest,\
SUPERADMIN_PASSWORD=SUPERADMIN_PASSWORD:latest" \
  --set-env-vars="SPRING_DATASOURCE_URL=jdbc:postgresql:///<DB_NAME>?cloudSqlInstance=<INSTANCE_CONNECTION_NAME>&socketFactory=com.google.cloud.sql.postgres.SocketFactory,\
SPRING_DATASOURCE_USERNAME=ims_app,\
FLYWAY_URL=jdbc:postgresql:///<DB_NAME>?cloudSqlInstance=<INSTANCE_CONNECTION_NAME>&socketFactory=com.google.cloud.sql.postgres.SocketFactory,\
FLYWAY_USERNAME=ims_flyway,\
APP_COOKIE_SECURE=true,\
APP_CORS_ALLOWED_ORIGINS=https://<FRONTEND_DOMAIN>"
```

After the first deploy, **change `APP_BOOTSTRAP_USERS` to `false`** to prevent re-seeding:

```bash
gcloud run services update custoking-ims-backend \
  --update-env-vars=APP_BOOTSTRAP_USERS=false \
  --region=us-central1
```

### Subsequent deploys

```bash
# Build new image (CI does this automatically via cloudbuild.yaml)
gcloud builds submit ./backend \
  --tag=us-central1-docker.pkg.dev/<PROJECT>/custoking/backend:<GIT_SHA>

# Update the Cloud Run service to use the new image
gcloud run services update custoking-ims-backend \
  --image=us-central1-docker.pkg.dev/<PROJECT>/custoking/backend:<GIT_SHA> \
  --region=us-central1
```

Cloud Run performs a zero-downtime rolling deploy by default.

### Rollback

```bash
# List recent revisions
gcloud run revisions list --service=custoking-ims-backend --region=us-central1

# Send 100 % traffic to a previous revision
gcloud run services update-traffic custoking-ims-backend \
  --to-revisions=<REVISION_NAME>=100 \
  --region=us-central1
```

### Cloud SQL connection

Cloud Run connects to Cloud SQL via the **Unix socket** (no VPC required for serverless):

```
SPRING_DATASOURCE_URL=jdbc:postgresql:///<DB_NAME>?cloudSqlInstance=<PROJECT>:<REGION>:<INSTANCE>&socketFactory=com.google.cloud.sql.postgres.SocketFactory
```

The Cloud Run service account must have the `roles/cloudsql.client` IAM role.

### Environment variable reference

| Variable | Required | Notes |
|----------|----------|-------|
| `SPRING_PROFILES_ACTIVE` | Yes | `prod` |
| `SPRING_DATASOURCE_URL` | Yes | Cloud SQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Yes | `ims_app` |
| `SPRING_DATASOURCE_PASSWORD` | Yes | Via Secret Manager |
| `FLYWAY_URL` | Yes | Same as datasource URL (or separate) |
| `FLYWAY_USERNAME` | Yes | DBA / migration user |
| `FLYWAY_PASSWORD` | Yes | Via Secret Manager |
| `APP_JWT_SECRET` | Yes | ≥ 32 chars; via Secret Manager |
| `APP_AADHAR_SECRET` | Yes | ≥ 16 chars; via Secret Manager |
| `SUPERADMIN_PASSWORD` | First deploy | Via Secret Manager |
| `APP_BOOTSTRAP_USERS` | First deploy only | `true` once, then `false` |
| `APP_COOKIE_SECURE` | Yes | `true` (HTTPS) |
| `APP_CORS_ALLOWED_ORIGINS` | Yes | Frontend domain(s) |
| `SWAGGER_ENABLED` | No | Default `false`; never `true` in prod |
| `DB_POOL_MAX` | No | Default 5 in prod profile |
| `PORT` | No | Injected by Cloud Run automatically |

See `backend/.env.example` for all variables with descriptions and default values.

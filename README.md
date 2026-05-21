# Custoking IMS

A React + Spring Boot + PostgreSQL multi-tenant school operations platform supporting RBAC, Supply OS, and Firefighting workflows.

## Architecture

- **Frontend:** React 19 + TypeScript + Vite
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
npm run build  # type-check + build
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

## GCP deployment
See `cloudbuild.yaml` for Cloud Build + Cloud Run deployment config.
Secrets are managed via Secret Manager; never commit secrets to git.

# Custoking IMS

A React + Spring Boot + PostgreSQL school operations platform with school-scoped admin access, Supply OS, and Firefighting workflows.

## What was validated in this package
- Frontend production build: `npm ci && npm run build`
- Catalog tab build fixes applied
- Repo cleaned for GitHub commit safety:
  - removed compiled `.class` files from source tree
  - added `.dockerignore` files
  - added GitHub Actions CI workflow
  - added Cloud Run deployment scaffold

## Local development
### Frontend
```bash
cd frontend
npm ci
npm run dev
```

### Full stack with Docker
```bash
docker compose up --build
```

Services:
- Frontend: `http://localhost`
- Backend: `http://localhost:8080`
- PostgreSQL: `localhost:5432`

## Production guidance
- Use `SPRING_PROFILES_ACTIVE=prod` for backend
- Use Cloud SQL for PostgreSQL or managed PostgreSQL instead of local Docker PostgreSQL
- Store DB password and `APP_AADHAR_SECRET` in Secret Manager
- Set `APP_BOOTSTRAP_USERS=false` in production
- Build frontend with `VITE_API_BASE_URL=https://<backend-url>/api`

## GitHub
After pushing this repo, GitHub Actions runs:
- frontend build verification
- Docker image builds for backend and frontend

## GCP
See `deploy/gcp/README.md` and `cloudbuild.yaml`.

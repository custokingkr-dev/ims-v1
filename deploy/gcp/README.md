# GCP deployment notes

This project deploys as two Cloud Run services:

- `custoking-backend`
- `custoking-frontend`

## Production shape

- Build images in Artifact Registry.
- Run PostgreSQL on Cloud SQL for PostgreSQL or another managed PostgreSQL instance.
- Store database password, Flyway password, JWT secret, and Aadhaar secret in Secret Manager.
- Run backend with `SPRING_PROFILES_ACTIVE=prod`.
- Keep `APP_BOOTSTRAP_USERS=false` after initial controlled setup.
- Build frontend with `VITE_API_BASE_URL=https://<backend-url>/api/v1`.
- For separate Cloud Run frontend/backend domains, run refresh cookies as `SameSite=None; Secure`.

## GitHub deploy workflow

Manual deploy is available through:

```text
.github/workflows/deploy.yml
```

The workflow authenticates with GCP Workload Identity and submits:

```bash
gcloud builds submit --config=cloudbuild.yaml --project=custoking-ims .
```

## Cloud Build substitutions

Update these in `cloudbuild.yaml` if service names, region, repository, or URLs change:

- `_REGION`
- `_AR_REPO`
- `_BACKEND_SERVICE`
- `_FRONTEND_SERVICE`
- `_BACKEND_PUBLIC_URL`
- `_FRONTEND_PUBLIC_URL`

## Required Secret Manager secrets

- `ims-app-password`
- `db-password`
- `aadhar-secret`
- `jwt-secret`

## Backend environment

Cloud Build deploys the backend with:

- `SPRING_PROFILES_ACTIVE=prod`
- `SPRING_DATASOURCE_URL=jdbc:postgresql://10.116.0.3:5432/custoking_ims_v1`
- `SPRING_DATASOURCE_USERNAME=ims_app`
- `APP_BOOTSTRAP_USERS=false`
- `APP_CORS_ALLOWED_ORIGINS=https://custoking-frontend-755376288593.asia-south2.run.app`
- `APP_COOKIE_SECURE=true`
- `APP_COOKIE_SAME_SITE=None`
- `APP_JWT_EXPIRATION_MS=900000`
- `APP_REFRESH_TOKEN_EXPIRATION_MS=604800000`

## Checks before deploy

```bash
docker build ./backend
docker build ./frontend --build-arg VITE_API_BASE_URL=https://custoking-backend-755376288593.asia-south2.run.app/api/v1
docker compose config
```

The GitHub CI workflow also validates that `.github/workflows/deploy.yml`, `cloudbuild.yaml`, and these key production deployment settings are present.

# GCP deployment notes

This project is set up for **two Cloud Run services**:
- `custoking-backend`
- `custoking-frontend`

## Recommended production shape
- Put PostgreSQL in **Cloud SQL for PostgreSQL**
- Store DB password and `APP_AADHAR_SECRET` in **Secret Manager**
- Run backend with `SPRING_PROFILES_ACTIVE=prod`
- Set `APP_BOOTSTRAP_USERS=false` in production
- Build frontend with `VITE_API_BASE_URL=https://<backend-url>/api`

## One-time setup
1. Create an Artifact Registry Docker repo.
2. Create Cloud SQL and database `custoking_ims_v1`.
3. Create secrets for DB password and Aadhaar secret.
4. Update `cloudbuild.yaml` substitutions before the first deploy.

## Example backend env vars for Cloud Run
- `SPRING_PROFILES_ACTIVE=prod`
- `SPRING_DATASOURCE_URL=jdbc:postgresql://<HOST>:5432/custoking_ims_v1`
- `SPRING_DATASOURCE_USERNAME=<db-user>`
- `SPRING_DATASOURCE_PASSWORD=<from-secret-manager>`
- `APP_AADHAR_SECRET=<from-secret-manager>`
- `APP_CORS_ALLOWED_ORIGINS=https://<frontend-url>`
- `APP_BOOTSTRAP_USERS=false`

## GitHub CI
`.github/workflows/ci.yml` builds:
- the frontend via `npm ci && npm run build`
- the backend and frontend Docker images

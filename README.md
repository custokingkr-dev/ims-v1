# Custoking IMS

A React + Spring Boot + PostgreSQL school operations platform with school-scoped admin access, Supply OS, and Firefighting workflows.

## Local Development

Use Tilt for the full local stack:

```bash
tilt up
```

Tilt uses `docker-compose.yml` and starts:

- Frontend: `http://localhost`
- Backend: `http://localhost:8080`
- Health check: `http://localhost:8080/actuator/health`
- PostgreSQL: `localhost:5432`

Default local logins:

- `superadmin@custoking.com` / `LocalSuperadmin@2026!`
- `admin@demo.custoking.com` / `LocalDemoAdmin@2026!`

Stop the stack:

```bash
tilt down
```

Reset the local database:

```bash
docker compose down -v
tilt up
```

## Direct Frontend Build

```bash
cd frontend
npm ci
npm run build
```

## Architecture Docs

- [Architecture hardening HLD](docs/HLD-architecture-hardening.md)
- [Architecture hardening changelog](docs/CHANGELOG-architecture-hardening.md)

## Notes

This repo includes local Tilt/Compose development, CI checks, and a manual GitHub Actions workflow for GCP Cloud Build / Cloud Run deployment.

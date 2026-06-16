# Custoking IMS Backend

Spring Boot API for the local Custoking IMS stack.

## Local Run

The supported local path is from the repo root:

```bash
tilt up
```

Tilt builds this service through `backend/Dockerfile` and connects it to the PostgreSQL service from `docker-compose.yml`.

## Default Login

- `superadmin@custoking.com` / `LocalSuperadmin@2026!`
- `admin@demo.custoking.com` / `LocalDemoAdmin@2026!`

## Direct Run

Direct backend execution requires Java 21, Maven, and a reachable PostgreSQL instance:

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
mvn spring-boot:run
```

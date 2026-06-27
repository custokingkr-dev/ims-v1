# Local Setup

This guide is for setting up the current split-service repository on a new laptop or on a machine that previously ran the old monolith.

## Prerequisites

- Git
- Docker Desktop with WSL2 on Windows
- Java 21
- Node 20+
- PowerShell 7 or Windows PowerShell 5.1
- Optional: Tilt

For Windows, keep Docker/WSL memory bounded with `%USERPROFILE%\.wslconfig`:

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

After changing `.wslconfig`, run:

```powershell
wsl --shutdown
```

## One-Shot Migration From Old Master

Use this when the laptop previously ran the monolith stack.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-from-master.ps1
```

The script:

- stops the previous compose stack,
- removes orphan containers such as `custoking-backend`,
- wipes the stale Postgres volume unless `-KeepData` is passed,
- checks out `microservices-boundary-foundation`,
- starts the chosen compose profile,
- waits for containers to become healthy.

Common options:

```powershell
# Lighter local runtime: identity, tenant-school, student, attendance, fee, frontend, gateway
powershell -ExecutionPolicy Bypass -File scripts\setup-from-master.ps1 -ComposeProfile core

# Keep existing database volume
powershell -ExecutionPolicy Bypass -File scripts\setup-from-master.ps1 -KeepData

# Auto-stash local edits before switching branch
powershell -ExecutionPolicy Bypass -File scripts\setup-from-master.ps1 -Force
```

macOS/Linux equivalent:

```bash
./scripts/setup-from-master.sh --profile core
./scripts/setup-from-master.sh --profile full
```

## Manual Docker Compose

Database only:

```powershell
docker compose up -d postgres
```

Core profile:

```powershell
docker compose --profile core up -d --build
```

Full profile:

```powershell
docker compose --profile full up -d --build
```

Local entry point:

```text
http://localhost
```

Service health ports:

| Service | URL |
| --- | --- |
| notification-service | `http://localhost:8081/actuator/health` |
| audit-service | `http://localhost:8082/actuator/health` |
| identity-service | `http://localhost:8083/actuator/health` |
| tenant-school-service | `http://localhost:8084/actuator/health` |
| student-service | `http://localhost:8085/actuator/health` |
| attendance-service | `http://localhost:8086/actuator/health` |
| fee-service | `http://localhost:8087/actuator/health` |
| catalog-service | `http://localhost:8088/actuator/health` |
| workflow-service | `http://localhost:8089/actuator/health` |
| firefighting-service | `http://localhost:8090/actuator/health` |
| reporting-service | `http://localhost:8091/actuator/health` |
| billing-service | `http://localhost:8092/actuator/health` |

## Tilt

Tilt uses `docker-compose.yml` and supports the same profiles.

```powershell
# Full profile
tilt up

# Low-memory core profile
$env:TILT_COMPOSE_PROFILE='core'
tilt up
```

On macOS/Linux:

```bash
TILT_COMPOSE_PROFILE=core tilt up
```

If a service list looks wrong, validate compose first:

```powershell
docker compose --profile core config --quiet
docker compose --profile full config --quiet
```

## Verification

Static guardrails:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-compose-profiles.ps1
```

Full runtime smoke after the full stack is healthy:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-microservice-features.ps1 -GatewayBaseUrl http://localhost
```

The smoke script provisions its own local E2E users:

- `e2e-superadmin@local.test`
- `e2e-admin@local.test`
- password: `password`

## Stop And Reclaim Memory

```powershell
docker compose --profile full stop
wsl --shutdown
```

To remove containers and volumes:

```powershell
docker compose --profile full down -v --remove-orphans
```

## Current Local Services

There is no `backend/` service or `custoking-backend` container in the current split-service stack. Browser traffic goes to:

```text
frontend -> api-gateway -> domain services -> postgres
```

Use `services/<service-name>/` for Java/Node service code and `frontend/` for the React app.

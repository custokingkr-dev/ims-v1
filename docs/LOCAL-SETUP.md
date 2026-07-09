# Local Setup

Last verified: 2026-07-09.

This guide covers running the current compact split-service repository locally and setting it up on a new developer laptop.

## Current Local Topology

Local Docker Compose runs these services:

| Compose service | Host URL |
| --- | --- |
| `postgres` | `localhost:5432` |
| `identity-service` | `http://localhost:8083/actuator/health` |
| `school-core-service` | `http://localhost:8084/actuator/health` |
| `operations-service` | `http://localhost:8089/actuator/health` |
| `platform-service` | `http://localhost:8091/actuator/health` |
| `billing-service` | `http://localhost:8092/actuator/health` |
| `api-gateway` | `http://localhost/gateway-health` |
| `frontend` | proxied through `http://localhost` |

There are no standalone local `tenant-school-service`, `student-service`, `attendance-service`, `fee-service`, `catalog-service`, `workflow-service`, `firefighting-service`, `reporting-service`, `notification-service`, or `audit-service` containers. Those domains are merged locally into:

- `school-core-service`: tenant school, student, attendance, fee, catalog
- `operations-service`: workflow, urgent procurement
- `platform-service`: reporting, notification, audit
- `billing-service`: billing

## Prerequisites

Install:

- Git
- Docker Desktop with WSL2 enabled on Windows
- JDK 25 or newer
- Node.js 20 or newer
- PowerShell 7 or Windows PowerShell 5.1
- Optional: Tilt
- Optional for deployment work only: Google Cloud CLI, Terraform

Recommended Windows WSL config at `%USERPROFILE%\.wslconfig`:

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

After editing `.wslconfig`:

```powershell
wsl --shutdown
```

## Windows Runtime Storage

Use `D:\Projects\Runtime` for machine-local runtime data so Docker, WSL, and IDE
caches do not consume the system drive. Run the repo script after cloning the
repository. If the laptop does not have a D: drive, pass `-RuntimeRoot "<path>"`
to the script and use the matching path in Docker Desktop.

Create the runtime layout and configure future IntelliJ IDEA launches:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\configure-windows-runtime.ps1
```

Default layout:

| Runtime data | Path |
| --- | --- |
| Docker Desktop disk target | `D:\Projects\Runtime\Docker` |
| WSL distro target | `D:\Projects\Runtime\WSL` |
| IntelliJ IDEA config/cache/log target | `D:\Projects\Runtime\JetBrains\IntelliJIdea2026.1` |

The script writes `D:\Projects\Runtime\JetBrains\IntelliJIdea2026.1\idea.properties`
and sets the user-level `IDEA_PROPERTIES` environment variable. Restart IntelliJ
IDEA from a fresh Windows session or terminal after running it.

Close IntelliJ IDEA before copying existing settings and caches:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\configure-windows-runtime.ps1 -MigrateIntelliJ
```

Docker Desktop's supported path is **Settings -> Resources -> Advanced -> Disk
image location**. Set it to:

```text
D:\Projects\Runtime\Docker
```

If the Docker Desktop UI does not expose that setting, shut Docker Desktop down
and use the guarded script move for the large Docker data disk. The fallback
script requires Docker Desktop to have created `%LOCALAPPDATA%\Docker\wsl\disk`
at least once:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\configure-windows-runtime.ps1 -MoveDockerDisk
```

`-MoveDockerDisk` moves `%LOCALAPPDATA%\Docker\wsl\disk` to
`D:\Projects\Runtime\Docker\wsl\disk` and leaves a directory junction at the old
path for Docker Desktop. Do not rely on moving Docker Desktop's small
`docker-desktop` WSL main distro; Docker Desktop manages it and may recreate it
under `%LOCALAPPDATA%` at startup.

For normal WSL distributions, install or move them under the same runtime root:

```powershell
wsl --install Ubuntu --location D:\Projects\Runtime\WSL\Ubuntu
wsl --manage Ubuntu --move D:\Projects\Runtime\WSL\Ubuntu
wsl --manage Ubuntu --set-sparse true
```

If WSL rejects `--set-sparse true`, leave it disabled rather than forcing
`--allow-unsafe`.

## New Laptop Setup

1. Clone the repo and enter it:

```powershell
git clone <repo-url> ims-v1
cd ims-v1
git checkout main
```

2. Run the local bootstrap check:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-local-dev.ps1
```

This verifies Git, Docker, Node, npm, and JDK 25+, creates `.env` from `.env.example` if needed, installs frontend and gateway dependencies, and validates the core compose profile.

3. Start the lightweight local app stack:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-local-dev.ps1 -ComposeProfile core -SkipDependencyInstall
```

When a compose profile is started, the setup script also runs `ensure-app-rt-local.ps1`
after migrations so the local runtime DB role has schema/table/sequence grants.

For a laptop that already has stale Custoking containers or an old local database volume,
reset the local compose state first:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-local-dev.ps1 -ComposeProfile core -SkipDependencyInstall -ResetData -RemoveOrphans
```

`-ResetData` removes the local `postgres_data` compose volume. Use it only for disposable
local development data.

Open:

```text
http://localhost
```

4. Seed local users after the services are healthy:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ensure-local-dev-users.ps1
```

Local credentials created by that script:

| Role | Email | Password |
| --- | --- | --- |
| SUPERADMIN | `local-superadmin@custoking.local` | `password` |
| ZONE_ADMIN | `local-zone-admin@custoking.local` | `password` |
| ADMIN | `local-admin@custoking.local` | `password` |
| SCHOOL_ADMIN | `local-school-admin@custoking.local` | `password` |
| OPERATIONS | `local-operations@custoking.local` | `password` |
| ACCOUNTANT | `local-accountant@custoking.local` | `password` |
| TEACHER | `local-teacher@custoking.local` | `password` |
| VIEWER | `local-viewer@custoking.local` | `password` |

## Compose Profiles

Database only:

```powershell
docker compose up -d postgres
```

Core app stack:

```powershell
docker compose --profile core up -d --build
```

Full local stack:

```powershell
docker compose --profile full up -d --build
```

Profile contents:

| Profile | Services |
| --- | --- |
| `core` | Postgres, identity, school-core, frontend, gateway |
| `full` | Core plus operations, platform, billing |

Local compose disables OTLP trace export by default because no collector is part of the
local stack. Dev/prod Cloud Run deployments configure real OTLP export separately.

Validate compose without starting containers:

```powershell
docker compose --profile core config --quiet
docker compose --profile full config --quiet
```

## Local Tests

Frontend:

```powershell
cd frontend
npm ci
npm test
npm run build
```

API gateway:

```powershell
cd services\api-gateway
npm ci
npm test
```

All service tests through the repo wrapper:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\invoke-microservice-tests.ps1
```

One service:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\invoke-microservice-tests.ps1 -Services identity-service
```

The test runner detects JDK 25+ and uses the root Maven wrapper. It does not depend on `mvn` being installed globally.

Manual Maven example:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\identity-service\pom.xml test
```

## Runtime Smoke

After the full profile is healthy:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 -RunDbAudit -RunSmoke
powershell -ExecutionPolicy Bypass -File scripts\smoke-microservice-features.ps1 -GatewayBaseUrl http://localhost
```

## Tilt

Tilt uses the same compose profiles:

```powershell
tilt up
```

For the lighter stack:

```powershell
$env:TILT_COMPOSE_PROFILE='core'
tilt up
```

Tilt runs `local-runtime-grants` first, then `local-dev-users`, so the local runtime DB
role and seed accounts are refreshed automatically.

## Migrating A Laptop From The Old Monolith

Use this only if the machine previously ran the old monolith stack:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-from-master.ps1 -ComposeProfile core
```

The script tears down the old compose stack, removes orphan containers such as `custoking-backend`, wipes the stale Postgres volume unless `-KeepData` is passed, checks out `main`, and starts the selected profile.

## Stop And Reclaim Memory

Stop containers:

```powershell
docker compose --profile full stop
```

Remove containers and local database volume:

```powershell
docker compose --profile full down -v --remove-orphans
```

Reclaim WSL memory:

```powershell
wsl --shutdown
```

## Common Failures

`release version 25 not supported`:

- Your Maven run is using an older JDK.
- Install JDK 25 and set `JAVA_HOME`, or use `scripts\invoke-microservice-tests.ps1`, which resolves JDK 25 automatically.

Port `80` already in use:

- Stop the process using port 80, or change the gateway port mapping in `docker-compose.yml` for local use.

Docker build is slow:

- Start with `-ComposeProfile core`.
- Use `-SkipDockerBuild` only after images are already built and source changes do not require rebuilding containers.

`school-core-service` fails Flyway with `column ad.late_count does not exist`:

- The local Postgres volume is from an older schema state.
- Reset disposable local data with `scripts\setup-local-dev.ps1 -ComposeProfile core -SkipDependencyInstall -ResetData -RemoveOrphans`.

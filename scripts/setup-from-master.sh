#!/usr/bin/env bash
#
# setup-from-master.sh
#
# One-shot setup that migrates a machine from the old monolith `master` stack to the
# split-service `microservices-boundary-foundation` branch and brings the local stack up.
#
# Designed for a machine previously running the monolith (postgres + backend + frontend
# on ports 5432/8080/80). It:
#   1. Verifies docker + git are available.
#   2. Tears down the old compose stack and removes orphan containers (e.g. custoking-backend).
#   3. Optionally wipes the stale Postgres volume (the monolith `public` schema is junk here).
#   4. Checks out and pulls the target branch.
#   5. Builds and starts the split-service stack for the chosen compose profile.
#   6. Waits for every container to report healthy and prints the local URLs.
#
# Usage:
#   ./scripts/setup-from-master.sh                       # full profile, fresh DB volume
#   ./scripts/setup-from-master.sh --profile core --keep-data --force
#
set -euo pipefail

BRANCH="microservices-boundary-foundation"
PROFILE="full"
KEEP_DATA=0
FORCE=0
SKIP_BUILD=0
HEALTH_TIMEOUT=300

usage() {
    grep '^#' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --branch)           BRANCH="$2"; shift 2 ;;
        --profile)          PROFILE="$2"; shift 2 ;;
        --keep-data)        KEEP_DATA=1; shift ;;
        --force)            FORCE=1; shift ;;
        --skip-build)       SKIP_BUILD=1; shift ;;
        --health-timeout)   HEALTH_TIMEOUT="$2"; shift 2 ;;
        -h|--help)          usage 0 ;;
        *) echo "Unknown option: $1" >&2; usage 1 ;;
    esac
done

if [ "$PROFILE" != "core" ] && [ "$PROFILE" != "full" ]; then
    echo "Invalid --profile '$PROFILE' (expected: core | full)" >&2
    exit 1
fi

# Resolve repo root (script lives in <repo>/scripts).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

step() { printf '\n==> %s\n' "$1"; }

# Containers and the host port they expose (empty for postgres / gateway is 80).
FULL_CONTAINERS=(
    "custoking-postgres:"
    "custoking-identity-service:8083"
    "custoking-tenant-school-service:8084"
    "custoking-student-service:8085"
    "custoking-attendance-service:8086"
    "custoking-fee-service:8087"
    "custoking-catalog-service:8088"
    "custoking-workflow-service:8089"
    "custoking-firefighting-service:8090"
    "custoking-reporting-service:8091"
    "custoking-billing-service:8092"
    "custoking-notification-service:8081"
    "custoking-audit-service:8082"
    "custoking-api-gateway:80"
)
CORE_CONTAINERS=(
    "custoking-postgres:"
    "custoking-identity-service:8083"
    "custoking-tenant-school-service:8084"
    "custoking-student-service:8085"
    "custoking-attendance-service:8086"
    "custoking-fee-service:8087"
    "custoking-api-gateway:80"
)

# --- 1. Preconditions -------------------------------------------------------
step "Checking prerequisites"
command -v docker >/dev/null 2>&1 || { echo "Required command 'docker' not found on PATH." >&2; exit 1; }
command -v git    >/dev/null 2>&1 || { echo "Required command 'git' not found on PATH." >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker daemon is not reachable. Start Docker and retry." >&2; exit 1; }
echo "docker and git are available."

# --- 2. Tear down the old stack --------------------------------------------
step "Tearing down the previous compose stack (and orphan containers)"
if [ "$KEEP_DATA" -eq 1 ]; then
    echo "Keeping the existing Postgres volume (--keep-data)."
    docker compose down --remove-orphans
else
    echo "Stale Postgres volume will be wiped (-v). Pass --keep-data to keep it."
    docker compose down --remove-orphans -v
fi
# Belt-and-suspenders: drop any lingering monolith container not owned by this compose project.
if [ -n "$(docker ps -aq --filter 'name=^/custoking-backend$')" ]; then
    echo "Removing leftover monolith container 'custoking-backend'."
    docker rm -f custoking-backend >/dev/null
fi

# --- 3. Switch to the target branch ----------------------------------------
step "Switching to branch '$BRANCH'"
if [ -n "$(git status --porcelain)" ]; then
    if [ "$FORCE" -eq 1 ]; then
        stamp="$(date +%Y%m%d-%H%M%S)"
        echo "Uncommitted changes found; stashing them (setup-from-master $stamp)."
        git stash push -u -m "setup-from-master $stamp" >/dev/null
    else
        echo "Working tree has uncommitted changes. Commit/stash them, or re-run with --force to auto-stash." >&2
        exit 1
    fi
fi
git fetch origin
git checkout "$BRANCH"
git pull --ff-only

# --- 4. Bring up the split-service stack ------------------------------------
step "Starting the '$PROFILE' profile stack"
if [ "$SKIP_BUILD" -eq 1 ]; then
    docker compose --profile "$PROFILE" up -d
else
    docker compose --profile "$PROFILE" up -d --build
fi

# --- 5. Wait for health -----------------------------------------------------
step "Waiting for containers to become healthy (timeout ${HEALTH_TIMEOUT}s)"
if [ "$PROFILE" = "core" ]; then
    targets=("${CORE_CONTAINERS[@]}")
else
    targets=("${FULL_CONTAINERS[@]}")
fi

pending=()
for entry in "${targets[@]}"; do pending+=("${entry%%:*}"); done

deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
while [ "${#pending[@]}" -gt 0 ] && [ "$(date +%s)" -lt "$deadline" ]; do
    still=()
    for name in "${pending[@]}"; do
        status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$name" 2>/dev/null || true)"
        if [ "$status" = "healthy" ] || [ "$status" = "running" ]; then
            printf '  %-34s %s\n' "$name" "$status"
        else
            still+=("$name")
        fi
    done
    pending=("${still[@]}")
    [ "${#pending[@]}" -gt 0 ] && sleep 5
done

if [ "${#pending[@]}" -gt 0 ]; then
    echo "" >&2
    echo "These containers did not become healthy in time:" >&2
    for name in "${pending[@]}"; do echo "  $name" >&2; done
    echo "" >&2
    echo "Recent logs:" >&2
    docker compose --profile "$PROFILE" logs --tail=80
    exit 1
fi

# --- 6. Summary -------------------------------------------------------------
step "Stack is up"
echo "Frontend / gateway : http://localhost"
if [ "$PROFILE" = "full" ]; then
    echo "Per-service health endpoints:"
    for entry in "${FULL_CONTAINERS[@]}"; do
        name="${entry%%:*}"; port="${entry##*:}"
        if [ -n "$port" ] && [ "$port" != "80" ]; then
            printf '  %-34s http://localhost:%s/actuator/health\n' "$name" "$port"
        fi
    done
fi
echo ""
echo "Next: run the migration/feature smoke if you want to verify end to end:"
echo "  powershell -ExecutionPolicy Bypass -File scripts/verify-microservice-migration.ps1 -RunDbAudit -RunSmoke"

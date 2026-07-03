<#
.SYNOPSIS
    One-shot setup that migrates a laptop from the old monolith `master` stack to the
    split-service `microservices-boundary-foundation` branch and brings the local stack up.

.DESCRIPTION
    Designed for a machine that was previously running the monolith (postgres + backend +
    frontend on ports 5432/8080/80). It:
      1. Verifies docker + git are available.
      2. Tears down the old compose stack and removes orphan containers (e.g. custoking-backend).
      3. Optionally wipes the stale Postgres volume (the monolith `public` schema is junk here).
      4. Checks out and pulls the target branch.
      5. Builds and starts the split-service stack for the chosen compose profile.
      6. Waits for every container to report healthy and prints the local URLs.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\setup-from-master.ps1

.EXAMPLE
    # Keep the existing DB volume and only bring up the lighter core profile
    powershell -ExecutionPolicy Bypass -File scripts\setup-from-master.ps1 -ComposeProfile core -KeepData
#>
param(
    [string]$Branch = "microservices-boundary-foundation",
    [ValidateSet("core", "full")]
    [string]$ComposeProfile = "full",
    # By default the stale monolith Postgres volume is wiped. Pass -KeepData to retain it.
    [switch]$KeepData,
    # Stash/discard uncommitted changes so the branch checkout cannot fail.
    [switch]$Force,
    # Skip the image rebuild (only use when images already match the branch).
    [switch]$SkipBuild,
    [int]$HealthTimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Require-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found on PATH."
    }
}

# Containers in the full profile and the host ports they expose (for the summary + waits).
$serviceHealth = [ordered]@{
    "custoking-postgres"               = $null
    "custoking-identity-service"       = 8083
        "custoking-school-core-service"   = 8084
    "custoking-operations-service"     = 8089
        "custoking-platform-service"     = 8091
    "custoking-billing-service"        = 8092
    "custoking-api-gateway"            = 80
}
# core profile is a subset.
$coreContainers = @(
    "custoking-postgres",
    "custoking-identity-service",
    "custoking-api-gateway"
)

# --- 1. Preconditions -------------------------------------------------------
Write-Step "Checking prerequisites"
Require-Command docker
Require-Command git
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw "Docker daemon is not reachable. Start Docker Desktop and retry." }
Write-Host "docker and git are available." -ForegroundColor Green

# --- 2. Tear down the old stack --------------------------------------------
Write-Step "Tearing down the previous compose stack (and orphan containers)"
$downArgs = @("compose", "down", "--remove-orphans")
if (-not $KeepData) {
    $downArgs += "-v"
    Write-Host "Stale Postgres volume will be wiped (-v). Pass -KeepData to keep it." -ForegroundColor Yellow
} else {
    Write-Host "Keeping the existing Postgres volume (-KeepData)." -ForegroundColor Yellow
}
& docker @downArgs
# Belt-and-suspenders: drop any lingering monolith container not owned by this compose project.
foreach ($leftover in @("custoking-backend")) {
    $exists = docker ps -aq --filter "name=^/$leftover$"
    if ($exists) {
        Write-Host "Removing leftover monolith container '$leftover'." -ForegroundColor Yellow
        docker rm -f $leftover *> $null
    }
}

# --- 3. Switch to the target branch ----------------------------------------
Write-Step "Switching to branch '$Branch'"
$dirty = git status --porcelain
if ($dirty) {
    if ($Force) {
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        Write-Host "Uncommitted changes found; stashing them (setup-from-master $stamp)." -ForegroundColor Yellow
        git stash push -u -m "setup-from-master $stamp" | Out-Null
    } else {
        throw "Working tree has uncommitted changes. Commit/stash them, or re-run with -Force to auto-stash."
    }
}
git fetch origin
git checkout $Branch
# Fast-forward to the remote tip if a tracking branch exists.
git pull --ff-only

# --- 4. Bring up the split-service stack ------------------------------------
Write-Step "Starting the '$ComposeProfile' profile stack"
$upArgs = @("compose", "--profile", $ComposeProfile, "up", "-d")
if (-not $SkipBuild) { $upArgs += "--build" }
& docker @upArgs
if ($LASTEXITCODE -ne 0) { throw "docker compose up failed. Check the build/output above." }

# --- 5. Wait for health -----------------------------------------------------
Write-Step "Waiting for containers to become healthy (timeout ${HealthTimeoutSeconds}s)"
$targets = if ($ComposeProfile -eq "core") { $coreContainers } else { @($serviceHealth.Keys) }
$deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
$pending = [System.Collections.Generic.List[string]]::new()
$targets | ForEach-Object { $pending.Add($_) }

while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
    foreach ($name in @($pending)) {
        $status = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $name 2>$null
        if ($status -eq "healthy" -or $status -eq "running") {
            Write-Host ("  {0,-34} {1}" -f $name, $status) -ForegroundColor Green
            $pending.Remove($name) | Out-Null
        }
    }
    if ($pending.Count -gt 0) { Start-Sleep -Seconds 5 }
}

if ($pending.Count -gt 0) {
    Write-Host "`nThese containers did not become healthy in time:" -ForegroundColor Red
    $pending | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Write-Host "`nRecent logs:" -ForegroundColor Red
    docker compose --profile $ComposeProfile logs --tail=80
    throw "Stack did not come up cleanly. See logs above."
}

# --- 6. Summary -------------------------------------------------------------
Write-Step "Stack is up"
Write-Host "Frontend / gateway : http://localhost" -ForegroundColor Green
if ($ComposeProfile -eq "full") {
    Write-Host "Per-service health endpoints:" -ForegroundColor Green
    foreach ($name in $serviceHealth.Keys) {
        $port = $serviceHealth[$name]
        if ($port -and $port -ne 80) {
            Write-Host ("  {0,-34} http://localhost:{1}/actuator/health" -f $name, $port)
        }
    }
}
Write-Host "`nNext: run the migration/feature smoke if you want to verify end to end:" -ForegroundColor Cyan
Write-Host "  powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 -RunDbAudit -RunSmoke"

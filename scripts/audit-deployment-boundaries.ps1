param(
  [string]$WorkflowFile = ".github/workflows/build-release.yml",
  [string]$ComposeFile = "docker-compose.yml",
  [string]$GatewayFile = "services/api-gateway/server.js",
  [string]$CloudRunDirectory = "deploy/cloudrun"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")

function Read-RequiredFile([string]$Path) {
  $resolved = Join-Path $repoRoot $Path
  if (-not (Test-Path -LiteralPath $resolved)) {
    throw "Required file not found: $Path"
  }
  return Get-Content -Raw -Path $resolved
}

$workflow = Read-RequiredFile $WorkflowFile
$compose = Read-RequiredFile $ComposeFile
$gateway = Read-RequiredFile $GatewayFile
$directRelease = Read-RequiredFile "scripts/invoke-direct-cloudrun-release.ps1"
$releaseVerification = Read-RequiredFile "scripts/verify-cloudrun-release.ps1"
$violations = New-Object System.Collections.Generic.List[string]
$catalog = @(Get-MicroserviceBuildCatalog)

$activeDeploymentText = "$workflow`n$compose`n$gateway"
foreach ($manifest in Get-ChildItem (Join-Path $repoRoot $CloudRunDirectory) -Filter "*.yaml" -File) {
  $activeDeploymentText += "`n" + (Get-Content -Raw -Path $manifest.FullName)
}

foreach ($retired in @("custoking-backend", "BACKEND_UPSTREAM", "./backend", "backend:")) {
  if ($activeDeploymentText.Contains($retired)) {
    $violations.Add("Retired backend deployment reference still present: $retired")
  }
}

foreach ($service in $catalog) {
  $manifestPath = Join-Path $repoRoot "$CloudRunDirectory/$($service.Name).yaml"
  if (-not (Test-Path -LiteralPath $manifestPath)) {
    $violations.Add("Cloud Run manifest missing for $($service.Name): $manifestPath")
    continue
  }
  $manifest = Get-Content -Raw -Path $manifestPath
  foreach ($required in @("custoking-$($service.Name)-dev", $service.Image)) {
    if (-not $manifest.Contains($required)) {
      $violations.Add("Cloud Run manifest for $($service.Name) is missing: $required")
    }
  }
  if (-not $compose.Contains($service.Name)) {
    $violations.Add("docker-compose.yml is missing service: $($service.Name)")
  }

  $dockerfilePath = Join-Path $repoRoot "$($service.Context)/Dockerfile"
  $dockerfile = Get-Content -Raw -Path $dockerfilePath
  foreach ($fromLine in @($dockerfile -split "`r?`n" | Where-Object { $_ -match "^FROM\s" })) {
    if ($fromLine -notmatch "@sha256:[0-9a-f]{64}") {
      $violations.Add("Docker base image is not pinned by digest for $($service.Name): $fromLine")
    }
  }
}

foreach ($required in @(
  "needs.detect.outputs.docker_matrix",
  "max-parallel: 4",
  "cache-from: type=gha",
  "cache-to: type=gha",
  "resolve-image-source-id.ps1",
  "dev-approved-",
  "invoke-direct-cloudrun-release.ps1",
  "invoke-clouddeploy-release.ps1",
  "verify-cloudrun-release.ps1",
  "smoke-gateway-health.ps1",
  "group: cd-environment-",
  "cancel-in-progress:")) {
  if (-not $workflow.Contains($required)) {
    $violations.Add("Release workflow missing required deployment control: $required")
  }
}

foreach ($required in @("--async", 'status = "submitted"')) {
  if (-not $directRelease.Contains($required)) {
    $violations.Add("Direct dev release is missing asynchronous deployment control: $required")
  }
}

foreach ($required in @("runtimeRef", "TimeoutMinutes", "latestTraffic", "update-traffic", "--to-latest")) {
  if (-not $releaseVerification.Contains($required)) {
    $violations.Add("Cloud Run release verification is missing bounded runtime-digest validation: $required")
  }
}

foreach ($required in @(
  "IDENTITY_UPSTREAM",
  "TENANT_SCHOOL_UPSTREAM",
  "STUDENT_UPSTREAM",
  "ATTENDANCE_UPSTREAM",
  "FEE_UPSTREAM",
  "CATALOG_UPSTREAM",
  "WORKFLOW_UPSTREAM",
  "FIREFIGHTING_UPSTREAM",
  "REPORTING_UPSTREAM",
  "BILLING_UPSTREAM",
  "AUDIT_UPSTREAM",
  "NOTIFICATION_UPSTREAM",
  "IDENTITY_SERVICE_TOKEN",
  "X-Request-ID",
  "traceparent")) {
  if (-not $gateway.Contains($required)) {
    $violations.Add("API gateway missing routing/security value: $required")
  }
}

$frontendDockerfile = Get-Content -Raw -Path (Join-Path $repoRoot "frontend/Dockerfile")
$npmInstallIndex = $frontendDockerfile.IndexOf("RUN npm ci")
$sourceCopyIndex = $frontendDockerfile.IndexOf("COPY src ./src")
if ($npmInstallIndex -lt 0 -or $sourceCopyIndex -lt 0 -or $npmInstallIndex -gt $sourceCopyIndex) {
  $violations.Add("Frontend Dockerfile must install dependencies before copying application source.")
}

if ($violations.Count -gt 0) {
  Write-Host "Deployment boundary violations found:"
  $violations | ForEach-Object { Write-Host "  $_" }
  exit 1
}

Write-Host "Deployment boundary audit passed: affected-service promotion, pinned images, Cloud Run manifests, and gateway routes are guarded."

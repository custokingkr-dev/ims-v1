param(
    [string]$PreflightScript = "scripts/invoke-real-environment-readiness-preflight.ps1",
    [string]$BundleScript = "scripts/invoke-production-readiness-bundle.ps1",
    [string]$CompletionPlan = "docs/MICROSERVICES-COMPLETION-PLAN.md",
    [string]$DeployReadme = "deploy/gcp/README.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$preflightPath = Join-Path $repoRoot $PreflightScript
$bundlePath = Join-Path $repoRoot $BundleScript
$completionPath = Join-Path $repoRoot $CompletionPlan
$deployReadmePath = Join-Path $repoRoot $DeployReadme

foreach ($path in @($preflightPath, $bundlePath, $completionPath, $deployReadmePath)) {
    if (-not (Test-Path $path)) {
        throw "Required real environment readiness file not found: $path"
    }
}

$preflight = Get-Content -Raw -Path $preflightPath
$completion = Get-Content -Raw -Path $completionPath
$deployReadme = Get-Content -Raw -Path $deployReadmePath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @(
    "real-environment-readiness-preflight.json",
    "real-environment-readiness-preflight.md",
    "run services list",
    "secrets list",
    "builds list",
    "DeploymentSmokeJson",
    "LegacyCompatibilityJson",
    "IMS_SMOKE_SUPERADMIN_TOKEN",
    "IMS_SMOKE_ADMIN_TOKEN",
    "readyForRealBundle")) {
    if (-not $preflight.Contains($required)) {
        $violations.Add("Real environment preflight missing required behavior: $required")
    }
}

foreach ($requiredDoc in @(
    "scripts\invoke-real-environment-readiness-preflight.ps1",
    "real-environment-readiness-preflight.json",
    "real-environment-readiness-preflight.md",
    "scripts\invoke-production-readiness-bundle.ps1")) {
    if (-not $completion.Contains($requiredDoc)) {
        $violations.Add("Completion plan missing real environment preflight documentation: $requiredDoc")
    }
    if (-not $deployReadme.Contains($requiredDoc)) {
        $violations.Add("GCP README missing real environment preflight documentation: $requiredDoc")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Real environment readiness preflight audit violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Real environment readiness preflight audit passed: real GCP prerequisites are checked before bundle execution."

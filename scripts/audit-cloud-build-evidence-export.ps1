param(
    [string]$ExporterScript = "scripts/export-cloud-build-evidence.ps1",
    [string]$PreflightScript = "scripts/invoke-promotion-preflight.ps1",
    [string]$CompletionPlan = "docs/MICROSERVICES-COMPLETION-PLAN.md",
    [string]$DeployReadme = "deploy/gcp/README.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$exporterPath = Join-Path $repoRoot $ExporterScript
$preflightPath = Join-Path $repoRoot $PreflightScript
$completionPath = Join-Path $repoRoot $CompletionPlan
$deployReadmePath = Join-Path $repoRoot $DeployReadme

foreach ($path in @($exporterPath, $preflightPath, $completionPath, $deployReadmePath)) {
    if (-not (Test-Path $path)) {
        throw "Required Cloud Build evidence file not found: $path"
    }
}

$exporter = Get-Content -Raw -Path $exporterPath
$preflight = Get-Content -Raw -Path $preflightPath
$completion = Get-Content -Raw -Path $completionPath
$deployReadme = Get-Content -Raw -Path $deployReadmePath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @(
    "builds describe",
    "builds list",
    "cloud-build-evidence.json",
    "_COMMIT_SHA",
    "status",
    "images",
    "Mock")) {
    if (-not $exporter.Contains($required)) {
        $violations.Add("Cloud Build evidence exporter missing required behavior: $required")
    }
}

foreach ($required in @(
    "CloudBuildJson",
    "cloud build evidence artifact",
    "cloud build status",
    "cloud build images")) {
    if (-not $preflight.Contains($required)) {
        $violations.Add("Promotion preflight missing Cloud Build evidence validation: $required")
    }
}

foreach ($requiredDoc in @(
    "scripts\export-cloud-build-evidence.ps1",
    "cloud-build-evidence.json",
    "-CloudBuildJson cloud-build-evidence.json")) {
    if (-not $completion.Contains($requiredDoc)) {
        $violations.Add("Completion plan missing Cloud Build evidence documentation: $requiredDoc")
    }
    if (-not $deployReadme.Contains($requiredDoc)) {
        $violations.Add("GCP README missing Cloud Build evidence documentation: $requiredDoc")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Cloud Build evidence export audit violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Cloud Build evidence export audit passed: exporter, preflight, and docs cover build evidence."

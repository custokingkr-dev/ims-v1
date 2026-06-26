param(
    [string]$ExporterScript = "scripts/export-cloud-run-revisions.ps1",
    [string]$CompletionPlan = "docs/MICROSERVICES-COMPLETION-PLAN.md",
    [string]$DeployReadme = "deploy/gcp/README.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$exporterPath = Join-Path $repoRoot $ExporterScript
$completionPath = Join-Path $repoRoot $CompletionPlan
$deployReadmePath = Join-Path $repoRoot $DeployReadme

foreach ($path in @($exporterPath, $completionPath, $deployReadmePath)) {
    if (-not (Test-Path $path)) {
        throw "Required revision export file not found: $path"
    }
}

$exporter = Get-Content -Raw -Path $exporterPath
$completion = Get-Content -Raw -Path $completionPath
$deployReadme = Get-Content -Raw -Path $deployReadmePath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @(
    "Get-MicroserviceBuildCatalog",
    "run services describe",
    "run revisions list",
    "cloud-run-revisions.json",
    "Mock",
    "latestReadyRevision",
    "traffic",
    "revisions",
    "generatedAtUtc")) {
    if (-not $exporter.Contains($required)) {
        $violations.Add("Cloud Run revision exporter missing required behavior: $required")
    }
}

foreach ($requiredDoc in @(
    "scripts\export-cloud-run-revisions.ps1",
    "cloud-run-revisions.json",
    "scripts\invoke-promotion-preflight.ps1")) {
    if (-not $completion.Contains($requiredDoc)) {
        $violations.Add("Completion plan missing revision export documentation: $requiredDoc")
    }
    if (-not $deployReadme.Contains($requiredDoc)) {
        $violations.Add("GCP README missing revision export documentation: $requiredDoc")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Cloud Run revision export audit violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Cloud Run revision export audit passed: exporter and docs produce the promotion preflight revision artifact."

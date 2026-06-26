param(
    [string]$ExporterScript = "scripts/export-image-digests.ps1",
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
        throw "Required image digest export file not found: $path"
    }
}

$exporter = Get-Content -Raw -Path $exporterPath
$preflight = Get-Content -Raw -Path $preflightPath
$completion = Get-Content -Raw -Path $completionPath
$deployReadme = Get-Content -Raw -Path $deployReadmePath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @(
    "Get-MicroserviceBuildCatalog",
    "artifacts docker images describe",
    "image-digests.json",
    "immutableRef",
    "sha256:",
    "Mock")) {
    if (-not $exporter.Contains($required)) {
        $violations.Add("Image digest exporter missing required behavior: $required")
    }
}

foreach ($required in @(
    "ImageDigestJson",
    "image digest artifact",
    "image digest service count",
    "image digest values")) {
    if (-not $preflight.Contains($required)) {
        $violations.Add("Promotion preflight missing image digest artifact validation: $required")
    }
}

foreach ($requiredDoc in @(
    "scripts\export-image-digests.ps1",
    "image-digests.json",
    "-ImageDigestJson image-digests.json")) {
    if (-not $completion.Contains($requiredDoc)) {
        $violations.Add("Completion plan missing image digest export documentation: $requiredDoc")
    }
    if (-not $deployReadme.Contains($requiredDoc)) {
        $violations.Add("GCP README missing image digest export documentation: $requiredDoc")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Image digest export audit violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Image digest export audit passed: exporter, preflight, and docs cover immutable image evidence."

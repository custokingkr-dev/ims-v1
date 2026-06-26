param(
    [string]$CatalogScript = "scripts/secret-manager-catalog.ps1",
    [string]$ExporterScript = "scripts/export-secret-manager-evidence.ps1",
    [string]$PreflightScript = "scripts/invoke-promotion-preflight.ps1",
    [string]$CompletionPlan = "docs/MICROSERVICES-COMPLETION-PLAN.md",
    [string]$DeployReadme = "deploy/gcp/README.md",
    [string]$ObservabilityRunbook = "docs/MICROSERVICE-OBSERVABILITY-RUNBOOK.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$catalogPath = Join-Path $repoRoot $CatalogScript
$exporterPath = Join-Path $repoRoot $ExporterScript
$preflightPath = Join-Path $repoRoot $PreflightScript
$completionPath = Join-Path $repoRoot $CompletionPlan
$deployReadmePath = Join-Path $repoRoot $DeployReadme
$observabilityPath = Join-Path $repoRoot $ObservabilityRunbook

foreach ($path in @($catalogPath, $exporterPath, $preflightPath, $completionPath, $deployReadmePath, $observabilityPath)) {
    if (-not (Test-Path $path)) {
        throw "Required Secret Manager evidence file not found: $path"
    }
}

$catalog = Get-Content -Raw -Path $catalogPath
$exporter = Get-Content -Raw -Path $exporterPath
$preflight = Get-Content -Raw -Path $preflightPath
$completion = Get-Content -Raw -Path $completionPath
$deployReadme = Get-Content -Raw -Path $deployReadmePath
$observability = Get-Content -Raw -Path $observabilityPath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @(
    "Get-SecretManagerCatalog",
    "jwt-secret",
    "msg91-auth-key",
    "audit-ingest-token",
    "billing-service-token")) {
    if (-not $catalog.Contains($required)) {
        $violations.Add("Secret Manager catalog missing required item: $required")
    }
}

foreach ($required in @(
    "Get-SecretManagerCatalog",
    "secrets describe",
    "secrets versions list",
    "secret-manager-evidence.json",
    "enabledVersionCount",
    "latestEnabledVersion",
    "Mock")) {
    if (-not $exporter.Contains($required)) {
        $violations.Add("Secret Manager evidence exporter missing required behavior: $required")
    }
}

if ($exporter.Contains("versions access")) {
    $violations.Add("Secret Manager evidence exporter must not access secret values.")
}

foreach ($required in @(
    "SecretManagerEvidenceJson",
    "secret manager evidence artifact",
    "secret manager required secrets",
    "secret manager enabled versions")) {
    if (-not $preflight.Contains($required)) {
        $violations.Add("Promotion preflight missing Secret Manager validation: $required")
    }
}

foreach ($requiredDoc in @(
    "scripts\export-secret-manager-evidence.ps1",
    "secret-manager-evidence.json",
    "-SecretManagerEvidenceJson secret-manager-evidence.json")) {
    if (-not $completion.Contains($requiredDoc)) {
        $violations.Add("Completion plan missing Secret Manager evidence documentation: $requiredDoc")
    }
    if (-not $deployReadme.Contains($requiredDoc)) {
        $violations.Add("GCP README missing Secret Manager evidence documentation: $requiredDoc")
    }
}

if (-not $observability.Contains("secret-manager-evidence.json")) {
    $violations.Add("Observability runbook missing Secret Manager evidence artifact.")
}

if ($violations.Count -gt 0) {
    Write-Host "Secret Manager evidence export audit violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Secret Manager evidence export audit passed: catalog, exporter, preflight, and docs cover required secrets without value access."

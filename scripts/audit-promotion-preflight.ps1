param(
    [string]$PreflightScript = "scripts/invoke-promotion-preflight.ps1",
    [string]$CompletionPlan = "docs/MICROSERVICES-COMPLETION-PLAN.md",
    [string]$DeployReadme = "deploy/gcp/README.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$preflightPath = Join-Path $repoRoot $PreflightScript
$completionPath = Join-Path $repoRoot $CompletionPlan
$deployReadmePath = Join-Path $repoRoot $DeployReadme

foreach ($path in @($preflightPath, $completionPath, $deployReadmePath)) {
    if (-not (Test-Path $path)) {
        throw "Required promotion preflight file not found: $path"
    }
}

$preflight = Get-Content -Raw -Path $preflightPath
$completion = Get-Content -Raw -Path $completionPath
$deployReadme = Get-Content -Raw -Path $deployReadmePath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @(
    "verify-microservice-migration.ps1",
    "deployment-readiness-smoke.json",
    "legacy-compatibility-audit.json",
    "needsBackfillReview",
    "CloudBuildId",
    "ImageDigest",
    "RevisionInventoryJson",
    "ImageDigestJson",
    "SecretManagerEvidenceJson",
    "secret manager evidence artifact",
    "secret manager enabled versions",
    "CloudRunIamEvidenceJson",
    "cloud run iam evidence artifact",
    "cloud run iam required invokers",
    "LegacyRetirementEvidenceJson",
    "legacy retirement evidence artifact",
    "legacy retirement destructive drops",
    "RollbackDrillEvidenceJson",
    "rollback drill evidence artifact",
    "rollback drill smoke failures",
    "production",
    "staging")) {
    if (-not $preflight.Contains($required)) {
        $violations.Add("Promotion preflight script missing required check/input: $required")
    }
}

foreach ($requiredDoc in @(
    "scripts\invoke-promotion-preflight.ps1",
    "secret-manager-evidence.json",
    "cloud-run-iam-evidence.json",
    "legacy-retirement-evidence.json",
    "rollback-drill-evidence.json",
    "deployment-readiness-smoke.json",
    "legacy-compatibility-audit.json")) {
    if (-not $completion.Contains($requiredDoc)) {
        $violations.Add("Completion plan missing promotion preflight documentation: $requiredDoc")
    }
    if (-not $deployReadme.Contains($requiredDoc)) {
        $violations.Add("GCP README missing promotion preflight documentation: $requiredDoc")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Promotion preflight audit violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Promotion preflight audit passed: preflight script and operator docs cover required promotion artifacts."

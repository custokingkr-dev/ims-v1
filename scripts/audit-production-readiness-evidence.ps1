param(
    [string]$LegacyEvidenceScript = "scripts/new-legacy-retirement-evidence.ps1",
    [string]$RollbackEvidenceScript = "scripts/new-rollback-drill-evidence.ps1",
    [string]$PreflightScript = "scripts/invoke-promotion-preflight.ps1",
    [string]$ManifestScript = "scripts/new-promotion-bundle-manifest.ps1",
    [string]$CompletionPlan = "docs/MICROSERVICES-COMPLETION-PLAN.md",
    [string]$DeployReadme = "deploy/gcp/README.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$paths = @(
    Join-Path $repoRoot $LegacyEvidenceScript
    Join-Path $repoRoot $RollbackEvidenceScript
    Join-Path $repoRoot $PreflightScript
    Join-Path $repoRoot $ManifestScript
    Join-Path $repoRoot $CompletionPlan
    Join-Path $repoRoot $DeployReadme
)

foreach ($path in $paths) {
    if (-not (Test-Path $path)) {
        throw "Required production readiness evidence file not found: $path"
    }
}

$legacyScript = Get-Content -Raw -Path (Join-Path $repoRoot $LegacyEvidenceScript)
$rollbackScript = Get-Content -Raw -Path (Join-Path $repoRoot $RollbackEvidenceScript)
$preflight = Get-Content -Raw -Path (Join-Path $repoRoot $PreflightScript)
$manifest = Get-Content -Raw -Path (Join-Path $repoRoot $ManifestScript)
$completion = Get-Content -Raw -Path (Join-Path $repoRoot $CompletionPlan)
$deployReadme = Get-Content -Raw -Path (Join-Path $repoRoot $DeployReadme)
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @("legacy-retirement-evidence.json", "retirementSqlSha256", "destructiveDropsActive", "needsBackfillReview")) {
    if (-not $legacyScript.Contains($required)) {
        $violations.Add("Legacy retirement evidence script missing required behavior: $required")
    }
}

foreach ($required in @("rollback-drill-evidence.json", "rollbackTargetRevision", "missingRollbackTargetCount", "smokeFailures")) {
    if (-not $rollbackScript.Contains($required)) {
        $violations.Add("Rollback drill evidence script missing required behavior: $required")
    }
}

foreach ($required in @(
    "LegacyRetirementEvidenceJson",
    "legacy retirement evidence artifact",
    "legacy retirement destructive drops",
    "RollbackDrillEvidenceJson",
    "rollback drill evidence artifact",
    "rollback drill smoke failures")) {
    if (-not $preflight.Contains($required)) {
        $violations.Add("Promotion preflight missing readiness evidence validation: $required")
    }
}

foreach ($required in @("legacy-retirement-evidence", "rollback-drill-evidence", "legacyRetirementIssues", "rollbackMissingTargets")) {
    if (-not $manifest.Contains($required)) {
        $violations.Add("Promotion bundle manifest missing readiness evidence: $required")
    }
}

foreach ($requiredDoc in @(
    "scripts\new-legacy-retirement-evidence.ps1",
    "legacy-retirement-evidence.json",
    "scripts\new-rollback-drill-evidence.ps1",
    "rollback-drill-evidence.json")) {
    if (-not $completion.Contains($requiredDoc)) {
        $violations.Add("Completion plan missing readiness evidence documentation: $requiredDoc")
    }
    if (-not $deployReadme.Contains($requiredDoc)) {
        $violations.Add("GCP README missing readiness evidence documentation: $requiredDoc")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Production readiness evidence audit violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Production readiness evidence audit passed: legacy retirement and rollback drill evidence are required and documented."

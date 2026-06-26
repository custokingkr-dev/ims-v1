param(
    [string]$ManifestScript = "scripts/new-promotion-bundle-manifest.ps1",
    [string]$PreflightScript = "scripts/invoke-promotion-preflight.ps1",
    [string]$CompletionPlan = "docs/MICROSERVICES-COMPLETION-PLAN.md",
    [string]$DeployReadme = "deploy/gcp/README.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$manifestPath = Join-Path $repoRoot $ManifestScript
$preflightPath = Join-Path $repoRoot $PreflightScript
$completionPath = Join-Path $repoRoot $CompletionPlan
$deployReadmePath = Join-Path $repoRoot $DeployReadme

foreach ($path in @($manifestPath, $preflightPath, $completionPath, $deployReadmePath)) {
    if (-not (Test-Path $path)) {
        throw "Required promotion bundle file not found: $path"
    }
}

$manifest = Get-Content -Raw -Path $manifestPath
$preflight = Get-Content -Raw -Path $preflightPath
$completion = Get-Content -Raw -Path $completionPath
$deployReadme = Get-Content -Raw -Path $deployReadmePath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @(
    "promotion-bundle-manifest.json",
    "Get-FileHash",
    "deployment-readiness-smoke",
    "legacy-compatibility-audit",
    "cloud-build-evidence",
    "image-digests",
    "cloud-run-revisions",
    "secret-manager-evidence",
    "cloud-run-iam-evidence",
    "legacy-retirement-evidence",
    "rollback-drill-evidence",
    "smokeFailures",
    "legacyBackfillIssues",
    "secretMissingCount",
    "iamInvalidCount",
    "rollbackMissingTargets")) {
    if (-not $manifest.Contains($required)) {
        $violations.Add("Promotion bundle manifest script missing required behavior: $required")
    }
}

foreach ($required in @(
    "PromotionBundleManifestJson",
    "promotion bundle manifest",
    "promotion bundle artifact count",
    "promotion bundle required artifacts")) {
    if (-not $preflight.Contains($required)) {
        $violations.Add("Promotion preflight missing bundle validation: $required")
    }
}

foreach ($requiredDoc in @(
    "scripts\new-promotion-bundle-manifest.ps1",
    "promotion-bundle-manifest.json",
    "secret-manager-evidence.json",
    "cloud-run-iam-evidence.json",
    "legacy-retirement-evidence.json",
    "rollback-drill-evidence.json",
    "-PromotionBundleManifestJson promotion-bundle-manifest.json")) {
    if (-not $completion.Contains($requiredDoc)) {
        $violations.Add("Completion plan missing promotion bundle documentation: $requiredDoc")
    }
    if (-not $deployReadme.Contains($requiredDoc)) {
        $violations.Add("GCP README missing promotion bundle documentation: $requiredDoc")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Promotion bundle manifest audit violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Promotion bundle manifest audit passed: bundle manifest and preflight validation are documented."

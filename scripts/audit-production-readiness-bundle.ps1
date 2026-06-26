param(
    [string]$BundleScript = "scripts/invoke-production-readiness-bundle.ps1",
    [string]$ReportScript = "scripts/new-production-readiness-report.ps1",
    [string]$VerifyScript = "scripts/verify-microservice-migration.ps1",
    [string]$CompletionPlan = "docs/MICROSERVICES-COMPLETION-PLAN.md",
    [string]$DeployReadme = "deploy/gcp/README.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$bundlePath = Join-Path $repoRoot $BundleScript
$reportPath = Join-Path $repoRoot $ReportScript
$verifyPath = Join-Path $repoRoot $VerifyScript
$completionPath = Join-Path $repoRoot $CompletionPlan
$deployReadmePath = Join-Path $repoRoot $DeployReadme

foreach ($path in @($bundlePath, $reportPath, $verifyPath, $completionPath, $deployReadmePath)) {
    if (-not (Test-Path $path)) {
        throw "Required production readiness bundle file not found: $path"
    }
}

$bundle = Get-Content -Raw -Path $bundlePath
$report = Get-Content -Raw -Path $reportPath
$verify = Get-Content -Raw -Path $verifyPath
$completion = Get-Content -Raw -Path $completionPath
$deployReadme = Get-Content -Raw -Path $deployReadmePath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @(
    "export-cloud-run-revisions.ps1",
    "export-image-digests.ps1",
    "export-cloud-build-evidence.ps1",
    "export-secret-manager-evidence.ps1",
    "export-cloud-run-iam-evidence.ps1",
    "generate-legacy-public-retirement-sql.ps1",
    "new-legacy-retirement-evidence.ps1",
    "new-rollback-drill-evidence.ps1",
    "new-promotion-bundle-manifest.ps1",
    "invoke-promotion-preflight.ps1",
    "new-production-readiness-report.ps1",
    "Mock")) {
    if (-not $bundle.Contains($required)) {
        $violations.Add("Production readiness bundle missing required step: $required")
    }
}

foreach ($required in @("readyForPromotion", "blockingIssues", "production-readiness-report.json", "production-readiness-report.md")) {
    if (-not $report.Contains($required)) {
        $violations.Add("Production readiness report missing required output/check: $required")
    }
}

if (-not $verify.Contains("audit-production-readiness-bundle.ps1")) {
    $violations.Add("Main migration gate does not run production readiness bundle audit.")
}

foreach ($requiredDoc in @(
    "scripts\invoke-production-readiness-bundle.ps1",
    "promotion-artifacts",
    "production-readiness-report.json",
    "production-readiness-report.md")) {
    if (-not $completion.Contains($requiredDoc)) {
        $violations.Add("Completion plan missing production readiness bundle documentation: $requiredDoc")
    }
    if (-not $deployReadme.Contains($requiredDoc)) {
        $violations.Add("GCP README missing production readiness bundle documentation: $requiredDoc")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Production readiness bundle audit violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Production readiness bundle audit passed: one-command bundle and readiness report are wired into docs and gate."

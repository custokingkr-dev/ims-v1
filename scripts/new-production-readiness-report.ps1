param(
    [string]$PromotionBundleManifestJson = "promotion-bundle-manifest.json",
    [string]$OutputJson = "production-readiness-report.json",
    [string]$OutputMarkdown = "production-readiness-report.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $repoRoot $Path
}

$manifestPath = Resolve-RepoPath $PromotionBundleManifestJson
if (-not (Test-Path $manifestPath)) {
    throw "Promotion bundle manifest not found: $PromotionBundleManifestJson"
}

$manifest = Get-Content -Raw -Path $manifestPath | ConvertFrom-Json
$missingRequired = @(@($manifest.artifacts) | Where-Object { $_.required -and -not $_.present })
$summary = $manifest.summary
$blockingIssues = New-Object System.Collections.Generic.List[string]

if ($missingRequired.Count -gt 0) {
    $blockingIssues.Add("Missing required artifacts: $($missingRequired.name -join ', ')") | Out-Null
}
if ([int]$summary.smokeFailures -gt 0) {
    $blockingIssues.Add("Deployment smoke failures: $($summary.smokeFailures)") | Out-Null
}
if ([int]$summary.legacyBackfillIssues -gt 0) {
    $blockingIssues.Add("Legacy backfill issues: $($summary.legacyBackfillIssues)") | Out-Null
}
if ([int]$summary.secretMissingCount -gt 0) {
    $blockingIssues.Add("Missing or disabled secrets: $($summary.secretMissingCount)") | Out-Null
}
if ([int]$summary.iamInvalidCount -gt 0) {
    $blockingIssues.Add("Invalid Cloud Run IAM services: $($summary.iamInvalidCount)") | Out-Null
}
if ([int]$summary.legacyRetirementIssues -gt 0) {
    $blockingIssues.Add("Legacy retirement evidence issues: $($summary.legacyRetirementIssues)") | Out-Null
}
if ([int]$summary.rollbackMissingTargets -gt 0) {
    $blockingIssues.Add("Rollback targets missing: $($summary.rollbackMissingTargets)") | Out-Null
}
if ([int]$summary.rollbackSmokeFailures -gt 0) {
    $blockingIssues.Add("Rollback smoke failures: $($summary.rollbackSmokeFailures)") | Out-Null
}

$ready = $blockingIssues.Count -eq 0
$report = [ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    environment = $manifest.environment
    readyForPromotion = $ready
    blockingIssues = @($blockingIssues)
    summary = $summary
    artifacts = $manifest.artifacts
}

$report | ConvertTo-Json -Depth 7 | Set-Content -Path $OutputJson -Encoding UTF8

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# Production Readiness Report")
$lines.Add("")
$lines.Add("- Environment: $($manifest.environment)")
$lines.Add("- Ready for promotion: $ready")
$lines.Add("- Generated: $($report.generatedAtUtc)")
$lines.Add("")
$lines.Add("## Blocking Issues")
if ($blockingIssues.Count -eq 0) {
    $lines.Add("- None")
} else {
    foreach ($issue in $blockingIssues) {
        $lines.Add("- $issue")
    }
}
$lines.Add("")
$lines.Add("## Artifact Summary")
foreach ($artifact in @($manifest.artifacts)) {
    $lines.Add("- $($artifact.name): present=$($artifact.present) required=$($artifact.required)")
}
$lines | Set-Content -Path $OutputMarkdown -Encoding UTF8

Write-Host "Created production readiness report: $OutputJson"
Write-Host "Created production readiness markdown: $OutputMarkdown"
Write-Host "Ready for promotion: $ready"
if (-not $ready) {
    exit 1
}

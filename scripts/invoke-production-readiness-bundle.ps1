param(
    [ValidateSet("staging", "production")]
    [string]$Environment = "production",
    [string]$ArtifactDir = "promotion-artifacts",
    [string]$ProjectId = "custoking",
    [string]$Region = "asia-south2",
    [string]$Repository = "custoking",
    [string]$Tag = "latest",
    [string]$BuildId,
    [string]$CommitSha,
    [string]$DeploymentSmokeJson,
    [string]$LegacyCompatibilityJson,
    [string]$GcloudPath = "gcloud",
    [switch]$Mock,
    [switch]$SkipStaticGate
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

function Resolve-InputPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $repoRoot $Path
}

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Command
    )

    Write-Host ""
    Write-Host "==> $Name"
    $global:LASTEXITCODE = 0
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
    Write-Host "OK: $Name"
}

$artifactRoot = Resolve-InputPath $ArtifactDir
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null

$smoke = Join-Path $artifactRoot "deployment-readiness-smoke.json"
$legacy = Join-Path $artifactRoot "legacy-compatibility-audit.json"
$legacySql = Join-Path $artifactRoot "legacy-public-retirement.sql"
$legacyRetirement = Join-Path $artifactRoot "legacy-retirement-evidence.json"
$rollback = Join-Path $artifactRoot "rollback-drill-evidence.json"
$revisions = Join-Path $artifactRoot "cloud-run-revisions.json"
$digests = Join-Path $artifactRoot "image-digests.json"
$build = Join-Path $artifactRoot "cloud-build-evidence.json"
$secrets = Join-Path $artifactRoot "secret-manager-evidence.json"
$iam = Join-Path $artifactRoot "cloud-run-iam-evidence.json"
$manifest = Join-Path $artifactRoot "promotion-bundle-manifest.json"
$reportJson = Join-Path $artifactRoot "production-readiness-report.json"
$reportMarkdown = Join-Path $artifactRoot "production-readiness-report.md"

if ($Mock) {
    [ordered]@{
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        gatewayBaseUrl = "https://mock-gateway"
        total = 1
        passed = 1
        failures = 0
        checks = @([ordered]@{ name = "mock"; passed = $true; detail = "mock" })
    } | ConvertTo-Json -Depth 5 | Set-Content -Path $smoke -Encoding UTF8

    [ordered]@{
        summary = [ordered]@{
            generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
            totalMappedTables = 45
            publicTablesWithRows = 0
            needsBackfillReview = 0
            statusCounts = [ordered]@{ PUBLIC_EMPTY = 45 }
        }
        rows = @()
    } | ConvertTo-Json -Depth 5 | Set-Content -Path $legacy -Encoding UTF8
} else {
    if ([string]::IsNullOrWhiteSpace($DeploymentSmokeJson) -or [string]::IsNullOrWhiteSpace($LegacyCompatibilityJson)) {
        throw "DeploymentSmokeJson and LegacyCompatibilityJson are required unless -Mock is supplied."
    }
    Copy-Item -LiteralPath (Resolve-InputPath $DeploymentSmokeJson) -Destination $smoke -Force
    Copy-Item -LiteralPath (Resolve-InputPath $LegacyCompatibilityJson) -Destination $legacy -Force
}

Invoke-Step "export Cloud Run revisions" {
    & (Join-Path $PSScriptRoot "export-cloud-run-revisions.ps1") `
        -ProjectId $ProjectId `
        -Region $Region `
        -OutputJson $revisions `
        -GcloudPath $GcloudPath `
        -Mock:$Mock
}

Invoke-Step "export image digests" {
    & (Join-Path $PSScriptRoot "export-image-digests.ps1") `
        -ProjectId $ProjectId `
        -Region $Region `
        -Repository $Repository `
        -Tag $Tag `
        -OutputJson $digests `
        -GcloudPath $GcloudPath `
        -Mock:$Mock
}

Invoke-Step "export Cloud Build evidence" {
    & (Join-Path $PSScriptRoot "export-cloud-build-evidence.ps1") `
        -ProjectId $ProjectId `
        -BuildId $BuildId `
        -CommitSha $CommitSha `
        -OutputJson $build `
        -GcloudPath $GcloudPath `
        -Mock:$Mock
}

Invoke-Step "export Secret Manager evidence" {
    & (Join-Path $PSScriptRoot "export-secret-manager-evidence.ps1") `
        -ProjectId $ProjectId `
        -OutputJson $secrets `
        -GcloudPath $GcloudPath `
        -Mock:$Mock
}

Invoke-Step "export Cloud Run IAM evidence" {
    & (Join-Path $PSScriptRoot "export-cloud-run-iam-evidence.ps1") `
        -ProjectId $ProjectId `
        -Region $Region `
        -OutputJson $iam `
        -GcloudPath $GcloudPath `
        -Mock:$Mock
}

Invoke-Step "generate legacy retirement SQL" {
    & (Join-Path $PSScriptRoot "generate-legacy-public-retirement-sql.ps1") `
        -CompatibilityAuditJson $legacy `
        -OutputSql $legacySql
}

Invoke-Step "create legacy retirement evidence" {
    & (Join-Path $PSScriptRoot "new-legacy-retirement-evidence.ps1") `
        -CompatibilityAuditJson $legacy `
        -RetirementSql $legacySql `
        -OutputJson $legacyRetirement
}

Invoke-Step "create rollback drill evidence" {
    & (Join-Path $PSScriptRoot "new-rollback-drill-evidence.ps1") `
        -RevisionInventoryJson $revisions `
        -DeploymentSmokeJson $smoke `
        -OutputJson $rollback `
        -Mock:$Mock
}

Invoke-Step "create promotion bundle manifest" {
    & (Join-Path $PSScriptRoot "new-promotion-bundle-manifest.ps1") `
        -Environment $Environment `
        -DeploymentSmokeJson $smoke `
        -LegacyCompatibilityJson $legacy `
        -CloudBuildJson $build `
        -ImageDigestJson $digests `
        -RevisionInventoryJson $revisions `
        -SecretManagerEvidenceJson $secrets `
        -CloudRunIamEvidenceJson $iam `
        -LegacyRetirementEvidenceJson $legacyRetirement `
        -RollbackDrillEvidenceJson $rollback `
        -OutputJson $manifest
}

Invoke-Step "run promotion preflight" {
    & (Join-Path $PSScriptRoot "invoke-promotion-preflight.ps1") `
        -Environment $Environment `
        -DeploymentSmokeJson $smoke `
        -LegacyCompatibilityJson $legacy `
        -CloudBuildJson $build `
        -ImageDigestJson $digests `
        -RevisionInventoryJson $revisions `
        -SecretManagerEvidenceJson $secrets `
        -CloudRunIamEvidenceJson $iam `
        -LegacyRetirementEvidenceJson $legacyRetirement `
        -RollbackDrillEvidenceJson $rollback `
        -PromotionBundleManifestJson $manifest `
        -SkipStaticGate:$SkipStaticGate
}

Invoke-Step "create production readiness report" {
    & (Join-Path $PSScriptRoot "new-production-readiness-report.ps1") `
        -PromotionBundleManifestJson $manifest `
        -OutputJson $reportJson `
        -OutputMarkdown $reportMarkdown
}

Write-Host ""
Write-Host "Production readiness bundle completed."
Write-Host "Artifact directory: $artifactRoot"

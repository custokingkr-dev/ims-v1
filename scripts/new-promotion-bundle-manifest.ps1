param(
    [ValidateSet("staging", "production")]
    [string]$Environment = "staging",
    [string]$DeploymentSmokeJson = "deployment-readiness-smoke.json",
    [string]$LegacyCompatibilityJson = "legacy-compatibility-audit.json",
    [string]$CloudBuildJson = "cloud-build-evidence.json",
    [string]$ImageDigestJson = "image-digests.json",
    [string]$RevisionInventoryJson = "cloud-run-revisions.json",
    [string]$SecretManagerEvidenceJson = "secret-manager-evidence.json",
    [string]$CloudRunIamEvidenceJson = "cloud-run-iam-evidence.json",
    [string]$LegacyRetirementEvidenceJson = "legacy-retirement-evidence.json",
    [string]$RollbackDrillEvidenceJson = "rollback-drill-evidence.json",
    [string]$OutputJson = "promotion-bundle-manifest.json",
    [switch]$RequireReleaseEvidence
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

function Read-Artifact {
    param(
        [string]$Name,
        [string]$Path,
        [bool]$Required
    )

    $resolved = Resolve-RepoPath $Path
    if (-not (Test-Path $resolved)) {
        if ($Required) {
            throw "Required promotion artifact missing: $Path"
        }
        return [pscustomobject]@{
            name = $Name
            path = $Path
            required = $false
            present = $false
        }
    }

    $hash = Get-FileHash -Algorithm SHA256 -Path $resolved
    $json = Get-Content -Raw -Path $resolved | ConvertFrom-Json
    return [pscustomobject]@{
        name = $Name
        path = $Path
        required = $Required
        present = $true
        sha256 = $hash.Hash.ToLowerInvariant()
        bytes = (Get-Item -Path $resolved).Length
        generatedAtUtc = $json.generatedAtUtc
    }
}

$releaseRequired = $RequireReleaseEvidence -or $Environment -eq "production"
$artifacts = @(
    Read-Artifact "deployment-readiness-smoke" $DeploymentSmokeJson $true
    Read-Artifact "legacy-compatibility-audit" $LegacyCompatibilityJson $true
    Read-Artifact "cloud-build-evidence" $CloudBuildJson $releaseRequired
    Read-Artifact "image-digests" $ImageDigestJson $releaseRequired
    Read-Artifact "cloud-run-revisions" $RevisionInventoryJson $releaseRequired
    Read-Artifact "secret-manager-evidence" $SecretManagerEvidenceJson $releaseRequired
    Read-Artifact "cloud-run-iam-evidence" $CloudRunIamEvidenceJson $releaseRequired
    Read-Artifact "legacy-retirement-evidence" $LegacyRetirementEvidenceJson $releaseRequired
    Read-Artifact "rollback-drill-evidence" $RollbackDrillEvidenceJson $releaseRequired
)

$smoke = Get-Content -Raw -Path (Resolve-RepoPath $DeploymentSmokeJson) | ConvertFrom-Json
$legacy = Get-Content -Raw -Path (Resolve-RepoPath $LegacyCompatibilityJson) | ConvertFrom-Json
$cloudBuild = if ((Test-Path (Resolve-RepoPath $CloudBuildJson))) { Get-Content -Raw -Path (Resolve-RepoPath $CloudBuildJson) | ConvertFrom-Json } else { $null }
$digests = if ((Test-Path (Resolve-RepoPath $ImageDigestJson))) { Get-Content -Raw -Path (Resolve-RepoPath $ImageDigestJson) | ConvertFrom-Json } else { $null }
$revisions = if ((Test-Path (Resolve-RepoPath $RevisionInventoryJson))) { Get-Content -Raw -Path (Resolve-RepoPath $RevisionInventoryJson) | ConvertFrom-Json } else { $null }
$secretEvidence = if ((Test-Path (Resolve-RepoPath $SecretManagerEvidenceJson))) { Get-Content -Raw -Path (Resolve-RepoPath $SecretManagerEvidenceJson) | ConvertFrom-Json } else { $null }
$iamEvidence = if ((Test-Path (Resolve-RepoPath $CloudRunIamEvidenceJson))) { Get-Content -Raw -Path (Resolve-RepoPath $CloudRunIamEvidenceJson) | ConvertFrom-Json } else { $null }
$legacyRetirement = if ((Test-Path (Resolve-RepoPath $LegacyRetirementEvidenceJson))) { Get-Content -Raw -Path (Resolve-RepoPath $LegacyRetirementEvidenceJson) | ConvertFrom-Json } else { $null }
$rollbackDrill = if ((Test-Path (Resolve-RepoPath $RollbackDrillEvidenceJson))) { Get-Content -Raw -Path (Resolve-RepoPath $RollbackDrillEvidenceJson) | ConvertFrom-Json } else { $null }

[ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    environment = $Environment
    summary = [ordered]@{
        smokeFailures = [int]$smoke.failures
        smokeChecks = [int]$smoke.total
        legacyBackfillIssues = [int]$legacy.summary.needsBackfillReview
        cloudBuildId = if ($cloudBuild) { $cloudBuild.id } else { $null }
        cloudBuildStatus = if ($cloudBuild) { $cloudBuild.status } else { $null }
        imageCount = if ($digests -and $digests.images) { (@($digests.images)).Count } else { 0 }
        revisionServiceCount = if ($revisions -and $revisions.services) { (@($revisions.services)).Count } else { 0 }
        secretCount = if ($secretEvidence -and $secretEvidence.secrets) { (@($secretEvidence.secrets)).Count } else { 0 }
        secretMissingCount = if ($secretEvidence -and $secretEvidence.secrets) { (@($secretEvidence.secrets | Where-Object { -not $_.present -or [int]$_.enabledVersionCount -lt 1 })).Count } else { 0 }
        iamServiceCount = if ($iamEvidence -and $iamEvidence.services) { (@($iamEvidence.services)).Count } else { 0 }
        iamInvalidCount = if ($iamEvidence -and $iamEvidence.services) { (@($iamEvidence.services | Where-Object { -not $_.publicExposureValid -or -not $_.requiredInvokersPresent })).Count } else { 0 }
        legacyRetirementIssues = if ($legacyRetirement) { [int]$legacyRetirement.needsBackfillReview + [int]$legacyRetirement.activeDropStatements } else { 0 }
        rollbackMissingTargets = if ($rollbackDrill) { [int]$rollbackDrill.missingRollbackTargetCount } else { 0 }
        rollbackSmokeFailures = if ($rollbackDrill) { [int]$rollbackDrill.smokeFailures } else { 0 }
    }
    artifacts = $artifacts
} | ConvertTo-Json -Depth 6 | Set-Content -Path $OutputJson -Encoding UTF8

Write-Host "Created promotion bundle manifest: $OutputJson"
Write-Host "Artifacts indexed: $(@($artifacts | Where-Object { $_.present }).Count)"

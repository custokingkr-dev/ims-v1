param(
    [ValidateSet("staging", "production")]
    [string]$Environment = "staging",
    [string]$DeploymentSmokeJson = "deployment-readiness-smoke.json",
    [string]$LegacyCompatibilityJson = "legacy-compatibility-audit.json",
    [string]$CloudBuildId,
    [string]$CloudBuildJson,
    [string]$ImageDigest,
    [string]$ImageDigestJson,
    [string]$RevisionInventoryJson,
    [string]$SecretManagerEvidenceJson,
    [string]$CloudRunIamEvidenceJson,
    [string]$LegacyRetirementEvidenceJson,
    [string]$RollbackDrillEvidenceJson,
    [string]$PromotionBundleManifestJson,
    [switch]$SkipStaticGate,
    [switch]$RequireReleaseEvidence
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Detail = ""
    )

    $checks.Add([pscustomobject]@{
        Name = $Name
        Passed = $Passed
        Detail = $Detail
    }) | Out-Null
}

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $repoRoot $Path
}

function Read-JsonFile {
    param(
        [string]$Path,
        [string]$Name
    )

    $resolved = Resolve-RepoPath $Path
    if (-not (Test-Path $resolved)) {
        Add-Check $Name $false "Missing file: $Path"
        return $null
    }

    try {
        $json = Get-Content -Raw -Path $resolved | ConvertFrom-Json
        Add-Check $Name $true "Read $Path"
        return $json
    } catch {
        Add-Check $Name $false "Invalid JSON in ${Path}: $($_.Exception.Message)"
        return $null
    }
}

if (-not $SkipStaticGate) {
    try {
        & (Join-Path $PSScriptRoot "verify-microservice-migration.ps1")
        if ($LASTEXITCODE -ne 0) {
            Add-Check "static migration gate" $false "verify-microservice-migration.ps1 exited with $LASTEXITCODE"
        } else {
            Add-Check "static migration gate" $true "verify-microservice-migration.ps1 passed"
        }
    } catch {
        Add-Check "static migration gate" $false $_.Exception.Message
    }
}

$smoke = Read-JsonFile $DeploymentSmokeJson "deployment readiness smoke artifact"
if ($smoke) {
    Add-Check "deployment smoke failures" ([int]$smoke.failures -eq 0) "failures=$($smoke.failures) total=$($smoke.total)"
    Add-Check "deployment smoke gateway url" (-not [string]::IsNullOrWhiteSpace($smoke.gatewayBaseUrl)) "gatewayBaseUrl=$($smoke.gatewayBaseUrl)"
    Add-Check "deployment smoke checks present" (($smoke.checks | Measure-Object).Count -gt 0) "checks=$((@($smoke.checks)).Count)"
}

$legacy = Read-JsonFile $LegacyCompatibilityJson "legacy compatibility artifact"
if ($legacy) {
    Add-Check "legacy compatibility summary" ($null -ne $legacy.summary) "summary present"
    if ($legacy.summary) {
        Add-Check "legacy backfill issues" ([int]$legacy.summary.needsBackfillReview -eq 0) "needsBackfillReview=$($legacy.summary.needsBackfillReview)"
        Add-Check "legacy mapped tables" ([int]$legacy.summary.totalMappedTables -gt 0) "totalMappedTables=$($legacy.summary.totalMappedTables)"
    }
}

$mustHaveReleaseEvidence = $RequireReleaseEvidence -or $Environment -eq "production"
if ($mustHaveReleaseEvidence) {
    $hasCloudBuildId = -not [string]::IsNullOrWhiteSpace($CloudBuildId)
    $hasCloudBuildJson = -not [string]::IsNullOrWhiteSpace($CloudBuildJson)
    Add-Check "cloud build evidence supplied" ($hasCloudBuildId -or $hasCloudBuildJson) "CloudBuildId=$CloudBuildId CloudBuildJson=$CloudBuildJson"
    if ($hasCloudBuildJson) {
        $cloudBuild = Read-JsonFile $CloudBuildJson "cloud build evidence artifact"
        if ($cloudBuild) {
            Add-Check "cloud build status" ($cloudBuild.status -eq "SUCCESS") "status=$($cloudBuild.status)"
            Add-Check "cloud build id" (-not [string]::IsNullOrWhiteSpace($cloudBuild.id)) "id=$($cloudBuild.id)"
            Add-Check "cloud build images" ((@($cloudBuild.images)).Count -gt 0) "images=$((@($cloudBuild.images)).Count)"
        }
    }

    $hasSingleDigest = -not [string]::IsNullOrWhiteSpace($ImageDigest)
    $hasDigestJson = -not [string]::IsNullOrWhiteSpace($ImageDigestJson)
    Add-Check "image digest evidence supplied" ($hasSingleDigest -or $hasDigestJson) "ImageDigest=$ImageDigest ImageDigestJson=$ImageDigestJson"

    if ($hasDigestJson) {
        $digests = Read-JsonFile $ImageDigestJson "image digest artifact"
        if ($digests) {
            $imageCount = if ($digests.images) { (@($digests.images)).Count } else { 0 }
            $missingDigests = @(@($digests.images) | Where-Object { [string]::IsNullOrWhiteSpace($_.digest) -or "$($_.digest)" -notlike "sha256:*" })
            Add-Check "image digest service count" ($imageCount -ge 15) "images=$imageCount"
            Add-Check "image digest values" ($missingDigests.Count -eq 0) "missingOrInvalid=$($missingDigests.Count)"
        }
    }

    if ([string]::IsNullOrWhiteSpace($RevisionInventoryJson)) {
        Add-Check "revision inventory" $false "RevisionInventoryJson not supplied"
    } else {
        $revisions = Read-JsonFile $RevisionInventoryJson "revision inventory artifact"
        if ($revisions) {
            $revisionCount = if ($revisions.services) { (@($revisions.services)).Count } else { (@($revisions)).Count }
            Add-Check "revision inventory service count" ($revisionCount -ge 15) "services=$revisionCount"
        }
    }

    if ([string]::IsNullOrWhiteSpace($SecretManagerEvidenceJson)) {
        Add-Check "secret manager evidence supplied" $false "SecretManagerEvidenceJson not supplied"
    } else {
        $secretEvidence = Read-JsonFile $SecretManagerEvidenceJson "secret manager evidence artifact"
        if ($secretEvidence) {
            $secretCount = if ($secretEvidence.secrets) { (@($secretEvidence.secrets)).Count } else { 0 }
            $missingSecrets = @(@($secretEvidence.secrets) | Where-Object { -not $_.present })
            $disabledSecrets = @(@($secretEvidence.secrets) | Where-Object { [int]$_.enabledVersionCount -lt 1 })
            Add-Check "secret manager required secrets" ($secretCount -ge 18) "secrets=$secretCount"
            Add-Check "secret manager present secrets" ($missingSecrets.Count -eq 0) "missing=$($missingSecrets.Count)"
            Add-Check "secret manager enabled versions" ($disabledSecrets.Count -eq 0) "missingEnabledVersions=$($disabledSecrets.Count)"
        }
    }

    if ([string]::IsNullOrWhiteSpace($CloudRunIamEvidenceJson)) {
        Add-Check "cloud run iam evidence supplied" $false "CloudRunIamEvidenceJson not supplied"
    } else {
        $iamEvidence = Read-JsonFile $CloudRunIamEvidenceJson "cloud run iam evidence artifact"
        if ($iamEvidence) {
            $iamServiceCount = if ($iamEvidence.services) { (@($iamEvidence.services)).Count } else { 0 }
            $invalidExposure = @(@($iamEvidence.services) | Where-Object { -not $_.publicExposureValid })
            $missingInvokers = @(@($iamEvidence.services) | Where-Object { -not $_.requiredInvokersPresent })
            Add-Check "cloud run iam service count" ($iamServiceCount -ge 15) "services=$iamServiceCount"
            Add-Check "cloud run iam public exposure" ($invalidExposure.Count -eq 0) "invalidExposure=$($invalidExposure.Count)"
            Add-Check "cloud run iam required invokers" ($missingInvokers.Count -eq 0) "missingInvokers=$($missingInvokers.Count)"
        }
    }

    if ([string]::IsNullOrWhiteSpace($LegacyRetirementEvidenceJson)) {
        Add-Check "legacy retirement evidence supplied" $false "LegacyRetirementEvidenceJson not supplied"
    } else {
        $legacyRetirement = Read-JsonFile $LegacyRetirementEvidenceJson "legacy retirement evidence artifact"
        if ($legacyRetirement) {
            Add-Check "legacy retirement backfill issues" ([int]$legacyRetirement.needsBackfillReview -eq 0) "needsBackfillReview=$($legacyRetirement.needsBackfillReview)"
            Add-Check "legacy retirement sql hash" (-not [string]::IsNullOrWhiteSpace($legacyRetirement.retirementSqlSha256)) "sha256=$($legacyRetirement.retirementSqlSha256)"
            Add-Check "legacy retirement destructive drops" (-not [bool]$legacyRetirement.destructiveDropsActive) "destructiveDropsActive=$($legacyRetirement.destructiveDropsActive)"
        }
    }

    if ([string]::IsNullOrWhiteSpace($RollbackDrillEvidenceJson)) {
        Add-Check "rollback drill evidence supplied" $false "RollbackDrillEvidenceJson not supplied"
    } else {
        $rollbackDrill = Read-JsonFile $RollbackDrillEvidenceJson "rollback drill evidence artifact"
        if ($rollbackDrill) {
            Add-Check "rollback drill service count" ([int]$rollbackDrill.serviceCount -ge 15) "services=$($rollbackDrill.serviceCount)"
            Add-Check "rollback drill target coverage" ([int]$rollbackDrill.missingRollbackTargetCount -eq 0) "missingTargets=$($rollbackDrill.missingRollbackTargetCount)"
            Add-Check "rollback drill smoke failures" ([int]$rollbackDrill.smokeFailures -eq 0) "smokeFailures=$($rollbackDrill.smokeFailures)"
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($PromotionBundleManifestJson)) {
        $manifest = Read-JsonFile $PromotionBundleManifestJson "promotion bundle manifest"
        if ($manifest) {
            $artifactCount = if ($manifest.artifacts) { (@($manifest.artifacts)).Count } else { 0 }
            $missingArtifacts = @(@($manifest.artifacts) | Where-Object { -not $_.present -and $_.required })
            Add-Check "promotion bundle artifact count" ($artifactCount -ge 5) "artifacts=$artifactCount"
            Add-Check "promotion bundle required artifacts" ($missingArtifacts.Count -eq 0) "missingRequired=$($missingArtifacts.Count)"
            Add-Check "promotion bundle smoke failures" ([int]$manifest.summary.smokeFailures -eq 0) "smokeFailures=$($manifest.summary.smokeFailures)"
            Add-Check "promotion bundle legacy issues" ([int]$manifest.summary.legacyBackfillIssues -eq 0) "legacyBackfillIssues=$($manifest.summary.legacyBackfillIssues)"
        }
    }
}

$failures = @($checks | Where-Object { -not $_.Passed })
Write-Host "Promotion preflight environment=$Environment total=$($checks.Count) failures=$($failures.Count)"
$checks | Format-Table Name, Passed, Detail -AutoSize

if ($failures.Count -gt 0) {
    exit 1
}

Write-Host "Promotion preflight passed."

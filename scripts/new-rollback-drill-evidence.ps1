param(
    [string]$RevisionInventoryJson = "cloud-run-revisions.json",
    [string]$DeploymentSmokeJson = "deployment-readiness-smoke.json",
    [string]$OutputJson = "rollback-drill-evidence.json",
    [switch]$Mock
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

$revisionPath = Resolve-RepoPath $RevisionInventoryJson
$smokePath = Resolve-RepoPath $DeploymentSmokeJson

if (-not (Test-Path $revisionPath)) {
    throw "Revision inventory JSON not found: $RevisionInventoryJson"
}
if (-not (Test-Path $smokePath)) {
    throw "Deployment smoke JSON not found: $DeploymentSmokeJson"
}

$revisionInventory = Get-Content -Raw -Path $revisionPath | ConvertFrom-Json
$smoke = Get-Content -Raw -Path $smokePath | ConvertFrom-Json
$services = @($revisionInventory.services)

$targets = @($services | ForEach-Object {
    $traffic = @($_.traffic | Where-Object { [int]$_.percent -gt 0 } | Select-Object -First 1)
    $rollbackTarget = if ($_.revisions -and (@($_.revisions).Count -gt 1)) {
        @($_.revisions | Where-Object { $_.name -ne $_.latestReadyRevision } | Select-Object -First 1).name
    } else {
        $_.latestReadyRevision
    }

    [pscustomobject]@{
        name = $_.name
        cloudRunService = $_.cloudRunService
        currentRevision = if ($traffic) { $traffic.revisionName } else { $_.latestReadyRevision }
        rollbackTargetRevision = $rollbackTarget
        hasRollbackTarget = -not [string]::IsNullOrWhiteSpace($rollbackTarget)
    }
})

$missingTargets = @($targets | Where-Object { -not $_.hasRollbackTarget })

[ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    mock = [bool]$Mock
    revisionInventoryJson = $RevisionInventoryJson
    deploymentSmokeJson = $DeploymentSmokeJson
    serviceCount = $targets.Count
    missingRollbackTargetCount = $missingTargets.Count
    smokeFailures = [int]$smoke.failures
    smokeChecks = [int]$smoke.total
    rollbackTargets = $targets
} | ConvertTo-Json -Depth 6 | Set-Content -Path $OutputJson -Encoding UTF8

Write-Host "Created rollback drill evidence: $OutputJson"
Write-Host "Services: $($targets.Count)"
Write-Host "Missing rollback targets: $($missingTargets.Count)"
Write-Host "Smoke failures: $($smoke.failures)"

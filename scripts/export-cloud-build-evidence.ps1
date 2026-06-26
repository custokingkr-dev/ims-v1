param(
    [string]$ProjectId = "custoking-ims",
    [string]$BuildId,
    [string]$CommitSha,
    [string]$OutputJson = "cloud-build-evidence.json",
    [string]$GcloudPath = "gcloud",
    [switch]$Mock
)

$ErrorActionPreference = "Stop"

$sdkGcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
if ($GcloudPath -eq "gcloud" -and (Test-Path -LiteralPath $sdkGcloud)) {
    $GcloudPath = $sdkGcloud
}

function Invoke-GcloudJson {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)

    $output = & $GcloudPath @Args
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud command failed with exit code ${LASTEXITCODE}: gcloud $($Args -join ' ')"
    }
    if ([string]::IsNullOrWhiteSpace(($output -join ""))) {
        return $null
    }
    return ($output -join "`n") | ConvertFrom-Json
}

if ($Mock) {
    if ([string]::IsNullOrWhiteSpace($BuildId)) {
        $BuildId = "mock-build-id"
    }
    if ([string]::IsNullOrWhiteSpace($CommitSha)) {
        $CommitSha = "mock-sha"
    }
    $build = [pscustomobject]@{
        id = $BuildId
        status = "SUCCESS"
        createTime = (Get-Date).ToUniversalTime().AddMinutes(-10).ToString("o")
        finishTime = (Get-Date).ToUniversalTime().ToString("o")
        substitutions = [pscustomobject]@{
            _COMMIT_SHA = $CommitSha
        }
        images = @("mock-image@$CommitSha")
        logUrl = "https://console.cloud.google.com/cloud-build/builds/$BuildId"
    }
} elseif (-not [string]::IsNullOrWhiteSpace($BuildId)) {
    $build = Invoke-GcloudJson builds describe $BuildId "--project=$ProjectId" "--format=json"
} else {
    $filter = "status=SUCCESS"
    if (-not [string]::IsNullOrWhiteSpace($CommitSha)) {
        $filter = "$filter AND substitutions._COMMIT_SHA=$CommitSha"
    }
    $builds = @(Invoke-GcloudJson builds list "--project=$ProjectId" "--filter=$filter" "--sort-by=~createTime" "--limit=1" "--format=json")
    if ($builds.Count -eq 0 -or $null -eq $builds[0] -or [string]::IsNullOrWhiteSpace($builds[0].id)) {
        throw "No Cloud Build record found for filter: $filter"
    }
    $build = $builds[0]
}

$substitutions = @{}
if ($build.substitutions) {
    $build.substitutions.PSObject.Properties | ForEach-Object {
        $substitutions[$_.Name] = $_.Value
    }
}

[ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    projectId = $ProjectId
    id = $build.id
    status = $build.status
    createTime = $build.createTime
    finishTime = $build.finishTime
    commitSha = $substitutions["_COMMIT_SHA"]
    substitutions = $substitutions
    images = @($build.images)
    logUrl = $build.logUrl
} | ConvertTo-Json -Depth 6 | Set-Content -Path $OutputJson -Encoding UTF8

Write-Host "Exported Cloud Build evidence: $OutputJson"
Write-Host "Build id: $($build.id)"
Write-Host "Status: $($build.status)"

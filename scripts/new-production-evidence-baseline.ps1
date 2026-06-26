param(
    [string]$ProjectId = "custoking-ims",
    [string]$Region = "asia-south2",
    [string]$Repository = "custoking",
    [string]$Tag = "latest",
    [string]$OutputDirectory = "artifacts/production-baseline",
    [string]$GcloudPath = "gcloud"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$sdkGcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
if ($GcloudPath -eq "gcloud" -and (Test-Path -LiteralPath $sdkGcloud)) {
    $GcloudPath = $sdkGcloud
}

. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")
. (Join-Path $PSScriptRoot "cloud-run-iam-catalog.ps1")

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $repoRoot $Path
}

function Invoke-GcloudJsonFile {
    param(
        [string]$OutputPath,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Args
    )

    $output = & $GcloudPath @Args
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud command failed with exit code ${LASTEXITCODE}: gcloud $($Args -join ' ')"
    }
    ($output -join "`n") | Set-Content -Path $OutputPath -Encoding UTF8
}

$outputRoot = Resolve-RepoPath $OutputDirectory
if (-not (Test-Path -LiteralPath $outputRoot)) {
    New-Item -ItemType Directory -Path $outputRoot | Out-Null
}

$gcloudArgs = @("-ExecutionPolicy", "Bypass")

& powershell @gcloudArgs -File (Join-Path $PSScriptRoot "export-cloud-run-revisions.ps1") `
    -ProjectId $ProjectId `
    -Region $Region `
    -GcloudPath $GcloudPath `
    -OutputJson (Join-Path $outputRoot "cloud-run-revisions.json")
if ($LASTEXITCODE -ne 0) { throw "Cloud Run revision export failed." }

& powershell @gcloudArgs -File (Join-Path $PSScriptRoot "export-image-digests.ps1") `
    -ProjectId $ProjectId `
    -Region $Region `
    -Repository $Repository `
    -Tag $Tag `
    -GcloudPath $GcloudPath `
    -OutputJson (Join-Path $outputRoot "image-digests.json")
if ($LASTEXITCODE -ne 0) { throw "Image digest export failed." }

& powershell @gcloudArgs -File (Join-Path $PSScriptRoot "export-secret-manager-evidence.ps1") `
    -ProjectId $ProjectId `
    -GcloudPath $GcloudPath `
    -OutputJson (Join-Path $outputRoot "secret-manager-evidence.json")
if ($LASTEXITCODE -ne 0) { throw "Secret Manager evidence export failed." }

$cloudBuildArgs = @(
    "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $PSScriptRoot "export-cloud-build-evidence.ps1"),
    "-ProjectId", $ProjectId,
    "-GcloudPath", $GcloudPath,
    "-OutputJson", (Join-Path $outputRoot "cloud-build-evidence.json")
)
if (-not [string]::IsNullOrWhiteSpace($Tag) -and $Tag -ne "latest") {
    $cloudBuildArgs += @("-CommitSha", $Tag)
}
& powershell @cloudBuildArgs
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Could not export Cloud Build evidence for tag '$Tag'. Continuing with latest build inventory."
}

Invoke-GcloudJsonFile (Join-Path $outputRoot "cloud-run-services.json") `
    run services list "--project=$ProjectId" "--region=$Region" "--format=json"

Invoke-GcloudJsonFile (Join-Path $outputRoot "cloud-builds-latest.json") `
    builds list "--project=$ProjectId" "--limit=10" "--sort-by=~createTime" "--format=json"

$iamRoot = Join-Path $outputRoot "iam"
if (-not (Test-Path -LiteralPath $iamRoot)) {
    New-Item -ItemType Directory -Path $iamRoot | Out-Null
}
foreach ($entry in Get-CloudRunIamCatalog) {
    Invoke-GcloudJsonFile (Join-Path $iamRoot "$($entry.CloudRunService).json") `
        run services get-iam-policy $entry.CloudRunService "--project=$ProjectId" "--region=$Region" "--format=json"
}

$gateway = & $GcloudPath run services describe custoking-api-gateway "--project=$ProjectId" "--region=$Region" "--format=json"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to describe custoking-api-gateway."
}
($gateway -join "`n") | Set-Content -Path (Join-Path $outputRoot "api-gateway-service.json") -Encoding UTF8

$backendStatus = [ordered]@{
    checkedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    present = $false
    service = $null
}
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $backendOutput = & $GcloudPath run services describe custoking-backend "--project=$ProjectId" "--region=$Region" "--format=json" 2>$null
    if ($LASTEXITCODE -eq 0) {
        $backendStatus.present = $true
        $backendStatus.service = ($backendOutput -join "`n") | ConvertFrom-Json
    }
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
$backendStatus | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $outputRoot "backend-retirement-status.json") -Encoding UTF8

Write-Host "Created production evidence baseline: $outputRoot"

param(
    [string]$ProjectId = "custoking",
    [string]$Region = "asia-south2",
    [string]$TemplatePath = "deploy/gcp/direct-service-smoke-job.template.yaml",
    [string]$OutputPath = "artifacts/direct-service-smoke-job.generated.yaml",
    [string]$DirectSmokeServiceAccount,
    [string]$SmokeSchoolId = "4",
    [string]$GcloudPath = "gcloud"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$sdkGcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
if ($GcloudPath -eq "gcloud" -and (Test-Path -LiteralPath $sdkGcloud)) {
    $GcloudPath = $sdkGcloud
}

if ([string]::IsNullOrWhiteSpace($DirectSmokeServiceAccount)) {
    $DirectSmokeServiceAccount = "direct-service-smoke@$ProjectId.iam.gserviceaccount.com"
}

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $repoRoot $Path
}

function Invoke-GcloudValue {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)

    $output = & $GcloudPath @Args
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud command failed with exit code ${LASTEXITCODE}: gcloud $($Args -join ' ')"
    }
    return "$($output -join "`n")".Trim()
}

function Escape-YamlValue {
    param([string]$Value)
    return $Value.Replace("\", "\\").Replace('"', '\"')
}

$template = Resolve-RepoPath $TemplatePath
$output = Resolve-RepoPath $OutputPath
$outputDirectory = Split-Path -Parent $output
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
}

$catalogUrl = Invoke-GcloudValue run services describe custoking-school-core-service `
    "--project=$ProjectId" `
    "--region=$Region" `
    "--format=value(status.url)"
$tenantUrl = Invoke-GcloudValue run services describe custoking-school-core-service `
    "--project=$ProjectId" `
    "--region=$Region" `
    "--format=value(status.url)"

$content = Get-Content -Raw -Path $template
$content = $content.Replace("__DIRECT_SMOKE_SERVICE_ACCOUNT__", (Escape-YamlValue $DirectSmokeServiceAccount))
$content = $content.Replace("__CATALOG_URL__", (Escape-YamlValue $catalogUrl))
$content = $content.Replace("__TENANT_URL__", (Escape-YamlValue $tenantUrl))
$content = $content.Replace("__SMOKE_SCHOOL_ID__", (Escape-YamlValue $SmokeSchoolId))
$content | Set-Content -Path $output -Encoding UTF8

Write-Host "Generated direct service smoke job: $output"
Write-Host "Catalog URL: $catalogUrl"
Write-Host "Tenant URL: $tenantUrl"
Write-Host "Service account: $DirectSmokeServiceAccount"

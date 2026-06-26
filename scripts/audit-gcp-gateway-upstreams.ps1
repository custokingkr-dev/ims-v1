param(
    [string]$ProjectId = "custoking-ims",
    [string]$Region = "asia-south2",
    [string]$GatewayService = "custoking-api-gateway",
    [string]$RetiredBackendService = "custoking-backend",
    [string]$GcloudPath = "gcloud",
    [string]$OutputJson
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

$gateway = Invoke-GcloudJson run services describe $GatewayService `
    "--project=$ProjectId" `
    "--region=$Region" `
    "--format=json"

$env = @($gateway.spec.template.spec.containers[0].env)
$upstreams = @($env | Where-Object { $_.name -like "*_UPSTREAM" } | ForEach-Object {
    [pscustomobject]@{
        name = $_.name
        value = $_.value
        pointsToRetiredBackend = "$($_.value)" -like "*$RetiredBackendService*"
    }
})

$backendRefs = @($upstreams | Where-Object { $_.pointsToRetiredBackend })
$result = [ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    projectId = $ProjectId
    region = $Region
    gatewayService = $GatewayService
    latestReadyRevision = $gateway.status.latestReadyRevisionName
    passed = $backendRefs.Count -eq 0
    retiredBackendReferenceCount = $backendRefs.Count
    upstreams = $upstreams
}

if (-not [string]::IsNullOrWhiteSpace($OutputJson)) {
    $result | ConvertTo-Json -Depth 5 | Set-Content -Path $OutputJson -Encoding UTF8
}

if ($backendRefs.Count -gt 0) {
    Write-Host "GCP gateway upstream audit failed: $($backendRefs.Count) upstream(s) still point to $RetiredBackendService"
    $backendRefs | Format-Table name, value -AutoSize
    exit 1
}

Write-Host "GCP gateway upstream audit passed: no upstream points to $RetiredBackendService"

param(
  [Parameter(Mandatory = $true)]
  [string]$Environment,

  [Parameter(Mandatory = $true)]
  [string]$ProjectId,

  [Parameter(Mandatory = $true)]
  [string]$Region,

  [string]$OutputPath = "release-evidence/smoke.json"
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

$gatewayService = "custoking-api-gateway-$Environment"
$gatewayUrl = & $GcloudCommand run services describe $gatewayService `
  --project=$ProjectId `
  --region=$Region `
  --format="value(status.url)"

if ($LASTEXITCODE -ne 0) {
  throw "Could not resolve Cloud Run URL for $gatewayService."
}

if ([string]::IsNullOrWhiteSpace($gatewayUrl)) {
  throw "Cloud Run service $gatewayService did not return a status.url."
}

$gatewayUrl = $gatewayUrl.TrimEnd("/")
$health = Invoke-RestMethod -Uri "$gatewayUrl/gateway-health" -TimeoutSec 30
if ($health.status -ne "UP") {
  throw "Gateway health is not UP: $($health | ConvertTo-Json -Compress)"
}

$outputDirectory = Split-Path -Parent $OutputPath
if ($outputDirectory) {
  New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}

[ordered]@{
  environment = $Environment
  service = $gatewayService
  gatewayUrl = $gatewayUrl
  endpoint = "$gatewayUrl/gateway-health"
  status = $health.status
  checkedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
} | ConvertTo-Json -Depth 5 | Set-Content -Path $OutputPath

Write-Host "Gateway health smoke passed for $gatewayService at $gatewayUrl."

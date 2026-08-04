param(
  [Parameter(Mandatory = $true)]
  [string]$ProjectId,

  [Parameter(Mandatory = $true)]
  [string]$Region,

  [Parameter(Mandatory = $true)]
  [ValidateSet("dev")]
  [string]$Environment,

  [Parameter(Mandatory = $true)]
  [string]$ImagesJson,

  [string]$OutputPath = "release-evidence/deployment.json"
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if (-not (Test-Path -LiteralPath $ImagesJson)) {
  throw "Release image evidence not found: $ImagesJson"
}

$images = @((Get-Content -Raw -Path $ImagesJson | ConvertFrom-Json).services)
$releaseOrder = @(
  "school-core-service",
  "identity-service",
  "operations-service",
  "billing-service",
  "platform-service",
  "api-gateway",
  "frontend"
)
$byService = @{}
foreach ($image in $images) {
  $byService[[string]$image.service] = $image
}

$deployments = @()
foreach ($service in $releaseOrder) {
  $image = $byService[$service]
  if (-not $image) {
    continue
  }

  $cloudRunService = "custoking-$service-$Environment"
  Write-Host "Deploying $cloudRunService with $($image.immutableRef)."
  & $GcloudCommand run deploy $cloudRunService `
    "--image=$($image.immutableRef)" `
    "--project=$ProjectId" `
    "--region=$Region" `
    --async `
    --quiet
  if ($LASTEXITCODE -ne 0) {
    throw "Cloud Run deployment failed for $cloudRunService."
  }

  $deployments += [ordered]@{
    service = $service
    cloudRunService = $cloudRunService
    image = $image.immutableRef
    status = "submitted"
  }
}

$outputDirectory = Split-Path -Parent $OutputPath
if ($outputDirectory) {
  New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}

[ordered]@{
  mode = "cloud-run-direct"
  environment = $Environment
  deployedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  services = $deployments
} | ConvertTo-Json -Depth 10 | Set-Content -Path $OutputPath

Write-Host "Submitted direct Cloud Run releases for $($deployments.Count) service(s)."

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
  $expectedRuntimeRef = if ($image.PSObject.Properties.Name -contains "runtimeRef") {
    [string]$image.runtimeRef
  } else {
    [string]$image.immutableRef
  }
  $serviceJson = & $GcloudCommand run services describe $cloudRunService `
    "--project=$ProjectId" `
    "--region=$Region" `
    --format=json
  if ($LASTEXITCODE -ne 0) {
    throw "Could not describe existing Cloud Run service $cloudRunService."
  }
  $serviceData = $serviceJson | ConvertFrom-Json
  $latestReady = [string]$serviceData.status.latestReadyRevisionName
  $latestCreated = [string]$serviceData.status.latestCreatedRevisionName
  $trafficReady = @($serviceData.status.traffic | Where-Object { $_.revisionName -eq $latestReady -and [int]$_.percent -eq 100 }).Count -gt 0
  $tracksLatest = @($serviceData.spec.traffic | Where-Object { $_.latestRevision -eq $true -and [int]$_.percent -eq 100 }).Count -gt 0

  if (-not [string]::IsNullOrWhiteSpace($latestReady) -and $latestReady -eq $latestCreated -and $trafficReady -and $tracksLatest) {
    $revisionJson = & $GcloudCommand run revisions describe $latestReady `
      "--project=$ProjectId" `
      "--region=$Region" `
      --format=json
    if ($LASTEXITCODE -eq 0) {
      $revisionData = $revisionJson | ConvertFrom-Json
      if ([string]$revisionData.status.imageDigest -eq $expectedRuntimeRef) {
        Write-Host "$cloudRunService already serves $expectedRuntimeRef; deployment skipped."
        $deployments += [ordered]@{
          service = $service
          cloudRunService = $cloudRunService
          image = $image.immutableRef
          runtimeImage = $expectedRuntimeRef
          revision = $latestReady
          status = "already-current"
        }
        continue
      }
    }
  }

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
    runtimeImage = $expectedRuntimeRef
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

$submittedCount = @($deployments | Where-Object { $_.status -eq "submitted" }).Count
$currentCount = @($deployments | Where-Object { $_.status -eq "already-current" }).Count
Write-Host "Direct Cloud Run release completed: submitted=$submittedCount alreadyCurrent=$currentCount."

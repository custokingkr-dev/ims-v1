param(
  [Parameter(Mandatory = $true)]
  [string]$ProjectId,

  [Parameter(Mandatory = $true)]
  [string]$Region,

  [Parameter(Mandatory = $true)]
  [ValidateSet("dev", "prod")]
  [string]$Environment,

  [Parameter(Mandatory = $true)]
  [string]$ImagesJson,

  [string]$OutputPath = "release-evidence/services-smoke.json",

  [int]$TimeoutMinutes = 15,

  [int]$PollSeconds = 10
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if (-not (Test-Path -LiteralPath $ImagesJson)) {
  throw "Release image evidence not found: $ImagesJson"
}

$images = @((Get-Content -Raw -Path $ImagesJson | ConvertFrom-Json).services)
$pending = @{}
foreach ($image in $images) {
  $pending[[string]$image.service] = $image
}

$checks = @()
$lastStatus = @{}
$trafficRequested = @{}
$deadline = (Get-Date).ToUniversalTime().AddMinutes($TimeoutMinutes)

while ($pending.Count -gt 0 -and (Get-Date).ToUniversalTime() -lt $deadline) {
  foreach ($serviceKey in @($pending.Keys)) {
    $image = $pending[$serviceKey]
    $serviceName = "custoking-$serviceKey-$Environment"
    $serviceJson = & $GcloudCommand run services describe $serviceName `
      "--project=$ProjectId" `
      "--region=$Region" `
      --format=json 2>$null
    if ($LASTEXITCODE -ne 0) {
      $lastStatus[$serviceKey] = "service describe failed"
      continue
    }

    $service = $serviceJson | ConvertFrom-Json
    $latestReady = [string]$service.status.latestReadyRevisionName
    $latestCreated = [string]$service.status.latestCreatedRevisionName
    $serviceReady = @($service.status.conditions | Where-Object { $_.type -eq "Ready" -and $_.status -eq "True" }).Count -gt 0
    if (-not $serviceReady -or [string]::IsNullOrWhiteSpace($latestCreated)) {
      $lastStatus[$serviceKey] = "latestReady=$latestReady latestCreated=$latestCreated serviceReady=$serviceReady"
      continue
    }

    $revisionJson = & $GcloudCommand run revisions describe $latestCreated `
      "--project=$ProjectId" `
      "--region=$Region" `
      --format=json 2>$null
    if ($LASTEXITCODE -ne 0) {
      $lastStatus[$serviceKey] = "revision describe failed for $latestCreated"
      continue
    }

    $revision = $revisionJson | ConvertFrom-Json
    $actualDigest = [string]$revision.status.imageDigest
    $expectedDigest = if ($image.PSObject.Properties.Name -contains "runtimeRef") {
      [string]$image.runtimeRef
    } else {
      [string]$image.immutableRef
    }
    $revisionReady = @($revision.status.conditions | Where-Object { $_.type -eq "Ready" -and $_.status -eq "True" }).Count -gt 0
    if (-not $revisionReady -or $actualDigest -ne $expectedDigest) {
      $lastStatus[$serviceKey] = "revisionReady=$revisionReady expected=$expectedDigest actual=$actualDigest"
      continue
    }

    $latestTraffic = @($service.status.traffic | Where-Object { $_.revisionName -eq $latestCreated -and [int]$_.percent -eq 100 }).Count -gt 0
    $tracksLatest = @($service.spec.traffic | Where-Object { $_.latestRevision -eq $true -and [int]$_.percent -eq 100 }).Count -gt 0
    if ($Environment -eq "dev" -and -not $tracksLatest) {
      if (-not $trafficRequested[$serviceKey]) {
        Write-Host "Restoring LATEST traffic mode for $serviceName."
        & $GcloudCommand run services update-traffic $serviceName `
          "--project=$ProjectId" `
          "--region=$Region" `
          --to-latest `
          --async `
          --quiet
        if ($LASTEXITCODE -ne 0) {
          throw "Could not restore LATEST traffic mode for $serviceName."
        }
        $trafficRequested[$serviceKey] = $true
      }
      $lastStatus[$serviceKey] = "ready expected digest; waiting for LATEST traffic mode"
      continue
    }

    if ($latestReady -ne $latestCreated -or -not $latestTraffic) {
      $lastStatus[$serviceKey] = "latestReady=$latestReady latestCreated=$latestCreated latestTraffic100=$latestTraffic tracksLatest=$tracksLatest"
      continue
    }

    $httpStatus = $null
    if ($serviceKey -eq "frontend") {
      try {
        $response = Invoke-WebRequest -Uri ([string]$service.status.url) -TimeoutSec 30 -UseBasicParsing
        $httpStatus = [int]$response.StatusCode
      } catch {
        $lastStatus[$serviceKey] = "frontend request failed: $($_.Exception.Message)"
        continue
      }
      if ($httpStatus -lt 200 -or $httpStatus -ge 400) {
        $lastStatus[$serviceKey] = "frontend HTTP $httpStatus"
        continue
      }
    }

    $checks += [ordered]@{
      service = $serviceKey
      cloudRunService = $serviceName
      revision = $latestCreated
      image = $actualDigest
      ready = $true
      latestTrafficPercent = 100
      httpStatus = $httpStatus
    }
    $pending.Remove($serviceKey)
    Write-Host "Verified $serviceName revision $latestCreated."
  }

  if ($pending.Count -gt 0) {
    $pendingSummary = @($pending.Keys | Sort-Object | ForEach-Object { "$_ [$($lastStatus[$_])]" }) -join "; "
    Write-Host "Waiting for Cloud Run: $pendingSummary"
    Start-Sleep -Seconds $PollSeconds
  }
}

if ($pending.Count -gt 0) {
  $pendingSummary = @($pending.Keys | Sort-Object | ForEach-Object { "$_ [$($lastStatus[$_])]" }) -join "; "
  throw "Timed out after $TimeoutMinutes minutes waiting for Cloud Run release verification: $pendingSummary"
}

$outputDirectory = Split-Path -Parent $OutputPath
if ($outputDirectory) {
  New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}

[ordered]@{
  environment = $Environment
  checkedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  services = @($checks | Sort-Object { $_.service })
} | ConvertTo-Json -Depth 10 | Set-Content -Path $OutputPath

Write-Host "Cloud Run release verification passed for $($checks.Count) service(s)."

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
    if (-not $serviceReady -or [string]::IsNullOrWhiteSpace($latestReady) -or $latestReady -ne $latestCreated) {
      $lastStatus[$serviceKey] = "latestReady=$latestReady latestCreated=$latestCreated serviceReady=$serviceReady"
      continue
    }

    $revisionJson = & $GcloudCommand run revisions describe $latestReady `
      "--project=$ProjectId" `
      "--region=$Region" `
      --format=json 2>$null
    if ($LASTEXITCODE -ne 0) {
      $lastStatus[$serviceKey] = "revision describe failed for $latestReady"
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
    $latestTraffic = @($service.status.traffic | Where-Object { $_.revisionName -eq $latestReady -and [int]$_.percent -eq 100 }).Count -gt 0
    if (-not $revisionReady -or $actualDigest -ne $expectedDigest -or -not $latestTraffic) {
      $lastStatus[$serviceKey] = "revisionReady=$revisionReady expected=$expectedDigest actual=$actualDigest latestTraffic100=$latestTraffic"
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
      revision = $latestReady
      image = $actualDigest
      ready = $true
      latestTrafficPercent = 100
      httpStatus = $httpStatus
    }
    $pending.Remove($serviceKey)
    Write-Host "Verified $serviceName revision $latestReady."
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

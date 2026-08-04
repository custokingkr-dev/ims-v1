param(
  [string]$ProjectId = "custoking",

  [string]$Region = "asia-south2",

  [string[]]$Environment = @("dev", "prod"),

  [int]$KeepLatestSucceeded = 5,

  [switch]$PruneSucceeded,

  [switch]$Execute
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

$services = @(
  "school-core-service",
  "identity-service",
  "operations-service",
  "billing-service",
  "platform-service",
  "api-gateway",
  "frontend"
)

$environmentNames = @($Environment | ForEach-Object {
  $_ -split ","
} | ForEach-Object {
  $_.Trim()
} | Where-Object {
  -not [string]::IsNullOrWhiteSpace($_)
})

foreach ($envName in $environmentNames) {
  if (@("dev", "prod") -notcontains $envName) {
    throw "Unsupported environment '$envName'. Expected dev or prod."
  }
}

function Invoke-GcloudJson([string[]]$Arguments) {
  $output = & $GcloudCommand @Arguments --format=json
  if ($LASTEXITCODE -ne 0) {
    throw "gcloud $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
  }
  if ([string]::IsNullOrWhiteSpace($output)) {
    return @()
  }
  return @($output | ConvertFrom-Json)
}

$candidates = @()

foreach ($envName in $environmentNames) {
  foreach ($service in $services) {
    $pipeline = "custoking-$service-$envName"
    $releases = Invoke-GcloudJson @(
      "deploy", "releases", "list",
      "--project=$ProjectId",
      "--region=$Region",
      "--delivery-pipeline=$pipeline",
      "--sort-by=~createTime",
      "--limit=50"
    )

    $keepNames = @{}
    if ($PruneSucceeded) {
      $latestSucceeded = $releases |
        Where-Object { $_.renderState -eq "SUCCEEDED" -and -not $_.abandoned } |
        Select-Object -First $KeepLatestSucceeded
      foreach ($release in $latestSucceeded) {
        $keepNames[$release.name] = $true
      }
    }

    foreach ($release in $releases) {
      if ($release.abandoned) {
        continue
      }
      $shouldAbandon = $false
      if (@("FAILED", "CANCELLED", "CANCELED") -contains [string]$release.renderState) {
        $shouldAbandon = $true
      } elseif ($PruneSucceeded -and $release.renderState -eq "SUCCEEDED" -and -not $keepNames.ContainsKey($release.name)) {
        $shouldAbandon = $true
      }

      if ($shouldAbandon) {
        $releaseId = ([string]$release.name).Split("/")[-1]
        $candidates += [ordered]@{
          environment = $envName
          service = $service
          pipeline = $pipeline
          release = $releaseId
          renderState = $release.renderState
          createTime = $release.createTime
        }
      }
    }
  }
}

if (-not $candidates) {
  Write-Host "No stale Cloud Deploy releases found."
  return
}

$candidates | Format-Table -AutoSize

if (-not $Execute) {
  Write-Host "Dry run only. Re-run with -Execute to abandon these stale releases."
  return
}

foreach ($candidate in $candidates) {
  $pipeline = [string]$candidate.pipeline
  $release = [string]$candidate.release
  Write-Host "Abandoning $pipeline/$release."
  & $GcloudCommand deploy releases abandon $release `
    --project=$ProjectId `
    --region=$Region `
    --delivery-pipeline=$pipeline `
    --quiet
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to abandon $pipeline/$release."
  }
}

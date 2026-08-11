param(
  [ValidateSet("Soak", "MorningBurst", "MixedMorning")]
  [string]$Profile = "Soak",
  [string]$ProjectId = "custoking",
  [string]$CloudSqlInstance = "custoking-db-dev",
  [string]$BaseUrl = "https://custoking-api-gateway-dev-l7mhms5c2a-em.a.run.app",
  [int]$PeakVus = 0,
  [string]$Hold = "",
  [ValidateRange(1, 10)]
  [int]$ConsecutiveGuardrailBreaches = 3,
  [double]$CpuStopRatio = 0.80,
  [ValidateRange(1, 100)]
  [double]$MemoryStopPercentage = 90,
  [int]$ConnectionStopCount = 140,
  [ValidateRange(1, 10)]
  [int]$ConsecutiveMonitoringFailures = 3,
  [string]$K6Image = "grafana/k6:2.0.0@sha256:a33a0cfdc4d2483d6b7a3a22e726a499ff2831a671a49239104cd34a9937523c",
  [switch]$AllowScaleWrites,
  [string]$EvidenceDirectory = "artifacts/load-certification"
)

$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
if (-not $AllowScaleWrites) {
  throw "Load certification updates the reserved synthetic attendance fixture. Pass -AllowScaleWrites."
}
if ($BaseUrl -notmatch '-dev-' -and $BaseUrl -notmatch 'localhost') {
  throw "Load certification is restricted to dev or localhost."
}
if ([string]::IsNullOrWhiteSpace($env:K6_ACCESS_TOKENS) -and
    ([string]::IsNullOrWhiteSpace($env:K6_LOGIN_EMAIL) -or
     [string]::IsNullOrWhiteSpace($env:K6_LOGIN_PASSWORD))) {
  throw "Provide K6_ACCESS_TOKENS or K6_LOGIN_EMAIL/K6_LOGIN_PASSWORD. Secrets are never written to evidence."
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw "Docker is required to run the pinned k6 container."
}

$defaults = if ($Profile -eq "Soak") {
  @{ PeakVus = 300; Hold = "4h"; RampUp = "5m"; RampDown = "5m" }
} else {
  @{ PeakVus = 300; Hold = "15m"; RampUp = "30s"; RampDown = "2m" }
}
if ($PeakVus -le 0) { $PeakVus = $defaults.PeakVus }
if ([string]::IsNullOrWhiteSpace($Hold)) { $Hold = $defaults.Hold }
if ($PeakVus -gt 300) {
  throw "The certified database shape has a 300-VU ceiling; higher values require a separate sizing experiment."
}

$instanceState = ((& $gcloud sql instances describe $CloudSqlInstance "--project=$ProjectId" `
  --format="value(state)") -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $instanceState -ne "RUNNABLE") {
  throw "Cloud SQL $CloudSqlInstance must be RUNNABLE before certification (current: $instanceState)."
}

$databaseId = "$ProjectId`:$CloudSqlInstance"
$monitoringHeaders = $null
$monitoringTokenRefreshAt = [datetime]::MinValue

function Get-MonitoringHeaders([switch]$ForceRefresh) {
  if ($ForceRefresh -or $null -eq $script:monitoringHeaders -or
      [datetime]::UtcNow -ge $script:monitoringTokenRefreshAt) {
    $accessToken = ((& $gcloud auth print-access-token) -join "").Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($accessToken)) {
      throw "Could not refresh the Google Cloud access token for guardrail monitoring."
    }
    $script:monitoringHeaders = @{ Authorization = "Bearer $accessToken" }
    $script:monitoringTokenRefreshAt = [datetime]::UtcNow.AddMinutes(40)
  }
  return $script:monitoringHeaders
}

function Get-LatestMetricPoint(
  [string]$MetricType,
  [string]$ValueProperty,
  [string]$AdditionalFilter = ""
) {
  $end = [datetime]::UtcNow
  $start = $end.AddMinutes(-10)
  $filter = "metric.type=`"$MetricType`" AND resource.labels.database_id=`"$databaseId`""
  if (-not [string]::IsNullOrWhiteSpace($AdditionalFilter)) {
    $filter += " AND $AdditionalFilter"
  }
  $uri = "https://monitoring.googleapis.com/v3/projects/$ProjectId/timeSeries" +
    "?filter=$([uri]::EscapeDataString($filter))" +
    "&interval.startTime=$([uri]::EscapeDataString($start.ToString('o')))" +
    "&interval.endTime=$([uri]::EscapeDataString($end.ToString('o')))" +
    "&aggregation.alignmentPeriod=60s&aggregation.perSeriesAligner=ALIGN_MAX" +
    "&aggregation.crossSeriesReducer=REDUCE_MAX&view=FULL"
  try {
    $response = Invoke-RestMethod -Uri $uri -Headers (Get-MonitoringHeaders) -TimeoutSec 30
  } catch {
    $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    if ($statusCode -ne 401) { throw }
    # gcloud can return a still-cached token with substantially less than its nominal one-hour
    # lifetime remaining. Refresh and retry the read once instead of converting token expiry into
    # a false load-capacity failure.
    $response = Invoke-RestMethod -Uri $uri -Headers (Get-MonitoringHeaders -ForceRefresh) -TimeoutSec 30
  }
  $points = @($response.timeSeries | ForEach-Object { $_.points } | Where-Object {
      $null -ne $_.value.$ValueProperty
  } | Sort-Object { [datetime]$_.interval.endTime } -Descending)
  if ($points.Count -eq 0) { return $null }
  return [pscustomobject]@{
    timestampUtc = ([datetime]$points[0].interval.endTime).ToUniversalTime().ToString("o")
    value = [double]$points[0].value.$ValueProperty
  }
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$loadTests = Join-Path $repoRoot "load-tests"
$evidenceRoot = Join-Path $repoRoot $EvidenceDirectory
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$stamp = [datetime]::UtcNow.ToString("yyyyMMddHHmmss")
$containerName = "ims-k6-$($Profile.ToLowerInvariant())-$stamp"
$summaryName = "$($Profile.ToLowerInvariant())-$stamp-k6-summary.json"
$stdoutPath = Join-Path $evidenceRoot "$($Profile.ToLowerInvariant())-$stamp.stdout.log"
$stderrPath = Join-Path $evidenceRoot "$($Profile.ToLowerInvariant())-$stamp.stderr.log"
$evidencePath = Join-Path $evidenceRoot "$($Profile.ToLowerInvariant())-$stamp-evidence.json"
$startedAt = [datetime]::UtcNow
$abortedReason = $null
$cpuBreaches = 0
$connectionBreaches = 0
$memoryBreaches = 0
$monitoringFailures = 0
$maxCpu = 0.0
$maxConnections = 0
$maxMemory = 0.0
$lastCpuTimestamp = $null
$lastConnectionTimestamp = $null
$lastMemoryTimestamp = $null
$monitoringSamples = [System.Collections.Generic.List[object]]::new()

$testScript = if ($Profile -eq "MixedMorning") {
  "/scripts/school-day-mixed-read.js"
} else {
  "/scripts/school-day-attendance-write.js"
}

$arguments = @(
  "run", "--name", $containerName,
  "-e", "BASE_URL=$BaseUrl", "-e", "K6_ACCESS_TOKENS", "-e", "K6_LOGIN_EMAIL",
  "-e", "K6_LOGIN_PASSWORD", "-e", "ALLOW_SCALE_WRITES=1",
  "-e", "SCALE_SCHOOL_COUNT=100", "-e", "SCALE_TOTAL_STUDENTS=300000",
  "-e", "SCALE_LARGE_SCHOOL_STUDENTS=10000", "-e", "PEAK_VUS=$PeakVus",
  "-e", "RAMP_UP=$($defaults.RampUp)", "-e", "HOLD=$Hold", "-e", "RAMP_DOWN=$($defaults.RampDown)",
  "-v", "${loadTests}:/scripts:ro", "-v", "${evidenceRoot}:/results",
  $K6Image, "run", "--summary-export=/results/$summaryName",
  "--summary-trend-stats=avg,min,med,max,p(90),p(95),p(99)",
  $testScript
)

$process = Start-Process docker -ArgumentList $arguments -NoNewWindow -PassThru `
  -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
$null = $process.Handle # Force Windows PowerShell to retain an exit-code-capable process handle.
$k6ExitCode = $null
try {
  while (-not $process.HasExited) {
    Start-Sleep -Seconds 60
    $process.Refresh()
    if ($process.HasExited) { break }

    try {
      $cpu = Get-LatestMetricPoint "cloudsql.googleapis.com/database/cpu/utilization" "doubleValue"
      $memory = Get-LatestMetricPoint `
        "cloudsql.googleapis.com/database/memory/components" `
        "doubleValue" `
        'metric.labels.component="Usage"'
      $connections = Get-LatestMetricPoint "cloudsql.googleapis.com/database/postgresql/num_backends" "int64Value"
      if ($null -eq $cpu -or $null -eq $memory -or $null -eq $connections) {
        throw "Cloud Monitoring did not return all required guardrail metrics."
      }
      $monitoringFailures = 0
      $maxCpu = [math]::Max($maxCpu, $cpu.value)
      $maxMemory = [math]::Max($maxMemory, $memory.value)
      $maxConnections = [math]::Max($maxConnections, [int]$connections.value)
      if ($cpu.timestampUtc -ne $lastCpuTimestamp) {
        $cpuBreaches = if ($cpu.value -ge $CpuStopRatio) { $cpuBreaches + 1 } else { 0 }
        $lastCpuTimestamp = $cpu.timestampUtc
      }
      if ($connections.timestampUtc -ne $lastConnectionTimestamp) {
        $connectionBreaches = if ($connections.value -ge $ConnectionStopCount) { $connectionBreaches + 1 } else { 0 }
        $lastConnectionTimestamp = $connections.timestampUtc
      }
      if ($memory.timestampUtc -ne $lastMemoryTimestamp) {
        $memoryBreaches = if ($memory.value -ge $MemoryStopPercentage) { $memoryBreaches + 1 } else { 0 }
        $lastMemoryTimestamp = $memory.timestampUtc
      }
      $monitoringSamples.Add([ordered]@{
          observedAtUtc = [datetime]::UtcNow.ToString("o")
          cpuMetricAtUtc = $cpu.timestampUtc
          cpuRatio = [math]::Round($cpu.value, 4)
          memoryMetricAtUtc = $memory.timestampUtc
          memoryUsagePercentage = [math]::Round($memory.value, 4)
          connectionsMetricAtUtc = $connections.timestampUtc
          connections = [int]$connections.value
      })
    } catch {
      $monitoringFailures++
      $monitoringSamples.Add([ordered]@{
          observedAtUtc = [datetime]::UtcNow.ToString("o")
          error = $_.Exception.Message
      })
    }
    if ($cpuBreaches -ge $ConsecutiveGuardrailBreaches) {
      $abortedReason = "Cloud SQL CPU remained at or above $CpuStopRatio for $cpuBreaches samples."
    } elseif ($connectionBreaches -ge $ConsecutiveGuardrailBreaches) {
      $abortedReason = "Cloud SQL connections remained at or above $ConnectionStopCount for $connectionBreaches samples."
    } elseif ($memoryBreaches -ge $ConsecutiveGuardrailBreaches) {
      $abortedReason = "Cloud SQL Usage memory component remained at or above $MemoryStopPercentage% for $memoryBreaches samples."
    } elseif ($monitoringFailures -ge $ConsecutiveMonitoringFailures) {
      $abortedReason = "Cloud Monitoring guardrail collection failed for $monitoringFailures consecutive samples."
    }
    if ($abortedReason) {
      & docker stop $containerName | Out-Null
      break
    }
  }
  $process.WaitForExit()
  $process.Refresh()
  if ($process.HasExited) {
    $k6ExitCode = $process.ExitCode
  }
} finally {
  & docker rm -f $containerName *> $null
}

$evidence = [ordered]@{
  profile = $Profile
  environment = "dev"
  baseUrl = $BaseUrl
  cloudSqlInstance = $CloudSqlInstance
  peakVus = $PeakVus
  hold = $Hold
  k6Image = $K6Image
  startedAtUtc = $startedAt.ToString("o")
  completedAtUtc = [datetime]::UtcNow.ToString("o")
  k6ExitCode = $k6ExitCode
  abortedReason = $abortedReason
  guardrails = [ordered]@{
    cpuStopRatio = $CpuStopRatio
    memoryMetric = "cloudsql.googleapis.com/database/memory/components"
    memoryComponent = "Usage"
    memoryStopPercentage = $MemoryStopPercentage
    connectionStopCount = $ConnectionStopCount
    consecutiveSamples = $ConsecutiveGuardrailBreaches
    maximumObservedCpuRatio = [math]::Round($maxCpu, 4)
    maximumObservedMemoryUsagePercentage = [math]::Round($maxMemory, 4)
    maximumObservedConnections = $maxConnections
    monitoringFailureStopCount = $ConsecutiveMonitoringFailures
    samples = @($monitoringSamples)
  }
  k6Summary = $summaryName
  tokenMaterialPersisted = $false
}
$evidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
$evidence | ConvertTo-Json -Depth 6
if ($abortedReason) { throw $abortedReason }
if ($null -eq $k6ExitCode) { throw "k6 exit code was unavailable; certification fails closed." }
if ($k6ExitCode -ne 0) { throw "k6 exited with code $k6ExitCode." }

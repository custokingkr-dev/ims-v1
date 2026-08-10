param(
  [ValidateSet("Soak", "MorningBurst")]
  [string]$Profile = "Soak",
  [string]$ProjectId = "custoking",
  [string]$CloudSqlInstance = "custoking-db-dev",
  [string]$BaseUrl = "https://custoking-api-gateway-dev-l7mhms5c2a-em.a.run.app",
  [int]$PeakVus = 0,
  [string]$Hold = "",
  [ValidateRange(1, 10)]
  [int]$ConsecutiveGuardrailBreaches = 3,
  [double]$CpuStopRatio = 0.80,
  [int]$ConnectionStopCount = 140,
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
if ([string]::IsNullOrWhiteSpace($env:K6_ACCESS_TOKENS)) {
  throw "K6_ACCESS_TOKENS must contain short-lived dev tokens. Tokens are never written to evidence."
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

$accessToken = ((& $gcloud auth print-access-token) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($accessToken)) {
  throw "Could not obtain a Google Cloud access token for guardrail monitoring."
}
$headers = @{ Authorization = "Bearer $accessToken" }
$databaseId = "$ProjectId`:$CloudSqlInstance"

function Get-LatestMetricValue([string]$MetricType, [string]$ValueProperty) {
  $end = [datetime]::UtcNow
  $start = $end.AddMinutes(-5)
  $filter = "metric.type=`"$MetricType`" AND resource.labels.database_id=`"$databaseId`""
  $uri = "https://monitoring.googleapis.com/v3/projects/$ProjectId/timeSeries" +
    "?filter=$([uri]::EscapeDataString($filter))" +
    "&interval.startTime=$([uri]::EscapeDataString($start.ToString('o')))" +
    "&interval.endTime=$([uri]::EscapeDataString($end.ToString('o')))" +
    "&aggregation.alignmentPeriod=60s&aggregation.perSeriesAligner=ALIGN_MAX" +
    "&aggregation.crossSeriesReducer=REDUCE_MAX&view=FULL"
  $response = Invoke-RestMethod -Uri $uri -Headers $headers -TimeoutSec 30
  $values = @($response.timeSeries | ForEach-Object { $_.points } | ForEach-Object {
      if ($null -ne $_.value.$ValueProperty) { [double]$_.value.$ValueProperty }
  })
  if ($values.Count -eq 0) { return $null }
  return [double](($values | Measure-Object -Maximum).Maximum)
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
$maxCpu = 0.0
$maxConnections = 0

$arguments = @(
  "run", "--name", $containerName,
  "-e", "BASE_URL=$BaseUrl", "-e", "K6_ACCESS_TOKENS", "-e", "ALLOW_SCALE_WRITES=1",
  "-e", "SCALE_SCHOOL_COUNT=100", "-e", "SCALE_TOTAL_STUDENTS=300000",
  "-e", "SCALE_LARGE_SCHOOL_STUDENTS=10000", "-e", "PEAK_VUS=$PeakVus",
  "-e", "RAMP_UP=$($defaults.RampUp)", "-e", "HOLD=$Hold", "-e", "RAMP_DOWN=$($defaults.RampDown)",
  "-v", "${loadTests}:/scripts:ro", "-v", "${evidenceRoot}:/results",
  $K6Image, "run", "--summary-export=/results/$summaryName",
  "/scripts/school-day-attendance-write.js"
)

$process = Start-Process docker -ArgumentList $arguments -NoNewWindow -PassThru `
  -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
try {
  while (-not $process.HasExited) {
    Start-Sleep -Seconds 60
    $process.Refresh()
    if ($process.HasExited) { break }

    $cpu = Get-LatestMetricValue "cloudsql.googleapis.com/database/cpu/utilization" "doubleValue"
    $connections = Get-LatestMetricValue "cloudsql.googleapis.com/database/postgresql/num_backends" "int64Value"
    if ($null -ne $cpu) {
      $maxCpu = [math]::Max($maxCpu, $cpu)
      $cpuBreaches = if ($cpu -ge $CpuStopRatio) { $cpuBreaches + 1 } else { 0 }
    }
    if ($null -ne $connections) {
      $maxConnections = [math]::Max($maxConnections, [int]$connections)
      $connectionBreaches = if ($connections -ge $ConnectionStopCount) { $connectionBreaches + 1 } else { 0 }
    }
    if ($cpuBreaches -ge $ConsecutiveGuardrailBreaches) {
      $abortedReason = "Cloud SQL CPU remained at or above $CpuStopRatio for $cpuBreaches samples."
    } elseif ($connectionBreaches -ge $ConsecutiveGuardrailBreaches) {
      $abortedReason = "Cloud SQL connections remained at or above $ConnectionStopCount for $connectionBreaches samples."
    }
    if ($abortedReason) {
      & docker stop $containerName | Out-Null
      break
    }
  }
  $process.WaitForExit()
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
  k6ExitCode = $process.ExitCode
  abortedReason = $abortedReason
  guardrails = [ordered]@{
    cpuStopRatio = $CpuStopRatio
    connectionStopCount = $ConnectionStopCount
    consecutiveSamples = $ConsecutiveGuardrailBreaches
    maximumObservedCpuRatio = [math]::Round($maxCpu, 4)
    maximumObservedConnections = $maxConnections
  }
  k6Summary = $summaryName
  tokenMaterialPersisted = $false
}
$evidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
$evidence | ConvertTo-Json -Depth 6
if ($abortedReason) { throw $abortedReason }
if ($process.ExitCode -ne 0) { throw "k6 exited with code $($process.ExitCode)." }

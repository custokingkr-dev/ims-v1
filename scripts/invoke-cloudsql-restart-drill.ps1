param(
  [string]$ProjectId = "custoking",
  [string]$Region = "asia-south2",
  [ValidateSet("dev")]
  [string]$Environment = "dev",
  [string]$Instance = "custoking-db-dev",
  [ValidateRange(5, 60)]
  [int]$TimeoutMinutes = 20,
  [string]$EvidenceDirectory = "artifacts/recovery",
  [switch]$Apply,
  [switch]$AllowDevDisruption
)

$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
if ($Apply -and -not $AllowDevDisruption) {
  throw "A restart disconnects dev database sessions. Pass -AllowDevDisruption after coordinating the test window."
}

function Invoke-Gcloud {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  & $gcloud @Arguments
  if ($LASTEXITCODE -ne 0) { throw "gcloud command failed: $($Arguments -join ' ')" }
}

$instanceJson = ((Invoke-Gcloud sql instances describe $Instance "--project=$ProjectId" --format=json) -join "`n") | ConvertFrom-Json
if ([string]$instanceJson.state -ne "RUNNABLE") {
  throw "Restart drill requires a RUNNABLE dev instance; current state is $($instanceJson.state)."
}

$serviceNames = @("identity-service", "school-core-service", "operations-service", "platform-service", "billing-service")
$services = @()
foreach ($logicalName in $serviceNames) {
  $name = "custoking-$logicalName-$Environment"
  $description = ((Invoke-Gcloud run services describe $name "--project=$ProjectId" `
    "--region=$Region" --format=json) -join "`n") | ConvertFrom-Json
  $services += [ordered]@{
    name = $name
    url = [string]$description.status.url
    latestReadyRevision = [string]$description.status.latestReadyRevisionName
  }
}

[ordered]@{
  environment = $Environment
  instance = $Instance
  initialState = $instanceJson.state
  services = $services
  applyRequested = [bool]$Apply
} | ConvertTo-Json -Depth 5
if (-not $Apply) {
  Write-Host "Dry run only. Re-run with -Apply -AllowDevDisruption during an approved window."
  exit 0
}

$startedAt = [datetime]::UtcNow
Invoke-Gcloud sql instances restart $Instance "--project=$ProjectId" --quiet
$sqlReadyAt = [datetime]::UtcNow
$deadline = [datetime]::UtcNow.AddMinutes($TimeoutMinutes)
$pending = [System.Collections.Generic.List[object]]::new()
foreach ($service in $services) { $pending.Add($service) }
$healthEvidence = [System.Collections.Generic.List[object]]::new()

while ($pending.Count -gt 0 -and [datetime]::UtcNow -lt $deadline) {
  foreach ($service in @($pending | ForEach-Object { $_ })) {
    try {
      # User ADC cannot mint an audience-overridden token (that flag is service-account-only).
      # Cloud Run accepts the Google-signed user identity token for an IAM-authorized operator.
      $identityToken = ((Invoke-Gcloud auth print-identity-token) -join "").Trim()
      $response = Invoke-WebRequest -Uri "$($service.url)/actuator/health" `
        -Headers @{ Authorization = "Bearer $identityToken" } -TimeoutSec 20 -UseBasicParsing
      if ($response.StatusCode -eq 200) {
        $readyAt = [datetime]::UtcNow
        $healthEvidence.Add([ordered]@{
            service = $service.name
            status = 200
            recoveredAtUtc = $readyAt.ToString("o")
            recoverySeconds = [math]::Round(($readyAt - $startedAt).TotalSeconds, 2)
        })
        $pending.Remove($service)
      }
    } catch {
      # A connection error or non-2xx is expected while Cloud SQL and pools recover.
    }
  }
  if ($pending.Count -gt 0) { Start-Sleep -Seconds 10 }
}
if ($pending.Count -gt 0) {
  throw "Timed out waiting for service recovery: $((@($pending | ForEach-Object { $_.name })) -join ', ')"
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$outputRoot = Join-Path $repoRoot $EvidenceDirectory
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$evidencePath = Join-Path $outputRoot "cloudsql-restart-$Environment-$($startedAt.ToString('yyyyMMddHHmmss')).json"
$completedAt = [datetime]::UtcNow
[ordered]@{
  project = $ProjectId
  environment = $Environment
  instance = $Instance
  startedAtUtc = $startedAt.ToString("o")
  sqlCommandCompletedAtUtc = $sqlReadyAt.ToString("o")
  sqlRestartSeconds = [math]::Round(($sqlReadyAt - $startedAt).TotalSeconds, 2)
  allServicesRecoveredAtUtc = $completedAt.ToString("o")
  applicationRecoverySeconds = [math]::Round(($completedAt - $startedAt).TotalSeconds, 2)
  services = @($healthEvidence)
  status = "PASSED"
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
Write-Host "Controlled restart drill passed; evidence: $evidencePath"

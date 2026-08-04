param(
  [Parameter(Mandatory = $true)]
  [string]$ProjectId,

  [Parameter(Mandatory = $true)]
  [string]$Region,

  [Parameter(Mandatory = $true)]
  [string]$Pipeline,

  [Parameter(Mandatory = $true)]
  [string]$Release,

  [Parameter(Mandatory = $true)]
  [string]$Rollout,

  [int]$TimeoutMinutes = 45,

  [int]$PollSeconds = 15,

  [switch]$AutoAdvanceCanary
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

function Invoke-GcloudJson([string[]]$Arguments) {
  $output = & $GcloudCommand @Arguments --format=json
  if ($LASTEXITCODE -ne 0) {
    throw "gcloud $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
  }
  if ([string]::IsNullOrWhiteSpace($output)) {
    return $null
  }
  return $output | ConvertFrom-Json
}

function Invoke-Gcloud([string[]]$Arguments) {
  & $GcloudCommand @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "gcloud $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
  }
}

$deadline = (Get-Date).ToUniversalTime().AddMinutes($TimeoutMinutes)
$terminalFailureStates = @("FAILED", "CANCELLED", "CANCELED", "HALTED", "APPROVAL_REJECTED")
$lastState = ""
$lastPhaseSummary = ""

while ((Get-Date).ToUniversalTime() -lt $deadline) {
  $rolloutData = Invoke-GcloudJson @(
    "deploy", "rollouts", "describe", $Rollout,
    "--project=$ProjectId",
    "--region=$Region",
    "--delivery-pipeline=$Pipeline",
    "--release=$Release"
  )

  $state = [string]$rolloutData.state
  $phaseSummary = (@($rolloutData.phases) | ForEach-Object { "$($_.id):$($_.state)" }) -join ", "
  if ($state -ne $lastState -or $phaseSummary -ne $lastPhaseSummary) {
    Write-Host "Rollout $Pipeline/$Release/$Rollout state=$state phases=[$phaseSummary]"
    $lastState = $state
    $lastPhaseSummary = $phaseSummary
  }

  if ($state -eq "SUCCEEDED") {
    return
  }

  if ($terminalFailureStates -contains $state) {
    throw "Cloud Deploy rollout $Pipeline/$Release/$Rollout ended in $state."
  }

  $phases = @($rolloutData.phases)
  $hasRunningPhase = $false
  foreach ($phase in $phases) {
    if (@("IN_PROGRESS", "RUNNING") -contains [string]$phase.state) {
      $hasRunningPhase = $true
      break
    }
  }

  if ($AutoAdvanceCanary -and -not $hasRunningPhase) {
    $nextPhase = $phases | Where-Object {
      @("PENDING", "NOT_STARTED") -contains [string]$_.state
    } | Select-Object -First 1

    if ($nextPhase) {
      Write-Host "Advancing rollout $Rollout to phase $($nextPhase.id)."
      Invoke-Gcloud @(
        "deploy", "rollouts", "advance", $Rollout,
        "--project=$ProjectId",
        "--region=$Region",
        "--delivery-pipeline=$Pipeline",
        "--release=$Release",
        "--phase-id=$($nextPhase.id)",
        "--quiet"
      )
    }
  }

  Start-Sleep -Seconds $PollSeconds
}

throw "Timed out after $TimeoutMinutes minutes waiting for Cloud Deploy rollout $Pipeline/$Release/$Rollout."

param(
  [Parameter(Mandatory = $true)]
  [string]$ProjectId,

  [Parameter(Mandatory = $true)]
  [string]$Region,

  [Parameter(Mandatory = $true)]
  [string]$DeploymentJson,

  [int]$TimeoutMinutes = 45,

  [int]$PollSeconds = 15,

  [switch]$AutoAdvanceCanary
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if (-not (Test-Path -LiteralPath $DeploymentJson)) {
  throw "Cloud Deploy evidence not found: $DeploymentJson"
}

$deployments = @((Get-Content -Raw -Path $DeploymentJson | ConvertFrom-Json).services)
$pending = @{}
foreach ($deployment in $deployments) {
  $pending[[string]$deployment.pipeline] = $deployment
}

$deadline = (Get-Date).ToUniversalTime().AddMinutes($TimeoutMinutes)
$terminalFailureStates = @("FAILED", "CANCELLED", "CANCELED", "HALTED", "APPROVAL_REJECTED")
$lastSummaries = @{}
$advancedPhases = @{}

while ($pending.Count -gt 0 -and (Get-Date).ToUniversalTime() -lt $deadline) {
  foreach ($pipeline in @($pending.Keys)) {
    $deployment = $pending[$pipeline]
    $json = & $GcloudCommand deploy rollouts describe $deployment.rollout `
      "--project=$ProjectId" `
      "--region=$Region" `
      "--delivery-pipeline=$pipeline" `
      "--release=$($deployment.release)" `
      --format=json
    if ($LASTEXITCODE -ne 0) {
      throw "Could not describe Cloud Deploy rollout $pipeline/$($deployment.release)/$($deployment.rollout)."
    }
    $rollout = $json | ConvertFrom-Json
    $state = [string]$rollout.state
    $phases = @($rollout.phases)
    $phaseSummary = ($phases | ForEach-Object { "$($_.id):$($_.state)" }) -join ", "
    $summary = "$state [$phaseSummary]"
    if ($lastSummaries[$pipeline] -ne $summary) {
      Write-Host "Rollout $pipeline state=$summary"
      $lastSummaries[$pipeline] = $summary
    }

    if ($state -eq "SUCCEEDED") {
      $pending.Remove($pipeline)
      continue
    }
    if ($terminalFailureStates -contains $state) {
      throw "Cloud Deploy rollout $pipeline/$($deployment.release)/$($deployment.rollout) ended in $state."
    }

    # PENDING_RELEASE phases are visible before Cloud Deploy is ready to accept advance requests.
    if ($AutoAdvanceCanary -and $state -eq "IN_PROGRESS") {
      $hasRunningPhase = @($phases | Where-Object { @("IN_PROGRESS", "RUNNING") -contains [string]$_.state }).Count -gt 0
      if (-not $hasRunningPhase) {
        $nextPhase = $phases | Where-Object { @("PENDING", "NOT_STARTED") -contains [string]$_.state } | Select-Object -First 1
        $advanceKey = if ($nextPhase) { "$pipeline/$($deployment.rollout)/$($nextPhase.id)" } else { "" }
        if ($nextPhase -and -not $advancedPhases[$advanceKey]) {
          Write-Host "Advancing $pipeline rollout $($deployment.rollout) to $($nextPhase.id)."
          & $GcloudCommand deploy rollouts advance $deployment.rollout `
            "--project=$ProjectId" `
            "--region=$Region" `
            "--delivery-pipeline=$pipeline" `
            "--release=$($deployment.release)" `
            "--phase-id=$($nextPhase.id)" `
            --quiet
          if ($LASTEXITCODE -ne 0) {
            throw "Could not advance $pipeline rollout $($deployment.rollout) to $($nextPhase.id)."
          }
          $advancedPhases[$advanceKey] = $true
        }
      }
    }
  }

  if ($pending.Count -gt 0) {
    Start-Sleep -Seconds $PollSeconds
  }
}

if ($pending.Count -gt 0) {
  throw "Timed out after $TimeoutMinutes minutes waiting for Cloud Deploy rollouts: $($pending.Keys -join ', ')."
}

Write-Host "All $($deployments.Count) Cloud Deploy rollout(s) succeeded."

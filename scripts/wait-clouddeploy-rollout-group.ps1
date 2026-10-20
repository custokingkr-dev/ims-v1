param(
  [Parameter(Mandatory = $true)][string]$ProjectId,
  [Parameter(Mandatory = $true)][string]$Region,
  [Parameter(Mandatory = $true)][object[]]$Rollouts,
  [ValidateRange(1, 120)][int]$TimeoutMinutes = 45,
  [ValidateRange(1, 60)][int]$PollSeconds = 5,
  [switch]$AutoAdvanceCanary
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
if ($Rollouts.Count -lt 1 -or $Rollouts.Count -gt 2) { throw "A rollout group must contain one or two services." }
$keys = @($Rollouts | ForEach-Object { "$($_.pipeline)/$($_.release)/$($_.rollout)" })
if (@($keys | Sort-Object -Unique).Count -ne $keys.Count) { throw "Duplicate rollout in group." }
foreach ($entry in $Rollouts) {
  foreach ($field in @('pipeline', 'release', 'rollout')) {
    if ([string]$entry.$field -notmatch '^[a-z][a-z0-9-]+$') { throw "Invalid rollout resource $field." }
  }
}
$deadline = [DateTime]::UtcNow.AddMinutes($TimeoutMinutes)
$terminalFailures = @("FAILED", "CANCELLED", "CANCELED", "HALTED", "APPROVAL_REJECTED")
$previous = @{}
$advancedPhases = @{}

while ([DateTime]::UtcNow -lt $deadline) {
  # Read the whole group before advancing any member. A known failure blocks all
  # further advancement and later dependency groups; in-flight phases may finish.
  $observed = @()
  foreach ($entry in $Rollouts) {
    $raw = & $GcloudCommand deploy rollouts describe $entry.rollout `
      "--project=$ProjectId" "--region=$Region" `
      "--delivery-pipeline=$($entry.pipeline)" "--release=$($entry.release)" --format=json
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect rollout $($entry.pipeline)/$($entry.rollout)." }
    $state = ($raw -join "`n") | ConvertFrom-Json
    if (!$state.state) { throw "Rollout state is missing for $($entry.pipeline)." }
    $summary = "$($state.state):" + (($state.phases | ForEach-Object { "$($_.id):$($_.state)" }) -join ', ')
    if ($previous[$entry.pipeline] -ne $summary) {
      Write-Host "Rollout $($entry.pipeline)/$($entry.rollout) $summary"
      $previous[$entry.pipeline] = $summary
    }
    if ($state.state -in $terminalFailures) { throw "Cloud Deploy rollout $($entry.pipeline)/$($entry.rollout) ended in $($state.state)." }
    $failedPhase = $state.phases | Where-Object { $_.state -in ($terminalFailures + @('ABORTED')) } | Select-Object -First 1
    if ($failedPhase) { throw "Cloud Deploy phase $($entry.pipeline)/$($failedPhase.id) ended in $($failedPhase.state)." }
    $observed += [pscustomobject]@{ entry=$entry; data=$state }
  }
  if (@($observed | Where-Object { $_.data.state -ne 'SUCCEEDED' }).Count -eq 0) { return }
  foreach ($item in $observed) {
    $state = $item.data
    # PENDING_RELEASE exposes phases before render completion. Approval and
    # running phases also cannot be advanced by this loop.
    if ($AutoAdvanceCanary -and $state.state -eq "IN_PROGRESS" -and
        @($state.phases | Where-Object { $_.state -in @('IN_PROGRESS', 'RUNNING') }).Count -eq 0) {
      $next = $state.phases | Where-Object { $_.state -in @('PENDING', 'NOT_STARTED') } | Select-Object -First 1
      if ($next) {
        $entry = $item.entry
        $advanceKey = "$($entry.pipeline)/$($entry.release)/$($entry.rollout)/$($next.id)"
        # The API may briefly report the old pending phase after a successful
        # advance. Never repeat that non-idempotent request on a stale snapshot.
        if ($advancedPhases.ContainsKey($advanceKey)) { continue }
        Write-Host "Advancing $($entry.pipeline)/$($entry.rollout) to $($next.id)."
        & $GcloudCommand deploy rollouts advance $entry.rollout `
          "--project=$ProjectId" "--region=$Region" `
          "--delivery-pipeline=$($entry.pipeline)" "--release=$($entry.release)" `
          "--phase-id=$($next.id)" --quiet
        if ($LASTEXITCODE -ne 0) { throw "Could not advance rollout $($entry.pipeline)/$($entry.rollout)." }
        $advancedPhases[$advanceKey] = $true
      }
    }
  }
  Start-Sleep -Seconds $PollSeconds
}
throw "Timed out after $TimeoutMinutes minutes waiting for rollout group: $($keys -join ', ')."

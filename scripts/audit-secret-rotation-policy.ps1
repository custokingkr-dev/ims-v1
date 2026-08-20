param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [string]$PolicyPath = "deploy/gcp/secret-rotation-policy.json",
  [switch]$FailOnInventoryDrift
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$resolvedPolicyPath = Join-Path $repoRoot $PolicyPath
if (-not (Test-Path -LiteralPath $resolvedPolicyPath)) {
  throw "Secret rotation policy does not exist: $PolicyPath"
}

$policy = Get-Content -Raw -LiteralPath $resolvedPolicyPath | ConvertFrom-Json
$owners = @($policy.ownerDefinitions.PSObject.Properties.Name)
$policySecrets = New-Object System.Collections.Generic.List[object]

foreach ($group in @($policy.groups)) {
  if ($owners -notcontains [string]$group.owner) {
    throw "Secret group '$($group.id)' references undefined owner '$($group.owner)'."
  }
  if ([string]$group.rotationMode -eq "manual-coordinated" -and [int]$group.proposedRotationDays -le 0) {
    throw "Secret group '$($group.id)' needs a positive proposedRotationDays value."
  }
  foreach ($baseName in @($group.baseNames)) {
    $names = if (@($group.environments).Count -eq 0) {
      @([string]$baseName)
    } else {
      @($group.environments | ForEach-Object { "$baseName-$_" })
    }
    foreach ($name in $names) {
      $policySecrets.Add([pscustomobject]@{
        name = [string]$name
        group = [string]$group.id
        owner = [string]$group.owner
        rotationMode = [string]$group.rotationMode
        proposedRotationDays = $group.proposedRotationDays
      }) | Out-Null
    }
  }
}

$duplicates = @($policySecrets | Group-Object name | Where-Object Count -gt 1 | ForEach-Object Name)
if ($duplicates.Count -gt 0) {
  throw "Duplicate secrets in rotation policy: $($duplicates -join ', ')"
}

$liveJson = (& $GcloudCommand secrets list "--project=$ProjectId" `
  --format="json(name,labels,topics,rotation,nextRotationTime)") -join "`n"
if ($LASTEXITCODE -ne 0) { throw "Could not list Secret Manager metadata for $ProjectId." }
$parsedLiveSecrets = $liveJson | ConvertFrom-Json
$liveSecretList = New-Object System.Collections.Generic.List[object]
foreach ($liveSecret in $parsedLiveSecrets) {
  $liveSecretList.Add($liveSecret) | Out-Null
}
$liveSecrets = @($liveSecretList.ToArray())
$liveNames = @($liveSecrets | ForEach-Object { ([string]$_.name).Split("/")[-1] } | Sort-Object -Unique)
$policyNames = @($policySecrets.name | Sort-Object -Unique)
$unowned = @($liveNames | Where-Object { $policyNames -notcontains $_ })
$missingLive = @($policyNames | Where-Object { $liveNames -notcontains $_ })

$inventory = @($policySecrets | Sort-Object name | ForEach-Object {
  $entry = $_
  $live = $liveSecrets | Where-Object { ([string]$_.name).EndsWith("/secrets/$($entry.name)") } | Select-Object -First 1
  [ordered]@{
    name = $entry.name
    group = $entry.group
    owner = $entry.owner
    rotationMode = $entry.rotationMode
    proposedRotationDays = $entry.proposedRotationDays
    live = $null -ne $live
    hasRotationSchedule = $null -ne $live.rotation
    hasNotificationTopic = $null -ne $live.topics -and @($live.topics).Count -gt 0
  }
})

$result = [ordered]@{
  project = $ProjectId
  policy = $PolicyPath
  proposalDate = [string]$policy.proposalDate
  approvalState = [string]$policy.approvalState
  intervalApproval = [string]$policy.intervalApproval
  payloadsAccessed = $false
  policySecretCount = $policyNames.Count
  liveSecretCount = $liveNames.Count
  unownedLiveSecrets = $unowned
  policySecretsMissingLive = $missingLive
  scheduledSecretCount = @($inventory | Where-Object hasRotationSchedule).Count
  notificationTopicSecretCount = @($inventory | Where-Object hasNotificationTopic).Count
  notificationScheduleState = [string]$policy.notificationScheduleState
  inventory = $inventory
}
$result | ConvertTo-Json -Depth 8

if ($FailOnInventoryDrift -and ($unowned.Count -gt 0 -or $missingLive.Count -gt 0)) {
  exit 1
}

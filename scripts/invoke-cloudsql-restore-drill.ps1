param(
  [string]$ProjectId = "custoking",
  [string]$Region = "asia-south2",
  [string]$SourceInstance = "custoking-db-prod",
  [string]$Database = "app",
  [string]$EvidenceDirectory = "artifacts/recovery",
  [string]$ValidationBucket = "custoking-db-snapshots",
  [datetime]$PointInTimeUtc = [datetime]::UtcNow.AddMinutes(-5),
  [switch]$Apply,
  [switch]$KeepOnFailure
)

$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
$stamp = [datetime]::UtcNow.ToString("yyyyMMddHHmmss")
$target = "custoking-restore-drill-$stamp"
$object = "recovery-drills/$target.sql.gz"
$validationUri = "gs://$ValidationBucket/$object"
$evidencePath = Join-Path $EvidenceDirectory "$target.json"
$targetCreated = $false
$exportCreated = $false
$cloneBucketAccessGranted = $false
$cloneServiceAccount = $null
$drillSucceeded = $false
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$startedAt = [datetime]::UtcNow

function Invoke-Gcloud([string[]]$Arguments) {
  & $gcloud @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "gcloud failed: gcloud $($Arguments -join ' ')"
  }
}

$sourceJson = & $gcloud sql instances describe $SourceInstance "--project=$ProjectId" --format=json
if ($LASTEXITCODE -ne 0) { throw "Could not describe source instance $SourceInstance." }
$source = $sourceJson | ConvertFrom-Json
$backup = $source.settings.backupConfiguration
if (-not $backup.enabled -or -not $backup.pointInTimeRecoveryEnabled) {
  throw "Source instance must have automated backups and PITR enabled before a restore drill."
}
if ([string]::IsNullOrWhiteSpace($source.serviceAccountEmailAddress)) {
  throw "Source instance does not expose a Cloud SQL service identity for recovery exports."
}
& $gcloud storage buckets describe "gs://$ValidationBucket" "--project=$ProjectId" --format="value(name)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Recovery validation bucket $ValidationBucket is unavailable." }

$latestBackupJson = & $gcloud sql backups list "--instance=$SourceInstance" "--project=$ProjectId" `
  --filter="status=SUCCESSFUL" --sort-by="~endTime" --limit=1 --format=json
if ($LASTEXITCODE -ne 0) { throw "Could not inspect successful backups for $SourceInstance." }
$latestBackups = @($latestBackupJson | ConvertFrom-Json)
if ($latestBackups.Count -ne 1) { throw "No successful backup exists for $SourceInstance." }

Write-Host "Recovery drill source: $SourceInstance"
Write-Host "Latest successful backup: $($latestBackups[0].id) at $($latestBackups[0].endTime)"
Write-Host "PITR restore point: $($PointInTimeUtc.ToUniversalTime().ToString('o'))"
Write-Host "Temporary instance: $target"

if (-not $Apply) {
  Write-Host "Dry run only. Re-run with -Apply to execute the isolated clone/export/delete drill."
  exit 0
}

try {
  Invoke-Gcloud @(
    "sql", "instances", "clone", $SourceInstance, $target,
    "--project=$ProjectId",
    "--point-in-time=$($PointInTimeUtc.ToUniversalTime().ToString('o'))",
    "--quiet"
  )
  $targetCreated = $true

  Invoke-Gcloud @(
    "sql", "instances", "patch", $target,
    "--project=$ProjectId", "--no-deletion-protection",
    "--quiet"
  )

  $targetJson = & $gcloud sql instances describe $target "--project=$ProjectId" --format=json
  if ($LASTEXITCODE -ne 0) { throw "Could not describe restored instance $target." }
  $restored = $targetJson | ConvertFrom-Json
  if ($restored.state -ne "RUNNABLE") { throw "Restored instance is not RUNNABLE: $($restored.state)" }
  if ($restored.region -ne $Region) { throw "Restored instance is outside ${Region}: $($restored.region)" }
  $cloneServiceAccount = "$($restored.serviceAccountEmailAddress)".Trim()
  if ([string]::IsNullOrWhiteSpace($cloneServiceAccount)) {
    throw "Restored instance does not expose a Cloud SQL service identity."
  }

  Invoke-Gcloud @(
    "storage", "buckets", "add-iam-policy-binding", "gs://$ValidationBucket",
    "--project=$ProjectId",
    "--member=serviceAccount:$cloneServiceAccount",
    "--role=roles/storage.objectAdmin",
    "--quiet"
  )
  $cloneBucketAccessGranted = $true

  $databaseNames = @(& $gcloud sql databases list "--instance=$target" "--project=$ProjectId" --format="value(name)")
  if ($LASTEXITCODE -ne 0) { throw "Could not list databases on restored instance." }
  if ($databaseNames -notcontains $Database) { throw "Restored database '$Database' was not found." }

  for ($attempt = 1; $attempt -le 3 -and -not $exportCreated; $attempt++) {
    & $gcloud sql export sql $target $validationUri "--database=$Database" "--project=$ProjectId" --quiet
    if ($LASTEXITCODE -eq 0) {
      $exportCreated = $true
    } elseif ($attempt -lt 3) {
      Write-Host "Export attempt $attempt failed; waiting for bucket IAM propagation."
      Start-Sleep -Seconds 20
    }
  }
  if (-not $exportCreated) { throw "Restore validation export failed after three attempts." }
  $statJson = & $gcloud storage objects describe $validationUri "--project=$ProjectId" --format=json
  if ($LASTEXITCODE -ne 0) { throw "Could not inspect restore validation export." }
  $stat = $statJson | ConvertFrom-Json
  if ([int64]$stat.size -le 0) { throw "Restore validation export is empty." }

  $drillSucceeded = $true
  New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
  [ordered]@{
    project = $ProjectId
    region = $Region
    sourceInstance = $SourceInstance
    targetInstance = $target
    database = $Database
    pointInTimeUtc = $PointInTimeUtc.ToUniversalTime().ToString("o")
    latestSuccessfulBackupId = $latestBackups[0].id
    latestSuccessfulBackupEndTime = $latestBackups[0].endTime
    restoredState = $restored.state
    validationExportBytes = [int64]$stat.size
    startedAtUtc = $startedAt.ToString("o")
    validatedAtUtc = [datetime]::UtcNow.ToString("o")
    status = "PASSED"
  } | ConvertTo-Json -Depth 5 | Set-Content -Path $evidencePath
  Write-Host "Restore validation passed; evidence: $evidencePath"
}
finally {
  if ($exportCreated -and ($drillSucceeded -or -not $KeepOnFailure)) {
    & $gcloud storage rm $validationUri --quiet
    if ($LASTEXITCODE -ne 0) { $cleanupErrors.Add("Could not delete $validationUri") }
  }
  if ($cloneBucketAccessGranted) {
    & $gcloud storage buckets remove-iam-policy-binding "gs://$ValidationBucket" `
      "--project=$ProjectId" "--member=serviceAccount:$cloneServiceAccount" `
      --role=roles/storage.objectAdmin --quiet
    if ($LASTEXITCODE -ne 0) { $cleanupErrors.Add("Could not revoke recovery bucket access for $cloneServiceAccount") }
  }
  if ($targetCreated -and ($drillSucceeded -or -not $KeepOnFailure)) {
    & $gcloud sql instances patch $target "--project=$ProjectId" --no-deletion-protection --quiet
    if ($LASTEXITCODE -ne 0) { $cleanupErrors.Add("Could not disable deletion protection on $target") }
    & $gcloud sql instances delete $target "--project=$ProjectId" --quiet
    if ($LASTEXITCODE -ne 0) { $cleanupErrors.Add("Could not delete temporary instance $target") }
  }
  if ($cleanupErrors.Count -gt 0) {
    throw "Recovery drill cleanup failed: $($cleanupErrors -join '; ')"
  }
}

Write-Host "Recovery drill completed and all temporary data was removed."

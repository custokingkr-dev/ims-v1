param(
  [string]$ProjectId = "custoking",
  [string]$Region = "asia-south2",
  [ValidateSet("dev", "prod")]
  [string]$Environment = "dev",
  [string]$SourceInstance = "custoking-db-dev",
  [string]$Database = "custoking_dev",
  [string]$EvidenceDirectory = "artifacts/recovery",
  [string]$ValidationBucket = "custoking-db-snapshots",
  [datetime]$PointInTimeUtc = [datetime]::UtcNow.AddMinutes(-5),
  [switch]$Apply,
  [switch]$AllowDevRecoveryCost,
  [switch]$AllowProductionRecoveryDrill,
  [switch]$KeepOnFailure
)

$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
$stamp = [datetime]::UtcNow.ToString("yyyyMMddHHmmss")
$isProduction = $Environment -eq "prod"
$target = "custoking-$Environment-restore-drill-$stamp"
$object = "recovery-drills/$target.sql.gz"
$validationUri = "gs://$ValidationBucket/$object"
$evidencePath = Join-Path $EvidenceDirectory "$target.json"
$targetCreated = $false
$exportRequested = $false
$exportCreated = $false
$cloneBucketAccessGranted = $false
$cloneServiceAccount = $null
$drillSucceeded = $false
$evidence = $null
$validationObjectRemoved = $false
$temporaryBucketIamRemoved = $false
$temporaryInstanceRemoved = $false
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$startedAt = [datetime]::UtcNow
$restoreReadyAt = $null
$exportOperationName = $null
$validationMode = if ($isProduction) { "schema-only" } else { "full-synthetic-data" }

if ($isProduction) {
  if ($SourceInstance -ne "custoking-db-prod") {
    throw "Production recovery is restricted to the exact source instance custoking-db-prod."
  }
  if (-not $AllowProductionRecoveryDrill) {
    throw "Production recovery requires -Environment prod, the exact prod source, and -AllowProductionRecoveryDrill."
  }
  if ($KeepOnFailure) {
    throw "Production recovery cannot retain a failed clone or validation object."
  }
} else {
  if ($SourceInstance -ne "custoking-db-dev") {
    throw "Development recovery is restricted to the exact source instance custoking-db-dev."
  }
  if ($AllowProductionRecoveryDrill) {
    throw "-AllowProductionRecoveryDrill is invalid for a development drill."
  }
  if ($Apply -and -not $AllowDevRecoveryCost) {
    throw "An isolated PITR clone incurs temporary dev compute/storage cost. Pass -AllowDevRecoveryCost."
  }
}

function Invoke-Gcloud([string[]]$Arguments) {
  & $gcloud @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "gcloud failed: gcloud $($Arguments -join ' ')"
  }
}

function Test-GcloudResourceAbsent {
  param(
    [string[]]$Arguments,
    [string]$ResourceLabel
  )

  $priorErrorActionPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $output = @(& $gcloud @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $priorErrorActionPreference
    $global:LASTEXITCODE = 0
  }

  if ($exitCode -eq 0) {
    return $false
  }
  $message = ($output | ForEach-Object { [string]$_ }) -join "`n"
  if ($message -match '(?i)(404|not found|does not exist|no URLs matched)') {
    return $true
  }
  throw "Could not confirm removal of ${ResourceLabel}: $message"
}

function Write-RecoveryEvidence {
  param([System.Collections.IDictionary]$Document)

  New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
  $Document | ConvertTo-Json -Depth 8 | Set-Content -Path $evidencePath
}

function Get-GcloudAccessToken {
  $token = ((& $gcloud auth print-access-token) -join "").Trim()
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($token)) {
    throw "Could not obtain a short-lived access token for the Cloud SQL Admin API."
  }
  return $token
}

function Invoke-CloudSqlSchemaOnlyExport {
  param(
    [string]$Instance,
    [string]$DestinationUri,
    [string]$DatabaseName
  )

  # Production never reaches gcloud's full-data export command. The request body is constructed
  # here so sqlExportOptions.schemaOnly=true is an explicit, reviewable invariant.
  $request = [ordered]@{
    exportContext = [ordered]@{
      fileType = "SQL"
      uri = $DestinationUri
      databases = @($DatabaseName)
      offload = $false
      sqlExportOptions = [ordered]@{
        schemaOnly = $true
      }
    }
  }
  if ($request.exportContext.sqlExportOptions.schemaOnly -ne $true) {
    throw "Production export request is not schema-only."
  }

  $token = Get-GcloudAccessToken
  $headers = @{ Authorization = "Bearer $token" }
  $projectSegment = [uri]::EscapeDataString($ProjectId)
  $instanceSegment = [uri]::EscapeDataString($Instance)
  $exportUrl = "https://sqladmin.googleapis.com/sql/v1beta4/projects/$projectSegment/instances/$instanceSegment/export"
  $operation = Invoke-RestMethod -Method Post -Uri $exportUrl -Headers $headers `
    -ContentType "application/json" -Body ($request | ConvertTo-Json -Depth 8 -Compress)
  $operationName = ([string]$operation.name).Split("/")[-1]
  if ([string]::IsNullOrWhiteSpace($operationName)) {
    throw "Cloud SQL schema-only export did not return an operation name."
  }

  $operationSegment = [uri]::EscapeDataString($operationName)
  $operationUrl = "https://sqladmin.googleapis.com/sql/v1beta4/projects/$projectSegment/operations/$operationSegment"
  $deadline = [datetime]::UtcNow.AddMinutes(30)
  while ([datetime]::UtcNow -lt $deadline) {
    $current = Invoke-RestMethod -Method Get -Uri $operationUrl -Headers $headers
    if ([string]$current.status -eq "DONE") {
      $errors = @($current.error.errors)
      if ($errors.Count -gt 0) {
        $safeErrors = @($errors | ForEach-Object { "$($_.code): $($_.message)" }) -join "; "
        throw "Cloud SQL schema-only export failed: $safeErrors"
      }
      return $operationName
    }
    Start-Sleep -Seconds 10
  }
  throw "Cloud SQL schema-only export operation $operationName exceeded the 30-minute timeout."
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
Write-Host "Validation export mode: $validationMode"

if (-not $Apply) {
  Write-Host "Dry run only. Re-run with the environment-specific authorization switch and -Apply."
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
  $restoreReadyAt = [datetime]::UtcNow
  if ($restored.region -ne $Region) { throw "Restored instance is outside ${Region}: $($restored.region)" }
  $cloneServiceAccount = "$($restored.serviceAccountEmailAddress)".Trim()
  if ([string]::IsNullOrWhiteSpace($cloneServiceAccount)) {
    throw "Restored instance does not expose a Cloud SQL service identity."
  }

  Invoke-Gcloud @(
    "storage", "buckets", "add-iam-policy-binding", "gs://$ValidationBucket",
    "--project=$ProjectId",
    "--member=serviceAccount:$cloneServiceAccount",
    "--role=roles/storage.objectCreator",
    "--quiet"
  )
  $cloneBucketAccessGranted = $true

  $databaseNames = @(& $gcloud sql databases list "--instance=$target" "--project=$ProjectId" --format="value(name)")
  if ($LASTEXITCODE -ne 0) { throw "Could not list databases on restored instance." }
  if ($databaseNames -notcontains $Database) { throw "Restored database '$Database' was not found." }

  for ($attempt = 1; $attempt -le 3 -and -not $exportCreated; $attempt++) {
    try {
      $exportRequested = $true
      if ($isProduction) {
        $exportOperationName = Invoke-CloudSqlSchemaOnlyExport `
          -Instance $target -DestinationUri $validationUri -DatabaseName $Database
      } else {
        & $gcloud sql export sql $target $validationUri "--database=$Database" "--project=$ProjectId" --quiet
        if ($LASTEXITCODE -ne 0) {
          throw "Development validation export command failed."
        }
      }
      $exportCreated = $true
    } catch {
      if ($attempt -ge 3) {
        throw
      }
      & $gcloud storage objects describe $validationUri "--project=$ProjectId" --format="value(name)" 2>$null | Out-Null
      $partialObjectExists = $LASTEXITCODE -eq 0
      $global:LASTEXITCODE = 0
      if ($partialObjectExists) {
        Invoke-Gcloud @("storage", "rm", $validationUri, "--project=$ProjectId", "--quiet")
      }
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
  $evidence = [ordered]@{
    project = $ProjectId
    region = $Region
    environment = $Environment
    sourceInstance = $SourceInstance
    targetInstance = $target
    database = $Database
    pointInTimeUtc = $PointInTimeUtc.ToUniversalTime().ToString("o")
    latestSuccessfulBackupId = $latestBackups[0].id
    latestSuccessfulBackupEndTime = $latestBackups[0].endTime
    restoredState = $restored.state
    validationExportBytes = [int64]$stat.size
    validationMode = $validationMode
    schemaOnly = [bool]$isProduction
    dataRowsValidated = $false
    exportOperationName = $exportOperationName
    startedAtUtc = $startedAt.ToString("o")
    validatedAtUtc = [datetime]::UtcNow.ToString("o")
    recoveryPointAgeSeconds = [math]::Round(($startedAt - $PointInTimeUtc.ToUniversalTime()).TotalSeconds, 2)
    restoreInstanceReadySeconds = [math]::Round(($restoreReadyAt - $startedAt).TotalSeconds, 2)
    validationRtoSeconds = [math]::Round(([datetime]::UtcNow - $startedAt).TotalSeconds, 2)
  }
}
finally {
  if ($exportRequested -and ($drillSucceeded -or -not $KeepOnFailure)) {
    & $gcloud storage objects describe $validationUri "--project=$ProjectId" --format="value(name)" 2>$null | Out-Null
    $objectExists = $LASTEXITCODE -eq 0
    $global:LASTEXITCODE = 0
    if ($objectExists) {
      & $gcloud storage rm $validationUri "--project=$ProjectId" --quiet
      $global:LASTEXITCODE = 0
    }
    try {
      $validationObjectRemoved = Test-GcloudResourceAbsent `
        -Arguments @("storage", "objects", "describe", $validationUri, "--project=$ProjectId", "--format=value(name)") `
        -ResourceLabel $validationUri
      if (-not $validationObjectRemoved) {
        $cleanupErrors.Add("Validation object still exists: $validationUri")
      }
    } catch {
      $cleanupErrors.Add($_.Exception.Message)
    }
  }
  if ($cloneBucketAccessGranted) {
    & $gcloud storage buckets remove-iam-policy-binding "gs://$ValidationBucket" `
      "--project=$ProjectId" "--member=serviceAccount:$cloneServiceAccount" `
      --role=roles/storage.objectCreator --quiet
    $global:LASTEXITCODE = 0
    $policyJson = & $gcloud storage buckets get-iam-policy "gs://$ValidationBucket" `
      "--project=$ProjectId" --format=json
    if ($LASTEXITCODE -ne 0) {
      $cleanupErrors.Add("Could not verify recovery bucket IAM cleanup for $cloneServiceAccount")
      $global:LASTEXITCODE = 0
    } else {
      $policy = $policyJson | ConvertFrom-Json
      $temporaryBinding = @($policy.bindings | Where-Object {
          $_.role -eq "roles/storage.objectCreator" -and
          @($_.members) -contains "serviceAccount:$cloneServiceAccount"
        })
      $temporaryBucketIamRemoved = $temporaryBinding.Count -eq 0
      if (-not $temporaryBucketIamRemoved) {
        $cleanupErrors.Add("Temporary recovery bucket access still exists for $cloneServiceAccount")
      }
    }
  }
  if ($targetCreated -and ($drillSucceeded -or -not $KeepOnFailure)) {
    & $gcloud sql instances patch $target "--project=$ProjectId" --no-deletion-protection --quiet
    $global:LASTEXITCODE = 0
    & $gcloud sql instances delete $target "--project=$ProjectId" --quiet
    $global:LASTEXITCODE = 0
    try {
      $temporaryInstanceRemoved = Test-GcloudResourceAbsent `
        -Arguments @("sql", "instances", "describe", $target, "--project=$ProjectId", "--format=value(name)") `
        -ResourceLabel "temporary instance $target"
      if (-not $temporaryInstanceRemoved) {
        $cleanupErrors.Add("Temporary instance still exists: $target")
      }
    } catch {
      $cleanupErrors.Add($_.Exception.Message)
    }
  }
  $allCleanupConfirmed = (
    $validationObjectRemoved -and
    $temporaryBucketIamRemoved -and
    $temporaryInstanceRemoved
  )
  if ($drillSucceeded -and -not $allCleanupConfirmed) {
    $cleanupErrors.Add("Cleanup confirmation is incomplete for a validated recovery drill")
  }
  if ($cleanupErrors.Count -gt 0) {
    if ($null -eq $evidence) {
      $evidence = [ordered]@{
        project = $ProjectId
        region = $Region
        environment = $Environment
        sourceInstance = $SourceInstance
        targetInstance = $target
        database = $Database
        pointInTimeUtc = $PointInTimeUtc.ToUniversalTime().ToString("o")
        validationMode = $validationMode
        schemaOnly = [bool]$isProduction
        dataRowsValidated = $false
        startedAtUtc = $startedAt.ToString("o")
      }
    }
    $evidence["status"] = "cleanup-failed"
    $evidence["cleanupConfirmed"] = $false
    $evidence["validationObjectRemoved"] = [bool]$validationObjectRemoved
    $evidence["temporaryBucketIamRemoved"] = [bool]$temporaryBucketIamRemoved
    $evidence["temporaryInstanceRemoved"] = [bool]$temporaryInstanceRemoved
    $evidence["cleanupErrors"] = @($cleanupErrors)
    $evidence["finishedAtUtc"] = [datetime]::UtcNow.ToString("o")
    Write-RecoveryEvidence -Document $evidence
    throw "Recovery drill cleanup failed: $($cleanupErrors -join '; ')"
  }
}

if (-not $drillSucceeded -or $null -eq $evidence) {
  throw "Recovery validation did not complete successfully."
}
$evidence["status"] = "PASSED"
$evidence["cleanupConfirmed"] = $true
$evidence["validationObjectRemoved"] = [bool]$validationObjectRemoved
$evidence["temporaryBucketIamRemoved"] = [bool]$temporaryBucketIamRemoved
$evidence["temporaryInstanceRemoved"] = [bool]$temporaryInstanceRemoved
$evidence["cleanupErrors"] = @()
$evidence["finishedAtUtc"] = [datetime]::UtcNow.ToString("o")
Write-RecoveryEvidence -Document $evidence
Write-Host "Recovery drill passed after confirmed cleanup; evidence: $evidencePath"

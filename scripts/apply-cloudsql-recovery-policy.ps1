param(
  [string]$ProjectId = "custoking",
  [string]$Instance = "custoking-db-prod",
  [string]$BackupLocation = "asia-south2",
  [string]$BackupStartTimeUtc = "20:30",
  [string]$ValidationBucket = "custoking-db-snapshots",
  [string]$RecoveryOperatorServiceAccount = "custoking-recovery-operator@custoking.iam.gserviceaccount.com",
  [string]$RecoveryBucketIamRoleId = "custokingRecoveryBucketIamOperator",
  [string]$RecoveryBucketIamRoleFile = "deploy/gcp/recovery-bucket-iam-operator-role.yaml",
  [ValidateRange(7, 365)]
  [int]$RetainedBackups = 14,
  [ValidateRange(1, 35)]
  [int]$TransactionLogRetentionDays = 7,
  [switch]$Apply
)

$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

function Invoke-Gcloud([string[]]$Arguments) {
  & $gcloud @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "gcloud failed: gcloud $($Arguments -join ' ')"
  }
}

$beforeJson = & $gcloud sql instances describe $Instance "--project=$ProjectId" --format=json
if ($LASTEXITCODE -ne 0) { throw "Could not describe Cloud SQL instance $Instance." }
$before = $beforeJson | ConvertFrom-Json

Write-Host "Cloud SQL recovery policy for $Instance"
Write-Host "  backup enabled: $($before.settings.backupConfiguration.enabled)"
Write-Host "  PITR enabled: $($before.settings.backupConfiguration.pointInTimeRecoveryEnabled)"
Write-Host "  retained backups: $($before.settings.backupConfiguration.backupRetentionSettings.retainedBackups)"
Write-Host "  transaction log days: $($before.settings.backupConfiguration.transactionLogRetentionDays)"
Write-Host "  deletion protection: $($before.settings.deletionProtectionEnabled)"

if (-not $Apply) {
  Write-Host "Dry run only. Re-run with -Apply to enforce the production recovery policy."
  exit 0
}

Invoke-Gcloud @(
  "sql", "instances", "patch", $Instance,
  "--project=$ProjectId",
  "--backup-start-time=$BackupStartTimeUtc",
  "--backup-location=$BackupLocation",
  "--retained-backups-count=$RetainedBackups",
  "--enable-point-in-time-recovery",
  "--retained-transaction-log-days=$TransactionLogRetentionDays",
  "--deletion-protection",
  "--quiet"
)

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$roleFile = if ([System.IO.Path]::IsPathRooted($RecoveryBucketIamRoleFile)) {
  $RecoveryBucketIamRoleFile
} else {
  Join-Path $repoRoot $RecoveryBucketIamRoleFile
}
if (-not (Test-Path -LiteralPath $roleFile)) {
  throw "Recovery bucket IAM role definition not found: $roleFile"
}

& $gcloud iam roles describe $RecoveryBucketIamRoleId "--project=$ProjectId" --format="value(name)" 2>$null | Out-Null
$roleExists = $LASTEXITCODE -eq 0
$global:LASTEXITCODE = 0
if ($roleExists) {
  Invoke-Gcloud @("iam", "roles", "update", $RecoveryBucketIamRoleId, "--project=$ProjectId", "--file=$roleFile", "--quiet")
} else {
  Invoke-Gcloud @("iam", "roles", "create", $RecoveryBucketIamRoleId, "--project=$ProjectId", "--file=$roleFile", "--quiet")
}

Invoke-Gcloud @(
  "storage", "buckets", "add-iam-policy-binding", "gs://$ValidationBucket",
  "--project=$ProjectId",
  "--member=serviceAccount:$RecoveryOperatorServiceAccount",
  "--role=projects/$ProjectId/roles/$RecoveryBucketIamRoleId",
  "--quiet"
)

$afterJson = & $gcloud sql instances describe $Instance "--project=$ProjectId" --format=json
if ($LASTEXITCODE -ne 0) { throw "Could not verify Cloud SQL instance $Instance after patch." }
$after = $afterJson | ConvertFrom-Json
$backup = $after.settings.backupConfiguration

if (-not $backup.enabled -or -not $backup.pointInTimeRecoveryEnabled) {
  throw "Automated backup or PITR is still disabled after applying the policy."
}
if ([int]$backup.backupRetentionSettings.retainedBackups -lt $RetainedBackups) {
  throw "Cloud SQL retained backup count is below $RetainedBackups."
}
if ([int]$backup.transactionLogRetentionDays -lt $TransactionLogRetentionDays) {
  throw "Cloud SQL transaction-log retention is below $TransactionLogRetentionDays days."
}
if (-not $after.settings.deletionProtectionEnabled) {
  throw "Cloud SQL deletion protection is not enabled."
}

Write-Host "Production Cloud SQL recovery policy and validation-bucket access are enforced and verified."

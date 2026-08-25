param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID." }),
  [string]$SourceInstance = "custoking-db-prod",
  [string]$ValidationBucket = "",
  [string]$RecoveryOperatorServiceAccount = "",
  [string]$RecoveryBucketIamRoleId = "custokingRecoveryBucketIamOperator"
)

$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if ([string]::IsNullOrWhiteSpace($ValidationBucket)) {
  $ValidationBucket = "$ProjectId-db-snapshots"
}
if ([string]::IsNullOrWhiteSpace($RecoveryOperatorServiceAccount)) {
  $RecoveryOperatorServiceAccount = "custoking-recovery-operator@$ProjectId.iam.gserviceaccount.com"
}

function Invoke-Gcloud([string[]]$Arguments) {
  $output = @(& $gcloud @Arguments)
  if ($LASTEXITCODE -ne 0) {
    throw "gcloud failed while auditing recovery prerequisites: gcloud $($Arguments -join ' ')"
  }
  return $output
}

function Get-GcloudAccessToken {
  $token = ((Invoke-Gcloud @("auth", "print-access-token")) -join "").Trim()
  if ([string]::IsNullOrWhiteSpace($token)) {
    throw "Could not obtain a short-lived access token for the recovery prerequisite audit."
  }
  return $token
}

function Assert-Permissions {
  param(
    [string[]]$Granted,
    [string[]]$Required,
    [string]$Scope
  )

  $missing = @($Required | Where-Object { $Granted -notcontains $_ })
  if ($missing.Count -gt 0) {
    throw "Recovery operator is missing required $Scope permissions: $($missing -join ', '). No clone was created."
  }
}

$activeAccounts = @(
  @(Invoke-Gcloud @("auth", "list", "--filter=status:ACTIVE", "--format=value(account)")) |
    ForEach-Object { ([string]$_).Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
if ($activeAccounts.Count -ne 1 -or $activeAccounts[0] -ne $RecoveryOperatorServiceAccount) {
  $activeLabel = if ($activeAccounts.Count -eq 0) { "none" } else { $activeAccounts -join ", " }
  throw "Recovery must run as $RecoveryOperatorServiceAccount; active account is $activeLabel. No clone was created."
}

Invoke-Gcloud @(
  "sql", "instances", "describe", $SourceInstance,
  "--project=$ProjectId", "--format=value(name)"
) | Out-Null

Invoke-Gcloud @(
  "storage", "buckets", "describe", "gs://$ValidationBucket",
  "--project=$ProjectId", "--format=value(name)"
) | Out-Null

$bucketPolicyJson = (Invoke-Gcloud @(
    "storage", "buckets", "get-iam-policy", "gs://$ValidationBucket",
    "--project=$ProjectId", "--format=json"
  )) -join "`n"
$bucketPolicy = $bucketPolicyJson | ConvertFrom-Json
$expectedRole = "projects/$ProjectId/roles/$RecoveryBucketIamRoleId"
$expectedMember = "serviceAccount:$RecoveryOperatorServiceAccount"
$expectedBucketResource = "projects/_/buckets/$ValidationBucket"
$expectedObjectPrefix = "projects/_/buckets/$ValidationBucket/objects/recovery-drills/"
$operatorBindings = @($bucketPolicy.bindings | Where-Object {
    $_.role -eq $expectedRole -and @($_.members) -contains $expectedMember
  })
$conditionedBinding = @($operatorBindings | Where-Object {
    $expression = [string]$_.condition.expression
    $expression.Contains($expectedBucketResource) -and $expression.Contains($expectedObjectPrefix)
  })
if ($conditionedBinding.Count -eq 0) {
  throw "Recovery bucket $ValidationBucket is missing the conditioned $expectedRole binding for $expectedMember. No clone was created."
}

$token = Get-GcloudAccessToken
$projectPermissions = @(
  "cloudsql.databases.list",
  "cloudsql.instances.clone",
  "cloudsql.instances.create",
  "cloudsql.instances.delete",
  "cloudsql.instances.export",
  "cloudsql.instances.get",
  "cloudsql.instances.update",
  "cloudsql.operations.get",
  "serviceusage.services.use"
)
$projectSegment = [uri]::EscapeDataString($ProjectId)
$projectPermissionResponse = Invoke-RestMethod -Method Post `
  -Uri "https://cloudresourcemanager.googleapis.com/v1/projects/${projectSegment}:testIamPermissions" `
  -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" `
  -Body (@{ permissions = $projectPermissions } | ConvertTo-Json -Compress)
Assert-Permissions -Granted @($projectPermissionResponse.permissions) -Required $projectPermissions -Scope "project"

$bucketPermissions = @(
  "storage.buckets.get",
  "storage.buckets.getIamPolicy",
  "storage.buckets.setIamPolicy"
)
$bucketSegment = [uri]::EscapeDataString($ValidationBucket)
$permissionQuery = ($bucketPermissions | ForEach-Object { "permissions=$([uri]::EscapeDataString($_))" }) -join "&"
$bucketPermissionResponse = Invoke-RestMethod -Method Get `
  -Uri "https://storage.googleapis.com/storage/v1/b/$bucketSegment/iam/testPermissions?$permissionQuery" `
  -Headers @{ Authorization = "Bearer $token" }
Assert-Permissions -Granted @($bucketPermissionResponse.permissions) -Required $bucketPermissions -Scope "recovery-bucket"

Write-Host "Recovery prerequisites are ready for $SourceInstance using gs://$ValidationBucket. No resources were changed."

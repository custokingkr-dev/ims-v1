param(
  [Parameter(Mandatory = $true)]
  [ValidateSet("custoking-dev")]
  [string]$ProjectId,
  [ValidateSet("asia-south2")]
  [string]$Region = "asia-south2",
  [switch]$Apply,
  [switch]$SkipSchedulers
)

# This script never deploys a revision, enables broadcast processing, accesses secret contents,
# changes consent, or sends a message. Only -Apply may provision the listed dev prerequisites.
$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
$bucket = "$ProjectId-quotation-documents"
$secret = "broadcast-policy-token-dev"
$roleId = "quotationDocumentRuntime"
$roleName = "projects/$ProjectId/roles/$roleId"
$operationsIdentity = "ims-operations-dev@$ProjectId.iam.gserviceaccount.com"
$platformIdentity = "ims-platform-dev@$ProjectId.iam.gserviceaccount.com"
$schoolIdentity = "ims-school-core-dev@$ProjectId.iam.gserviceaccount.com"
$expectedPermissions = @("storage.buckets.get", "storage.objects.create", "storage.objects.get", "storage.objects.delete")
$roleFile = Join-Path $PSScriptRoot "../deploy/gcp/quotation-document-runtime-role.yaml"

function Invoke-Gcloud {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  $output = & $gcloud @Arguments "--project=$ProjectId"
  if ($LASTEXITCODE -ne 0) { throw "gcloud failed: $($Arguments -join ' ')" }
  return $output
}
function Read-GcloudJson {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  return ((Invoke-Gcloud @Arguments --format=json) -join "`n") | ConvertFrom-Json
}
function Read-Service([string]$Name, [string]$ExpectedIdentity) {
  $service = Read-GcloudJson run services describe $Name "--region=$Region"
  if ($service.spec.template.spec.serviceAccountName -ne $ExpectedIdentity) {
    throw "$Name is not running as its expected dedicated dev identity. No prerequisite changes were started."
  }
  return $service
}
function Read-Environment($Service, [string]$Name) {
  return [string](@($Service.spec.template.spec.containers[0].env | Where-Object { $_.name -eq $Name } | Select-Object -First 1).value)
}
function Assert-ProviderDryRun($Service) {
  if ((Read-Environment $Service "NOTIFICATION_DELIVERY_PROVIDER") -ne "logging" -or
      (Read-Environment $Service "MSG91_DRY_RUN") -ne "true") {
    throw "Dev platform must explicitly use logging and MSG91_DRY_RUN=true before provisioning a notification drain."
  }
  $mode = Read-Environment $Service "BROADCAST_DISPATCH_MODE"
  if ($mode -and $mode -notin @("OFF", "DRY_RUN")) { throw "Unexpected broadcast mode '$mode'; refusing to provision."
  }
}
function Add-QuotationBucketBinding {
  # A just-created custom role can exist in IAM before Storage's policy validator sees it.
  # Retry only that exact error, after proving the role and bucket still belong to this project.
  $propagationError = "HTTPError 400: Role \($([regex]::Escape($roleName))\) does not exist in the resource's hierarchy\."
  for ($attempt = 1; $attempt -le 6; $attempt++) {
    $previousPreference = $ErrorActionPreference
    try {
      $ErrorActionPreference = "Continue"
      $lines = @(& $gcloud storage buckets add-iam-policy-binding "gs://$bucket" "--member=serviceAccount:$operationsIdentity" "--role=$roleName" "--project=$ProjectId" --quiet 2>&1)
      $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($code -eq 0) { return }
    $failure = ($lines | ForEach-Object { [string]$_ }) -join "`n"
    if ($failure -notmatch $propagationError -or $attempt -eq 6) {
      throw "Quotation bucket IAM binding failed; no broader role was substituted. $failure"
    }
    $verifiedRole = Read-GcloudJson iam roles describe $roleId
    $verifiedBucket = @(Read-GcloudJson storage buckets list | Where-Object { $_.name -eq $bucket })
    if ($verifiedRole.name -ne $roleName -or $verifiedRole.deleted -or
        @(Compare-Object ($expectedPermissions | Sort-Object) (@($verifiedRole.includedPermissions) | Sort-Object)).Count -gt 0 -or
        $verifiedBucket.Count -ne 1 -or $verifiedBucket[0].uniform_bucket_level_access -ne $true -or
        $verifiedBucket[0].public_access_prevention -ne "enforced" -or $verifiedBucket[0].location -ne "ASIA-SOUTH2") {
      throw "Cannot attribute the role hierarchy error to propagation: role or bucket ownership/privacy did not match. No retry was made."
    }
    Write-Host "Verified dev role/bucket; waiting 10 seconds for custom-role propagation (attempt $attempt of 6)."
    Start-Sleep -Seconds 10
  }
}

$operations = Read-Service "custoking-operations-service-dev" $operationsIdentity
$platform = Read-Service "custoking-platform-service-dev" $platformIdentity
$school = Read-Service "custoking-school-core-service-dev" $schoolIdentity
Assert-ProviderDryRun $platform
$buckets = @(Read-GcloudJson storage buckets list)
$existingBucket = @($buckets | Where-Object { $_.name -eq $bucket } | Select-Object -First 1)
if ($existingBucket.Count -gt 0 -and
    ($existingBucket[0].uniform_bucket_level_access -ne $true -or $existingBucket[0].public_access_prevention -ne "enforced" -or $existingBucket[0].location -ne "ASIA-SOUTH2")) {
  throw "Existing quotation bucket has unexpected location or privacy. Refusing to change an existing bucket policy implicitly."
}
$roles = @(Read-GcloudJson iam roles list)
$existingRole = @($roles | Where-Object { $_.name -eq $roleName } | Select-Object -First 1)
if ($existingRole.Count -gt 0) {
  $role = Read-GcloudJson iam roles describe $roleId
  if ($role.deleted -or @(Compare-Object ($expectedPermissions | Sort-Object) (@($role.includedPermissions) | Sort-Object)).Count -gt 0) {
    throw "Existing $roleName does not match the reviewed four permissions. Refusing to update a shared custom role implicitly."
  }
}
$secrets = @(Read-GcloudJson secrets list)
$existingSecret = @($secrets | Where-Object { $_.name -match "/secrets/$secret$" } | Select-Object -First 1)
$versions = if ($existingSecret.Count -gt 0) { @(Read-GcloudJson secrets versions list $secret) } else { @() }
if ($versions.Count -gt 0 -and (@($versions | Sort-Object { [long](($_.name -split '/')[-1]) } -Descending | Select-Object -First 1)[0].state -ne "ENABLED")) {
  throw "The latest policy secret version is not enabled. Review its state; this script does not rotate or re-enable it."
}
$jobs = @(Read-GcloudJson scheduler jobs list --location=asia-south1)
$serviceNames = @("operations-service", "platform-service")
$plan = [ordered]@{
  project = $ProjectId; environment = "dev"; observedAt = (Get-Date).ToUniversalTime().ToString("o"); applyRequested = [bool]$Apply
  quotationStorage = [ordered]@{
    bucket = $bucket; createRequired = $existingBucket.Count -eq 0; location = $Region
    uniformBucketLevelAccess = $true; publicAccessPrevention = "enforced"
    runtimeIdentity = $operationsIdentity; bucketScopedRole = $roleName
    roleCreateRequired = $existingRole.Count -eq 0; permissions = $expectedPermissions
  }
  broadcastPolicy = [ordered]@{
    secret = $secret; createRequired = $existingSecret.Count -eq 0; initialVersionRequired = $versions.Count -eq 0
    secretAccessors = @($platformIdentity, $schoolIdentity)
    privateInvoker = [ordered]@{ caller = $platformIdentity; target = "custoking-school-core-service-dev" }
  }
  backgroundJobs = @($serviceNames | ForEach-Object {
    $name = "ims-$_-async-relay-dev"
    [ordered]@{ name = $name; location = "asia-south1"; exists = @($jobs | Where-Object { $_.name -match "/jobs/$name$" }).Count -gt 0; selectedForProvisioning = -not [bool]$SkipSchedulers }
  })
  deployment = [ordered]@{
    operationsBucketEnvironment = "FIREFIGHTING_QUOTATION_DOCUMENT_BUCKET=$bucket"
    sharedSecretEnvironment = "BROADCAST_POLICY_TOKEN=$secret`:latest"
    baselineMode = "OFF"; baselineWorkerReady = $false
    provider = "logging"; msg91DryRun = $true
    deployPerformedByThisScript = $false; broadcastModeChangedByThisScript = $false
  }
}
$plan | ConvertTo-Json -Depth 8
if (-not $Apply) {
  Write-Host "Read-only plan. No bucket, IAM binding, secret, Scheduler job, revision, or message was changed."
  return
}

# All IAM changes below use additive bindings, never whole-policy replacement.
if ($existingRole.Count -eq 0) { Invoke-Gcloud iam roles create $roleId "--file=$roleFile" --quiet | Out-Null }
if ($existingBucket.Count -eq 0) {
  Invoke-Gcloud storage buckets create "gs://$bucket" "--location=$Region" --uniform-bucket-level-access --public-access-prevention --quiet | Out-Null
}
Add-QuotationBucketBinding
if ($existingSecret.Count -eq 0) {
  Invoke-Gcloud secrets create $secret --replication-policy=user-managed "--locations=$Region" --quiet | Out-Null
}
if ($versions.Count -eq 0) {
  $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  $bytes = New-Object byte[] 32
  try {
    $random.GetBytes($bytes)
    # ASCII secret goes only to gcloud stdin: never an argument, file, plan, or console output.
    # Both services trim the resulting configuration value, including the pipeline newline.
    [Convert]::ToBase64String($bytes) | & $gcloud secrets versions add $secret --data-file=- "--project=$ProjectId" --quiet | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Policy secret version creation failed; no secret content was printed." }
  } finally { [Array]::Clear($bytes, 0, $bytes.Length); $random.Dispose() }
}
foreach ($identity in @($platformIdentity, $schoolIdentity)) {
  Invoke-Gcloud secrets add-iam-policy-binding $secret "--member=serviceAccount:$identity" --role=roles/secretmanager.secretAccessor --quiet | Out-Null
}
Invoke-Gcloud run services add-iam-policy-binding custoking-school-core-service-dev "--region=$Region" "--member=serviceAccount:$platformIdentity" --role=roles/run.invoker --quiet | Out-Null
if (-not $SkipSchedulers) {
  Assert-ProviderDryRun (Read-Service "custoking-platform-service-dev" $platformIdentity)
  & (Join-Path $PSScriptRoot "configure-async-relay-scheduler.ps1") -ProjectId $ProjectId -Environment dev -Region $Region -Service $serviceNames -Apply
  if (-not $?) { throw "Async Scheduler provisioning or 2xx verification did not complete." }
}
Write-Host "Dev prerequisites provisioned. No revision was deployed and broadcast mode was not changed. Deploy the reviewed manifests, verify private-file and drain behavior, then separately review DRY_RUN activation."

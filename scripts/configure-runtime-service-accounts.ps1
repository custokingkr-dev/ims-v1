param(
  [string]$ProjectId = "custoking",
  [string]$Region = "asia-south2",
  [ValidateSet("dev", "prod")]
  [string]$Environment = "dev",
  [switch]$Apply,
  [switch]$AllowProduction
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if ($Apply -and $Environment -eq "prod" -and -not $AllowProduction) {
  throw "Production runtime IAM changes require -AllowProduction."
}

function Invoke-Gcloud {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  $output = & $GcloudCommand @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "gcloud command failed: $($Arguments -join ' ')"
  }
  return $output
}

function Invoke-GcloudWithPropagationRetry {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  $maxAttempts = 12
  for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
    try {
      return Invoke-Gcloud @Arguments
    } catch {
      if ($attempt -eq $maxAttempts) {
        throw
      }
      Start-Sleep -Seconds 5
    }
  }
}

$serviceNames = @(
  "identity-service",
  "school-core-service",
  "operations-service",
  "platform-service",
  "billing-service",
  "api-gateway",
  "frontend"
)

$accountNames = [ordered]@{
  "identity-service" = "ims-identity-$Environment"
  "school-core-service" = "ims-school-core-$Environment"
  "operations-service" = "ims-operations-$Environment"
  "platform-service" = "ims-platform-$Environment"
  "billing-service" = "ims-billing-$Environment"
  "api-gateway" = "ims-api-gateway-$Environment"
  "frontend" = "ims-frontend-$Environment"
}

$secretMatrix = [ordered]@{
  "identity-service" = @(
    "app-rt-password-$Environment",
    "db-password-$Environment",
    "jwt-secret-$Environment",
    "identity-introspection-token-$Environment",
    "tenant-school-read-token-$Environment"
  )
  "school-core-service" = @(
    "app-rt-password-$Environment",
    "db-password-$Environment",
    "tenant-school-read-token-$Environment",
    "student-read-token-$Environment",
    "attendance-read-token-$Environment",
    "fee-read-token-$Environment",
    "catalog-read-token-$Environment",
    "student-photo-import-drive-oauth-client-id-$Environment",
    "student-photo-import-drive-oauth-client-secret-$Environment",
    "student-photo-import-drive-oauth-refresh-token-$Environment"
  )
  "operations-service" = @(
    "app-rt-password-$Environment",
    "db-password-$Environment",
    "workflow-read-token-$Environment",
    "firefighting-read-token-$Environment",
    "tenant-school-read-token-$Environment"
  )
  "platform-service" = @(
    "app-rt-password-$Environment",
    "db-password-$Environment",
    "reporting-read-token-$Environment",
    "audit-ingest-token-$Environment",
    "notification-status-token-$Environment",
    "msg91-auth-key-$Environment",
    "catalog-read-token-$Environment",
    "firefighting-read-token-$Environment"
  )
  "billing-service" = @(
    "app-rt-password-$Environment",
    "db-password-$Environment",
    "billing-service-token-$Environment"
  )
  "api-gateway" = @(
    "identity-introspection-token-$Environment",
    "tenant-school-read-token-$Environment",
    "student-read-token-$Environment",
    "attendance-read-token-$Environment",
    "fee-read-token-$Environment",
    "catalog-read-token-$Environment",
    "workflow-read-token-$Environment",
    "firefighting-read-token-$Environment",
    "reporting-read-token-$Environment",
    "billing-service-token-$Environment",
    "audit-ingest-token-$Environment",
    "notification-status-token-$Environment",
    "jwt-secret-$Environment"
  )
  "frontend" = @()
}

$projectRoles = [ordered]@{
  "identity-service" = @("roles/telemetry.tracesWriter", "roles/serviceusage.serviceUsageConsumer")
  "school-core-service" = @("roles/telemetry.tracesWriter", "roles/serviceusage.serviceUsageConsumer")
  "operations-service" = @("roles/telemetry.tracesWriter", "roles/serviceusage.serviceUsageConsumer")
  "platform-service" = @("roles/telemetry.tracesWriter", "roles/serviceusage.serviceUsageConsumer")
  "billing-service" = @("roles/telemetry.tracesWriter", "roles/serviceusage.serviceUsageConsumer")
  "api-gateway" = @("roles/cloudtrace.agent", "roles/serviceusage.serviceUsageConsumer")
  "frontend" = @()
}

# Caller -> private Cloud Run targets. Each binding is service-scoped.
$invokerMatrix = [ordered]@{
  "identity-service" = @("school-core-service")
  "operations-service" = @("school-core-service")
  "platform-service" = @("school-core-service", "operations-service")
  "api-gateway" = @(
    "identity-service",
    "school-core-service",
    "operations-service",
    "platform-service",
    "billing-service"
  )
}

$requiredSecrets = @($secretMatrix.Values | ForEach-Object { @($_) } | Sort-Object -Unique)
$availableSecrets = @(Invoke-Gcloud secrets list "--project=$ProjectId" --format="value(name)")
$missingSecrets = @($requiredSecrets | Where-Object { $availableSecrets -notcontains $_ })
$reportingTopic = "ims-reporting-events-v1-$Environment"
$availableTopics = @(Invoke-Gcloud pubsub topics list "--project=$ProjectId" --format="value(name.basename())")
$photoBucketName = "custoking-student-photos-$Environment"
$availableBuckets = @(Invoke-Gcloud storage buckets list "--project=$ProjectId" --format="value(name)") |
  ForEach-Object { ([string]$_) -replace '^gs://', '' }
$availableRunServices = @(Invoke-Gcloud run services list `
  "--project=$ProjectId" `
  "--region=$Region" `
  --platform=managed `
  --format="value(metadata.name)")
$requiredRunServices = @($invokerMatrix.Values | ForEach-Object { @($_) } | ForEach-Object {
  "custoking-$_-$Environment"
} | Sort-Object -Unique)
$missingRunServices = @($requiredRunServices | Where-Object { $availableRunServices -notcontains $_ })
$missingTopics = @($reportingTopic | Where-Object { $availableTopics -notcontains $_ })
$missingBuckets = @($photoBucketName | Where-Object { $availableBuckets -notcontains $_ })

$summary = [ordered]@{
  project = $ProjectId
  region = $Region
  environment = $Environment
  applyRequested = [bool]$Apply
  productionAuthorized = [bool]$AllowProduction
  serviceAccounts = @($serviceNames | ForEach-Object {
    $service = $_
    [string[]]$invokeTargets = @()
    if ($invokerMatrix.Contains($service)) {
      $invokeTargets = [string[]]@($invokerMatrix[$service])
    }
    [ordered]@{
      service = $service
      email = "$($accountNames[$service])@$ProjectId.iam.gserviceaccount.com"
      secretCount = @($secretMatrix[$service]).Count
      projectRoles = @($projectRoles[$service])
      invokes = [object[]]$invokeTargets
    }
  })
  resourceRoles = [ordered]@{
    reportingPublishers = @("school-core-service", "operations-service", "billing-service")
    studentPhotoObjectUser = "school-core-service"
    studentPhotoSelfSigner = "school-core-service"
  }
  preflight = [ordered]@{
    missingSecrets = $missingSecrets
    missingTopics = $missingTopics
    missingBuckets = $missingBuckets
    missingRunServices = $missingRunServices
  }
}

if (-not $Apply) {
  $summary | ConvertTo-Json -Depth 8
  Write-Host "Dry run only. Re-run with -Apply after reviewing the identity matrix."
  exit 0
}

if ($missingSecrets.Count -gt 0 -or $missingTopics.Count -gt 0 -or
    $missingBuckets.Count -gt 0 -or $missingRunServices.Count -gt 0) {
  throw "Runtime IAM preflight failed; review the dry-run preflight arrays."
}

foreach ($service in $serviceNames) {
  $accountName = $accountNames[$service]
  $accountEmail = "$accountName@$ProjectId.iam.gserviceaccount.com"
  $existing = ((Invoke-Gcloud iam service-accounts list `
    "--project=$ProjectId" `
    "--filter=email:$accountEmail" `
    --format="value(email)") -join "").Trim()
  if ([string]::IsNullOrWhiteSpace($existing)) {
    [void](Invoke-Gcloud iam service-accounts create $accountName `
      "--project=$ProjectId" `
      "--display-name=IMS $service runtime ($Environment)")
  }

  foreach ($role in @($projectRoles[$service])) {
    [void](Invoke-GcloudWithPropagationRetry projects add-iam-policy-binding $ProjectId `
      "--member=serviceAccount:$accountEmail" `
      "--role=$role" `
      --condition=None `
      --quiet)
  }

  foreach ($secret in @($secretMatrix[$service])) {
    [void](Invoke-GcloudWithPropagationRetry secrets add-iam-policy-binding $secret `
      "--project=$ProjectId" `
      "--member=serviceAccount:$accountEmail" `
      --role=roles/secretmanager.secretAccessor `
      --quiet)
  }
}

foreach ($service in @("school-core-service", "operations-service", "billing-service")) {
  $accountEmail = "$($accountNames[$service])@$ProjectId.iam.gserviceaccount.com"
  [void](Invoke-GcloudWithPropagationRetry pubsub topics add-iam-policy-binding $reportingTopic `
    "--project=$ProjectId" `
    "--member=serviceAccount:$accountEmail" `
    --role=roles/pubsub.publisher `
    --quiet)
}

$schoolCoreEmail = "$($accountNames['school-core-service'])@$ProjectId.iam.gserviceaccount.com"
$photoBucket = "gs://$photoBucketName"
[void](Invoke-GcloudWithPropagationRetry storage buckets add-iam-policy-binding $photoBucket `
  "--member=serviceAccount:$schoolCoreEmail" `
  --role=roles/storage.objectUser `
  --quiet)
[void](Invoke-GcloudWithPropagationRetry iam service-accounts add-iam-policy-binding $schoolCoreEmail `
  "--project=$ProjectId" `
  "--member=serviceAccount:$schoolCoreEmail" `
  --role=roles/iam.serviceAccountTokenCreator `
  --quiet)

foreach ($caller in $invokerMatrix.Keys) {
  $callerEmail = "$($accountNames[$caller])@$ProjectId.iam.gserviceaccount.com"
  foreach ($target in @($invokerMatrix[$caller])) {
    [void](Invoke-GcloudWithPropagationRetry run services add-iam-policy-binding "custoking-$target-$Environment" `
      "--project=$ProjectId" `
      "--region=$Region" `
      "--member=serviceAccount:$callerEmail" `
      --role=roles/run.invoker `
      --quiet)
  }
}

$summary["applied"] = $true
$summary | ConvertTo-Json -Depth 8

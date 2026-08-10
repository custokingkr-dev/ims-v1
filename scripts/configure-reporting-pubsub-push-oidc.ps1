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

if ($Environment -eq "prod" -and -not $AllowProduction) {
  throw "Production Pub/Sub migration requires -AllowProduction."
}

function Invoke-Gcloud {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  & $GcloudCommand @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "gcloud command failed: $($Arguments -join ' ')"
  }
}

$projectNumber = ((Invoke-Gcloud projects describe $ProjectId --format="value(projectNumber)") -join "").Trim()
$platformService = "custoking-platform-service-$Environment"
$subscription = "ims-reporting-service-push-$Environment"
$pushServiceAccountName = "ims-reporting-push-$Environment"
$pushServiceAccount = "$pushServiceAccountName@$ProjectId.iam.gserviceaccount.com"
$pubsubServiceAgent = "service-$projectNumber@gcp-sa-pubsub.iam.gserviceaccount.com"
$platformUrl = ((Invoke-Gcloud run services describe $platformService `
  "--project=$ProjectId" "--region=$Region" --format="value(status.url)") -join "").Trim()
$pushEndpoint = "$platformUrl/api/v1/pubsub/reporting-events"

$current = ((Invoke-Gcloud pubsub subscriptions describe $subscription `
  "--project=$ProjectId" --format=json) -join "`n") | ConvertFrom-Json
$currentUri = [uri][string]$current.pushConfig.pushEndpoint
$summary = [ordered]@{
  environment = $Environment
  subscription = $subscription
  platformService = $platformService
  pushServiceAccount = $pushServiceAccount
  desiredAudience = $platformUrl
  desiredEndpoint = $pushEndpoint
  currentHasQueryString = -not [string]::IsNullOrWhiteSpace($currentUri.Query)
  currentOidcServiceAccount = [string]$current.pushConfig.oidcToken.serviceAccountEmail
  applyRequested = [bool]$Apply
}

if (-not $Apply) {
  $summary | ConvertTo-Json
  Write-Host "Dry run only. Re-run with -Apply after reviewing the target."
  exit 0
}

& $GcloudCommand iam service-accounts describe $pushServiceAccount "--project=$ProjectId" *> $null
if ($LASTEXITCODE -ne 0) {
  Invoke-Gcloud iam service-accounts create $pushServiceAccountName `
    "--project=$ProjectId" `
    "--display-name=IMS reporting Pub/Sub push ($Environment)"
}

Invoke-Gcloud iam service-accounts add-iam-policy-binding $pushServiceAccount `
  "--project=$ProjectId" `
  "--member=serviceAccount:$pubsubServiceAgent" `
  --role=roles/iam.serviceAccountTokenCreator `
  --quiet

Invoke-Gcloud run services add-iam-policy-binding $platformService `
  "--project=$ProjectId" `
  "--region=$Region" `
  "--member=serviceAccount:$pushServiceAccount" `
  --role=roles/run.invoker `
  --quiet

Invoke-Gcloud pubsub subscriptions modify-push-config $subscription `
  "--project=$ProjectId" `
  "--push-endpoint=$pushEndpoint" `
  "--push-auth-service-account=$pushServiceAccount" `
  "--push-auth-token-audience=$platformUrl"

$updated = ((Invoke-Gcloud pubsub subscriptions describe $subscription `
  "--project=$ProjectId" --format=json) -join "`n") | ConvertFrom-Json
$updatedEndpoint = [uri][string]$updated.pushConfig.pushEndpoint
if (-not [string]::IsNullOrWhiteSpace($updatedEndpoint.Query)) {
  throw "Updated Pub/Sub endpoint still contains a query string."
}
if ([string]$updated.pushConfig.oidcToken.serviceAccountEmail -ne $pushServiceAccount) {
  throw "Updated Pub/Sub OIDC service account does not match the dedicated identity."
}
if ([string]$updated.pushConfig.oidcToken.audience -ne $platformUrl) {
  throw "Updated Pub/Sub OIDC audience does not match the Cloud Run service URL."
}

$summary.currentHasQueryString = $false
$summary.currentOidcServiceAccount = $pushServiceAccount
$summary.applied = $true
$summary | ConvertTo-Json

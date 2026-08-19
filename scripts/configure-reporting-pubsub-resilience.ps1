param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [ValidateSet("dev", "prod")]
  [string]$Environment = "dev",
  [ValidateRange(5, 100)]
  [int]$MaxDeliveryAttempts = 10,
  [switch]$Apply,
  [switch]$AllowProduction
)

$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
if ($Environment -eq "prod" -and -not $AllowProduction) {
  throw "Production reporting subscription changes require -AllowProduction."
}

function Invoke-Gcloud {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  & $gcloud @Arguments
  if ($LASTEXITCODE -ne 0) { throw "gcloud command failed: $($Arguments -join ' ')" }
}

function Test-Resource([string[]]$Arguments) {
  $previous = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    & $gcloud @Arguments *> $null
    return $LASTEXITCODE -eq 0
  } finally {
    $ErrorActionPreference = $previous
  }
}

$projectNumber = ((Invoke-Gcloud projects describe $ProjectId --format="value(projectNumber)") -join "").Trim()
$pubsubServiceAgent = "service-$projectNumber@gcp-sa-pubsub.iam.gserviceaccount.com"
$subscription = "ims-reporting-service-push-$Environment"
$deadLetterTopic = "ims-reporting-dead-letter-v1-$Environment"
$deadLetterSubscription = "ims-reporting-dead-letter-inspection-$Environment"

if (-not (Test-Resource @("pubsub", "subscriptions", "describe", $subscription, "--project=$ProjectId"))) {
  throw "Required reporting subscription $subscription does not exist."
}
$deadLetterTopicExists = Test-Resource @("pubsub", "topics", "describe", $deadLetterTopic, "--project=$ProjectId")
$deadLetterSubscriptionExists = Test-Resource @(
  "pubsub", "subscriptions", "describe", $deadLetterSubscription, "--project=$ProjectId"
)

$summary = [ordered]@{
  environment = $Environment
  subscription = $subscription
  deadLetterTopic = $deadLetterTopic
  deadLetterTopicExists = $deadLetterTopicExists
  deadLetterSubscription = $deadLetterSubscription
  deadLetterSubscriptionExists = $deadLetterSubscriptionExists
  maxDeliveryAttempts = $MaxDeliveryAttempts
  retryMinimum = "10s"
  retryMaximum = "600s"
  applyRequested = [bool]$Apply
}
if (-not $Apply) {
  $summary | ConvertTo-Json
  Write-Host "Dry run only. Re-run with -Apply to add bounded retries and dead-letter inspection."
  exit 0
}

if (-not $deadLetterTopicExists) {
  Invoke-Gcloud pubsub topics create $deadLetterTopic "--project=$ProjectId"
}
if (-not $deadLetterSubscriptionExists) {
  Invoke-Gcloud pubsub subscriptions create $deadLetterSubscription `
    "--project=$ProjectId" "--topic=$deadLetterTopic" `
    --expiration-period=never --message-retention-duration=7d
}
Invoke-Gcloud pubsub topics add-iam-policy-binding $deadLetterTopic `
  "--project=$ProjectId" "--member=serviceAccount:$pubsubServiceAgent" `
  --role=roles/pubsub.publisher --quiet
Invoke-Gcloud pubsub subscriptions add-iam-policy-binding $subscription `
  "--project=$ProjectId" "--member=serviceAccount:$pubsubServiceAgent" `
  --role=roles/pubsub.subscriber --quiet
Invoke-Gcloud pubsub subscriptions update $subscription `
  "--project=$ProjectId" --message-retention-duration=7d --expiration-period=never `
  --min-retry-delay=10s --max-retry-delay=600s `
  "--dead-letter-topic=$deadLetterTopic" "--max-delivery-attempts=$MaxDeliveryAttempts"

$updated = ((Invoke-Gcloud pubsub subscriptions describe $subscription `
  "--project=$ProjectId" --format=json) -join "`n") | ConvertFrom-Json
if (([string]$updated.deadLetterPolicy.deadLetterTopic -split '/')[-1] -ne $deadLetterTopic) {
  throw "Reporting dead-letter topic was not attached."
}
if ([int]$updated.deadLetterPolicy.maxDeliveryAttempts -ne $MaxDeliveryAttempts) {
  throw "Reporting max delivery attempts do not match the requested value."
}
if ([string]$updated.retryPolicy.minimumBackoff -ne "10s" -or
    [string]$updated.retryPolicy.maximumBackoff -ne "600s") {
  throw "Reporting retry policy does not match the requested 10s-600s range."
}

$summary.deadLetterTopicExists = $true
$summary.deadLetterSubscriptionExists = $true
$summary.applied = $true
$summary | ConvertTo-Json

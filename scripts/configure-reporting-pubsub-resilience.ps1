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
$deadLetterSubscriptionCandidates = @(
  "reporting-dead-letter-inspection-$Environment",
  "ims-reporting-dead-letter-inspection-$Environment"
)

if (-not (Test-Resource @("pubsub", "subscriptions", "describe", $subscription, "--project=$ProjectId"))) {
  throw "Required reporting subscription $subscription does not exist."
}
$currentSubscription = ((Invoke-Gcloud pubsub subscriptions describe $subscription `
  "--project=$ProjectId" --format=json) -join "`n") | ConvertFrom-Json
$deadLetterTopicExists = Test-Resource @("pubsub", "topics", "describe", $deadLetterTopic, "--project=$ProjectId")
$deadLetterSubscription = $deadLetterSubscriptionCandidates[0]
$deadLetterSubscriptionExists = $false
foreach ($candidate in $deadLetterSubscriptionCandidates) {
  if (Test-Resource @("pubsub", "subscriptions", "describe", $candidate, "--project=$ProjectId")) {
    $deadLetterSubscription = $candidate
    $deadLetterSubscriptionExists = $true
    break
  }
}
$currentDeadLetterSubscription = if ($deadLetterSubscriptionExists) {
  ((Invoke-Gcloud pubsub subscriptions describe $deadLetterSubscription `
    "--project=$ProjectId" --format=json) -join "`n") | ConvertFrom-Json
} else {
  $null
}
if ($deadLetterSubscriptionExists -and
    ([string]$currentDeadLetterSubscription.topic -split '/')[-1] -ne $deadLetterTopic) {
  throw "Reporting dead-letter inspection subscription targets an unexpected topic. Refusing to mutate it."
}

$summary = [ordered]@{
  environment = $Environment
  subscription = $subscription
  deadLetterTopic = $deadLetterTopic
  deadLetterTopicExists = $deadLetterTopicExists
  deadLetterSubscription = $deadLetterSubscription
  deadLetterSubscriptionExists = $deadLetterSubscriptionExists
  currentDeadLetterSubscriptionTopic = ([string]$currentDeadLetterSubscription.topic -split '/')[-1]
  maxDeliveryAttempts = $MaxDeliveryAttempts
  retryMinimum = "10s"
  retryMaximum = "600s"
  currentMaxDeliveryAttempts = [int]$currentSubscription.deadLetterPolicy.maxDeliveryAttempts
  currentRetryMinimum = [string]$currentSubscription.retryPolicy.minimumBackoff
  currentRetryMaximum = [string]$currentSubscription.retryPolicy.maximumBackoff
  currentAckDeadlineSeconds = [int]$currentSubscription.ackDeadlineSeconds
  currentMessageRetention = [string]$currentSubscription.messageRetentionDuration
  currentExpirationTtl = [string]$currentSubscription.expirationPolicy.ttl
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
Invoke-Gcloud pubsub subscriptions update $deadLetterSubscription `
  "--project=$ProjectId" --message-retention-duration=7d --expiration-period=never
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
if ([int]$updated.ackDeadlineSeconds -ne 10) {
  throw "Reporting acknowledgement deadline does not match the expected 10 seconds."
}
if ([string]$updated.messageRetentionDuration -ne "604800s") {
  throw "Reporting message retention does not match the required 7 days."
}
if (-not [string]::IsNullOrWhiteSpace([string]$updated.expirationPolicy.ttl)) {
  throw "Reporting subscription must not expire."
}
$updatedDeadLetterSubscription = ((Invoke-Gcloud pubsub subscriptions describe $deadLetterSubscription `
  "--project=$ProjectId" --format=json) -join "`n") | ConvertFrom-Json
if (([string]$updatedDeadLetterSubscription.topic -split '/')[-1] -ne $deadLetterTopic) {
  throw "Reporting dead-letter inspection subscription targets an unexpected topic."
}
if ([string]$updatedDeadLetterSubscription.messageRetentionDuration -ne "604800s" -or
    -not [string]::IsNullOrWhiteSpace([string]$updatedDeadLetterSubscription.expirationPolicy.ttl)) {
  throw "Reporting dead-letter inspection subscription must retain messages for 7 days without expiring."
}

$summary.deadLetterTopicExists = $true
$summary.deadLetterSubscriptionExists = $true
$summary.currentMaxDeliveryAttempts = [int]$updated.deadLetterPolicy.maxDeliveryAttempts
$summary.currentRetryMinimum = [string]$updated.retryPolicy.minimumBackoff
$summary.currentRetryMaximum = [string]$updated.retryPolicy.maximumBackoff
$summary.currentAckDeadlineSeconds = [int]$updated.ackDeadlineSeconds
$summary.currentMessageRetention = [string]$updated.messageRetentionDuration
$summary.currentExpirationTtl = [string]$updated.expirationPolicy.ttl
$summary.currentDeadLetterSubscriptionTopic = ([string]$updatedDeadLetterSubscription.topic -split '/')[-1]
$summary.applied = $true
$summary | ConvertTo-Json

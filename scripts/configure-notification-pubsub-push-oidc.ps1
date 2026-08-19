param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [string]$Region = "asia-south2",
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
  throw "Production notification topology changes require -AllowProduction."
}

function Invoke-Gcloud {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  & $gcloud @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "gcloud command failed: $($Arguments -join ' ')"
  }
}

function Test-GcloudResource {
  param([string[]]$Arguments)
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
$platformService = "custoking-platform-service-$Environment"
$topic = "ims-notifications-events-v1-$Environment"
$subscription = "ims-notification-service-push-$Environment"
$deadLetterTopic = "ims-notifications-dead-letter-v1-$Environment"
$deadLetterSubscription = "ims-notifications-dead-letter-inspection-$Environment"
$pushServiceAccountName = "ims-notification-push-$Environment"
$pushServiceAccount = "$pushServiceAccountName@$ProjectId.iam.gserviceaccount.com"
$pubsubServiceAgent = "service-$projectNumber@gcp-sa-pubsub.iam.gserviceaccount.com"
$platformUrl = ((Invoke-Gcloud run services describe $platformService `
  "--project=$ProjectId" "--region=$Region" --format="value(status.url)") -join "").Trim()
$pushEndpoint = "$platformUrl/api/v1/pubsub/notifications"

$topicExists = Test-GcloudResource @("pubsub", "topics", "describe", $topic, "--project=$ProjectId")
$subscriptionExists = Test-GcloudResource @("pubsub", "subscriptions", "describe", $subscription, "--project=$ProjectId")
$deadLetterTopicExists = Test-GcloudResource @("pubsub", "topics", "describe", $deadLetterTopic, "--project=$ProjectId")
$deadLetterSubscriptionExists = Test-GcloudResource @("pubsub", "subscriptions", "describe", $deadLetterSubscription, "--project=$ProjectId")

$summary = [ordered]@{
  environment = $Environment
  topic = $topic
  topicExists = $topicExists
  subscription = $subscription
  subscriptionExists = $subscriptionExists
  endpoint = $pushEndpoint
  audience = $platformUrl
  pushServiceAccount = $pushServiceAccount
  deadLetterTopic = $deadLetterTopic
  deadLetterTopicExists = $deadLetterTopicExists
  deadLetterSubscription = $deadLetterSubscription
  deadLetterSubscriptionExists = $deadLetterSubscriptionExists
  maxDeliveryAttempts = $MaxDeliveryAttempts
  applyRequested = [bool]$Apply
}

if (-not $topicExists) {
  throw "Required producer topic $topic does not exist. Refusing to invent a replacement topic."
}
if (-not $Apply) {
  $summary | ConvertTo-Json
  Write-Host "Dry run only. Deploy OIDC-only application support before re-running with -Apply."
  exit 0
}

$serviceAccountExists = Test-GcloudResource @(
  "iam", "service-accounts", "describe", $pushServiceAccount, "--project=$ProjectId"
)
if (-not $serviceAccountExists) {
  Invoke-Gcloud iam service-accounts create $pushServiceAccountName `
    "--project=$ProjectId" `
    "--display-name=IMS notification Pub/Sub push ($Environment)"
}

Invoke-Gcloud iam service-accounts add-iam-policy-binding $pushServiceAccount `
  "--project=$ProjectId" `
  "--member=serviceAccount:$pubsubServiceAgent" `
  --role=roles/iam.serviceAccountTokenCreator --quiet
Invoke-Gcloud run services add-iam-policy-binding $platformService `
  "--project=$ProjectId" "--region=$Region" `
  "--member=serviceAccount:$pushServiceAccount" --role=roles/run.invoker --quiet

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

if (-not $subscriptionExists) {
  Invoke-Gcloud pubsub subscriptions create $subscription `
    "--project=$ProjectId" "--topic=$topic" `
    "--push-endpoint=$pushEndpoint" `
    "--push-auth-service-account=$pushServiceAccount" `
    "--push-auth-token-audience=$platformUrl" `
    --ack-deadline=30 --message-retention-duration=7d --expiration-period=never `
    --min-retry-delay=10s --max-retry-delay=600s `
    "--dead-letter-topic=$deadLetterTopic" "--max-delivery-attempts=$MaxDeliveryAttempts"
} else {
  Invoke-Gcloud pubsub subscriptions modify-push-config $subscription `
    "--project=$ProjectId" "--push-endpoint=$pushEndpoint" `
    "--push-auth-service-account=$pushServiceAccount" `
    "--push-auth-token-audience=$platformUrl"
  Invoke-Gcloud pubsub subscriptions update $subscription `
    "--project=$ProjectId" --ack-deadline=30 --message-retention-duration=7d `
    --expiration-period=never --min-retry-delay=10s --max-retry-delay=600s `
    "--dead-letter-topic=$deadLetterTopic" "--max-delivery-attempts=$MaxDeliveryAttempts"
}

Invoke-Gcloud pubsub subscriptions add-iam-policy-binding $subscription `
  "--project=$ProjectId" "--member=serviceAccount:$pubsubServiceAgent" `
  --role=roles/pubsub.subscriber --quiet

$updated = ((Invoke-Gcloud pubsub subscriptions describe $subscription `
  "--project=$ProjectId" --format=json) -join "`n") | ConvertFrom-Json
$updatedEndpoint = [uri][string]$updated.pushConfig.pushEndpoint
if (-not [string]::IsNullOrWhiteSpace($updatedEndpoint.Query)) {
  throw "Notification push endpoint must not contain a query string."
}
if ([string]$updated.pushConfig.oidcToken.serviceAccountEmail -ne $pushServiceAccount) {
  throw "Notification push OIDC service account does not match the dedicated identity."
}
if ([string]$updated.pushConfig.oidcToken.audience -ne $platformUrl) {
  throw "Notification push OIDC audience does not match the Cloud Run service URL."
}
if (([string]$updated.deadLetterPolicy.deadLetterTopic -split '/')[-1] -ne $deadLetterTopic) {
  throw "Notification dead-letter topic was not attached."
}
if ([int]$updated.deadLetterPolicy.maxDeliveryAttempts -ne $MaxDeliveryAttempts) {
  throw "Notification max delivery attempts do not match the requested value."
}

$summary.subscriptionExists = $true
$summary.deadLetterTopicExists = $true
$summary.deadLetterSubscriptionExists = $true
$summary.applied = $true
$summary | ConvertTo-Json

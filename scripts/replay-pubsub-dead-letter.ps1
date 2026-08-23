param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [Parameter(Mandatory = $true)]
  [ValidateSet("reporting", "notifications")]
  [string]$Pipeline,
  [ValidateSet("dev", "prod")]
  [string]$Environment = "dev",
  [ValidateRange(1, 100)]
  [int]$MaxMessages = 10,
  [switch]$InspectOnly,
  [switch]$Apply,
  [switch]$AllowProduction
)

$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if ($Environment -eq "prod" -and $Apply -and -not $AllowProduction) {
  throw "Production dead-letter replay requires -AllowProduction."
}
if ($InspectOnly -and $Apply) {
  throw "-InspectOnly cannot be combined with -Apply."
}

$sourceTopic = if ($Pipeline -eq "reporting") {
  "ims-reporting-events-v1-$Environment"
} else {
  "ims-notifications-events-v1-$Environment"
}
$expectedDeadLetterTopic = if ($Pipeline -eq "reporting") {
  "ims-reporting-dead-letter-v1-$Environment"
} else {
  "ims-notifications-dead-letter-v1-$Environment"
}
$deadLetterSubscriptionCandidates = if ($Pipeline -eq "reporting") {
  @(
    "reporting-dead-letter-inspection-$Environment",
    "ims-reporting-dead-letter-inspection-$Environment"
  )
} else {
  @(
    "notifications-dead-letter-inspection-$Environment",
    "ims-notifications-dead-letter-inspection-$Environment"
  )
}

$deadLetterSubscription = $null
foreach ($candidate in $deadLetterSubscriptionCandidates) {
  & $gcloud pubsub subscriptions describe $candidate "--project=$ProjectId" --format="value(name)" *> $null
  if ($LASTEXITCODE -eq 0) {
    $deadLetterSubscription = $candidate
    break
  }
}

if ([string]::IsNullOrWhiteSpace($deadLetterSubscription)) {
  throw "No dead-letter inspection subscription exists. Checked: $($deadLetterSubscriptionCandidates -join ', ')."
}

$descriptionJson = & $gcloud pubsub subscriptions describe $deadLetterSubscription `
  "--project=$ProjectId" --format=json
if ($LASTEXITCODE -ne 0) {
  throw "Could not inspect dead-letter subscription $deadLetterSubscription."
}
$description = (($descriptionJson -join "`n") | ConvertFrom-Json)
$actualDeadLetterTopic = ([string]$description.topic -split '/')[-1]
if ($actualDeadLetterTopic -ne $expectedDeadLetterTopic) {
  throw "Dead-letter inspection subscription $deadLetterSubscription targets '$actualDeadLetterTopic', expected '$expectedDeadLetterTopic'. Refusing to replay from an ambiguous source."
}

if (-not $Apply) {
  [ordered]@{
    pipeline = $Pipeline
    environment = $Environment
    sourceTopic = $sourceTopic
    deadLetterSubscription = $deadLetterSubscription
    deadLetterTopic = $actualDeadLetterTopic
    state = [string]$description.state
    inspectOnly = [bool]$InspectOnly
    applyRequested = $false
    messagesPulled = 0
    messagesAcknowledged = 0
  } | ConvertTo-Json
  Write-Host "Read-only inspection. No messages were pulled, leased, published, or acknowledged. Re-run with -Apply to perform a replay."
  exit 0
}

# Pulling leases messages temporarily, so it is permitted only after the explicit Apply gate.
# Apply republishes each original payload first and acknowledges only successful publishes.
$pulledJson = & $gcloud pubsub subscriptions pull $deadLetterSubscription `
  "--project=$ProjectId" "--limit=$MaxMessages" --format=json
if ($LASTEXITCODE -ne 0) { throw "Could not pull dead-letter messages." }
$parsed = $pulledJson | ConvertFrom-Json
$received = [System.Collections.Generic.List[object]]::new()
foreach ($entry in $parsed) {
  $received.Add($entry)
}

[ordered]@{
  pipeline = $Pipeline
  environment = $Environment
  sourceTopic = $sourceTopic
  deadLetterSubscription = $deadLetterSubscription
  pulled = $received.Count
  inspectOnly = $false
  applyRequested = [bool]$Apply
} | ConvertTo-Json

if ($received.Count -eq 0) {
  Write-Host "No dead-letter messages were available to replay."
  exit 0
}

$accessToken = ((& $gcloud auth print-access-token) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($accessToken)) {
  throw "Could not obtain a Google Cloud access token."
}
$headers = @{ Authorization = "Bearer $accessToken" }
$publishUri = "https://pubsub.googleapis.com/v1/projects/$ProjectId/topics/${sourceTopic}:publish"
$ackUri = "https://pubsub.googleapis.com/v1/projects/$ProjectId/subscriptions/${deadLetterSubscription}:acknowledge"
$ackIds = [System.Collections.Generic.List[string]]::new()

foreach ($item in $received) {
  $message = [ordered]@{ data = [string]$item.message.data }
  if ($item.message.attributes) {
    $attributes = [ordered]@{}
    foreach ($property in $item.message.attributes.PSObject.Properties) {
      if ($property.Name -notlike "x-goog-pubsub-source-*") {
        $attributes[$property.Name] = [string]$property.Value
      }
    }
    if ($attributes.Count -gt 0) { $message.attributes = $attributes }
  }
  $body = @{ messages = @($message) } | ConvertTo-Json -Depth 8
  $published = Invoke-RestMethod -Method Post -Uri $publishUri -Headers $headers `
    -ContentType "application/json" -Body $body
  if (@($published.messageIds).Count -ne 1) {
    throw "Replay publish did not return exactly one message id; no remaining messages were acknowledged."
  }
  $ackIds.Add([string]$item.ackId)
}

Invoke-RestMethod -Method Post -Uri $ackUri -Headers $headers -ContentType "application/json" `
  -Body (@{ ackIds = @($ackIds) } | ConvertTo-Json -Depth 4) | Out-Null
Write-Host "Replayed and acknowledged $($ackIds.Count) dead-letter message(s)."

param(
  [string]$ProjectId = "custoking",
  [ValidateSet("reporting", "notifications")]
  [string]$Pipeline,
  [ValidateSet("dev", "prod")]
  [string]$Environment = "dev",
  [ValidateRange(1, 100)]
  [int]$MaxMessages = 10,
  [switch]$Apply,
  [switch]$AllowProduction
)

$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if ($Environment -eq "prod" -and -not $AllowProduction) {
  throw "Production dead-letter replay requires -AllowProduction."
}

$sourceTopic = if ($Pipeline -eq "reporting") {
  "ims-reporting-events-v1-$Environment"
} else {
  "ims-notifications-events-v1-$Environment"
}
$deadLetterSubscription = "ims-$Pipeline-dead-letter-inspection-$Environment"

& $gcloud pubsub subscriptions describe $deadLetterSubscription "--project=$ProjectId" --format="value(name)" | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw "Dead-letter inspection subscription $deadLetterSubscription does not exist."
}

# Pulling leases messages temporarily but does not acknowledge them. Dry-run therefore never removes
# data; Apply republishes each original payload first and acknowledges only successful publishes.
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
  applyRequested = [bool]$Apply
} | ConvertTo-Json

if (-not $Apply -or $received.Count -eq 0) {
  Write-Host "No messages were acknowledged. Re-run with -Apply after inspecting the dead-letter cause."
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

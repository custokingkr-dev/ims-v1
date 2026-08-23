param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [string]$Region = "asia-south2",
  [string]$SchedulerLocation = "asia-south1",
  [ValidateSet("dev", "prod")]
  [string]$Environment = "dev",
  [string]$Schedule = "* * * * *",
  [ValidateRange(60, 600)]
  [int]$DeliveryVerificationTimeoutSeconds = 180,
  [switch]$Apply,
  [switch]$EnableCloudSchedulerApi,
  [switch]$AllowProduction
)

$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
if ($Environment -eq "prod" -and -not $AllowProduction) {
  throw "Production scheduler changes require -AllowProduction."
}
if ($EnableCloudSchedulerApi -and -not $Apply) {
  throw "-EnableCloudSchedulerApi is meaningful only together with -Apply."
}

function Invoke-Gcloud {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  & $gcloud @Arguments
  if ($LASTEXITCODE -ne 0) { throw "gcloud command failed: $($Arguments -join ' ')" }
}

function Test-GcloudResource([string[]]$Arguments) {
  $previous = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    & $gcloud @Arguments *> $null
    return $LASTEXITCODE -eq 0
  } finally {
    $ErrorActionPreference = $previous
  }
}

$apiEnabled = -not [string]::IsNullOrWhiteSpace(((Invoke-Gcloud services list `
  "--project=$ProjectId" --enabled --filter="name:cloudscheduler.googleapis.com" `
  --format="value(name)") -join "").Trim())
$serviceAccountName = "ims-async-scheduler-$Environment"
$serviceAccount = "$serviceAccountName@$ProjectId.iam.gserviceaccount.com"
$targets = @(
  [ordered]@{ service = "school-core-service"; path = "/api/v1/internal/outbox/relay" },
  [ordered]@{ service = "operations-service"; path = "/api/v1/internal/outbox/relay" },
  [ordered]@{ service = "billing-service"; path = "/api/v1/internal/outbox/relay" },
  [ordered]@{ service = "platform-service"; path = "/api/v1/internal/async/drain" }
)

$resolved = @()
foreach ($target in $targets) {
  $cloudRunService = "custoking-$($target.service)-$Environment"
  $serviceUrl = ((Invoke-Gcloud run services describe $cloudRunService `
    "--project=$ProjectId" "--region=$Region" --format="value(status.url)") -join "").Trim()
  $resolved += [ordered]@{
    job = "ims-$($target.service)-async-relay-$Environment"
    service = $cloudRunService
    uri = "$serviceUrl$($target.path)"
    audience = $serviceUrl
  }
}

[ordered]@{
  environment = $Environment
  cloudSchedulerApiEnabled = $apiEnabled
  schedulerLocation = $SchedulerLocation
  cloudRunRegion = $Region
  schedule = $Schedule
  timeZone = "Etc/UTC"
  invokerServiceAccount = $serviceAccount
  jobs = $resolved
  estimatedJobs = $resolved.Count
  applyRequested = [bool]$Apply
} | ConvertTo-Json -Depth 6

if (-not $Apply) {
  Write-Host "Dry run only. No API, IAM, or Scheduler resource was changed."
  exit 0
}
if (-not $apiEnabled) {
  if (-not $EnableCloudSchedulerApi) {
    throw "Cloud Scheduler API is disabled. Review cost/IAM, then pass -EnableCloudSchedulerApi explicitly."
  }
  Invoke-Gcloud services enable cloudscheduler.googleapis.com "--project=$ProjectId"
}

$supportedLocations = @((Invoke-Gcloud scheduler locations list `
  "--project=$ProjectId" --format="value(locationId)") | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($SchedulerLocation -notin $supportedLocations) {
  throw "Cloud Scheduler location '$SchedulerLocation' is not supported. Supported locations: $($supportedLocations -join ', ')."
}

$serviceAccountExists = Test-GcloudResource @(
  "iam", "service-accounts", "describe", $serviceAccount, "--project=$ProjectId"
)
if (-not $serviceAccountExists) {
  Invoke-Gcloud iam service-accounts create $serviceAccountName `
    "--project=$ProjectId" "--display-name=IMS async relay scheduler ($Environment)"
}

foreach ($target in $resolved) {
  Invoke-Gcloud run services add-iam-policy-binding $target.service `
    "--project=$ProjectId" "--region=$Region" `
    "--member=serviceAccount:$serviceAccount" --role=roles/run.invoker --quiet

  $jobExists = Test-GcloudResource @(
    "scheduler", "jobs", "describe", $target.job, "--project=$ProjectId", "--location=$SchedulerLocation"
  )
  $verb = if ($jobExists) { "update" } else { "create" }
  Invoke-Gcloud scheduler jobs $verb http $target.job `
    "--project=$ProjectId" "--location=$SchedulerLocation" `
    "--schedule=$Schedule" --time-zone=Etc/UTC `
    "--uri=$($target.uri)" --http-method=POST `
    "--oidc-service-account-email=$serviceAccount" `
    "--oidc-token-audience=$($target.audience)" `
    --headers=Content-Type=application/json --message-body="{}" `
    --attempt-deadline=300s --max-retry-attempts=3 `
    --min-backoff=10s --max-backoff=300s --max-doublings=3
}

foreach ($target in $resolved) {
  $job = ((Invoke-Gcloud scheduler jobs describe $target.job `
    "--project=$ProjectId" "--location=$SchedulerLocation" --format=json) -join "`n") | ConvertFrom-Json
  if ([string]$job.httpTarget.oidcToken.serviceAccountEmail -ne $serviceAccount) {
    throw "Scheduler job $($target.job) does not use the dedicated service account."
  }
  if ([string]$job.httpTarget.oidcToken.audience -ne [string]$target.audience) {
    throw "Scheduler job $($target.job) has an unexpected OIDC audience."
  }
  if ([string]$job.httpTarget.uri -ne [string]$target.uri) {
    throw "Scheduler job $($target.job) has an unexpected target URI."
  }
}

# A successful IAM policy write does not mean Cloud Run's invoker check has
# converged everywhere yet. The Scheduler retry policy covers that propagation
# window, but the provisioning procedure must wait for a real 2xx delivery so
# an initial 403 cannot be mistaken for a completed rollout.
$verificationStartedAt = (Get-Date).ToUniversalTime()
$verificationDeadline = (Get-Date).AddSeconds($DeliveryVerificationTimeoutSeconds)
$verified = @{}
$latestStatus = @{}

# Trigger a bounded verification attempt instead of assuming the configured
# cron schedule will fire inside the verification window (operators may choose
# an hourly or school-day-only schedule). The relay endpoints are idempotent and
# the explicit -Apply gate already authorizes their activation.
foreach ($target in $resolved) {
  Invoke-Gcloud scheduler jobs run $target.job `
    "--project=$ProjectId" "--location=$SchedulerLocation"
}

do {
  $logJson = ((Invoke-Gcloud logging read "resource.type=cloud_run_revision" `
    "--project=$ProjectId" --freshness=10m --limit=1000 --order=desc --format=json) -join "`n")
  $entries = if ([string]::IsNullOrWhiteSpace($logJson)) { @() } else { @($logJson | ConvertFrom-Json) }
  foreach ($target in $resolved) {
    $attempts = @($entries | Where-Object {
      $_.httpRequest.userAgent -eq "Google-Cloud-Scheduler" -and
      $_.httpRequest.requestUrl -eq [string]$target.uri -and
      ([DateTimeOffset]$_.timestamp).UtcDateTime -ge $verificationStartedAt
    } | Sort-Object { [DateTimeOffset]$_.timestamp } -Descending)
    if ($attempts.Count -gt 0) {
      $latestStatus[$target.job] = [int]$attempts[0].httpRequest.status
      if ($attempts | Where-Object { [int]$_.httpRequest.status -ge 200 -and [int]$_.httpRequest.status -lt 300 }) {
        $verified[$target.job] = $true
      }
    }
  }
  if ($verified.Count -eq $resolved.Count) { break }
  Start-Sleep -Seconds 10
} while ((Get-Date) -lt $verificationDeadline)

$unverified = @($resolved | Where-Object { -not $verified.ContainsKey($_.job) })
if ($unverified.Count -gt 0) {
  $details = $unverified | ForEach-Object {
    $status = if ($latestStatus.ContainsKey($_.job)) { $latestStatus[$_.job] } else { "no request observed" }
    "$($_.job) ($status)"
  }
  throw "Scheduler delivery did not reach 2xx within $DeliveryVerificationTimeoutSeconds seconds: $($details -join ', '). IAM propagation can produce transient 403 responses; leave retries enabled and inspect Cloud Run request logs before retrying configuration."
}

Write-Host "Configured and verified $($resolved.Count) OIDC-authenticated async relay jobs; every target returned 2xx."

param(
  [string]$ProjectId = "custoking",
  [string]$Region = "asia-south2",
  [ValidateSet("dev", "prod")]
  [string]$Environment = "dev",
  [string]$Schedule = "* * * * *",
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
    "scheduler", "jobs", "describe", $target.job, "--project=$ProjectId", "--location=$Region"
  )
  $verb = if ($jobExists) { "update" } else { "create" }
  Invoke-Gcloud scheduler jobs $verb http $target.job `
    "--project=$ProjectId" "--location=$Region" `
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
    "--project=$ProjectId" "--location=$Region" --format=json) -join "`n") | ConvertFrom-Json
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
Write-Host "Configured and verified $($resolved.Count) OIDC-authenticated async relay jobs."

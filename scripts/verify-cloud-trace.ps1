param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [ValidateSet("dev", "prod")]
  [string]$Environment = "dev",
  [string]$Region = "asia-south2",
  [string]$ServicePrefix = "custoking",
  [string[]]$Services = @(
    "api-gateway",
    "billing-service",
    "identity-service",
    "operations-service",
    "platform-service",
    "school-core-service"
  ),
  [ValidateRange(1, 1440)]
  [int]$LookbackMinutes = 60,
  [ValidateRange(1, 100)]
  [int]$PageSize = 20,
  [ValidateRange(0, 1440)]
  [int]$ExporterErrorLookbackMinutes = 30,
  [switch]$AllowExporterErrors
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

function Get-AccessToken {
  $token = ((& $GcloudCommand auth print-access-token) -join "").Trim()
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($token)) {
    throw "Could not obtain a Google Cloud access token."
  }
  return $token
}

function Get-LiveServiceMetadata {
  param([string]$ServiceName)

  $cloudRunService = "$ServicePrefix-$ServiceName-$Environment"
  $json = (& $GcloudCommand run services describe $cloudRunService `
    --project $ProjectId --region $Region --format=json) -join "`n"
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($json)) {
    throw "Could not describe Cloud Run service '$cloudRunService'."
  }
  $service = $json | ConvertFrom-Json
  $revision = [string]$service.status.latestReadyRevisionName
  $resourceAttributes = [string](@($service.spec.template.spec.containers[0].env | Where-Object {
    $_.name -eq "OTEL_RESOURCE_ATTRIBUTES"
  } | Select-Object -First 1).value)
  $attributes = @{}
  foreach ($entry in @($resourceAttributes -split ",")) {
    $parts = $entry -split "=", 2
    if ($parts.Count -eq 2) { $attributes[$parts[0].Trim()] = $parts[1].Trim() }
  }
  $serviceVersion = [string]$attributes["service.version"]
  $deployedEnvironment = [string]$attributes["deployment.environment.name"]
  if ([string]::IsNullOrWhiteSpace($revision)) {
    throw "Cloud Run service '$cloudRunService' has no latest ready revision."
  }
  if ($deployedEnvironment -ne $Environment) {
    throw "Cloud Run service '$cloudRunService' has deployment.environment.name='$deployedEnvironment', expected '$Environment'."
  }
  if ([string]::IsNullOrWhiteSpace($serviceVersion) -or $serviceVersion -eq "unknown") {
    throw "Cloud Run service '$cloudRunService' does not expose a deployed service.version."
  }
  return [ordered]@{
    cloudRunService = $cloudRunService
    revision = $revision
    version = $serviceVersion
  }
}

function Get-Traces {
  param(
    [string]$ServiceName,
    [string]$ServiceVersion,
    [hashtable]$Headers,
    [datetime]$Start,
    [datetime]$End
  )

  $query = [ordered]@{
    startTime = $Start.ToUniversalTime().ToString("o")
    endTime = $End.ToUniversalTime().ToString("o")
    filter = "+service.name:$ServiceName +deployment.environment.name:$Environment +service.version:$ServiceVersion"
    view = "ROOTSPAN"
    pageSize = $PageSize
    orderBy = "start desc"
  }
  $queryString = @($query.GetEnumerator() | ForEach-Object {
    "$([uri]::EscapeDataString([string]$_.Key))=$([uri]::EscapeDataString([string]$_.Value))"
  }) -join "&"
  $uri = "https://cloudtrace.googleapis.com/v1/projects/$([uri]::EscapeDataString($ProjectId))/traces?$queryString"
  $response = Invoke-RestMethod -Method Get -Uri $uri -Headers $Headers -TimeoutSec 30
  return @($response.traces | Where-Object { $null -ne $_ -and $null -ne $_.spans })
}

$end = [datetime]::UtcNow
$start = $end.AddMinutes(-$LookbackMinutes)
$headers = @{ Authorization = "Bearer $(Get-AccessToken)" }
$results = @()
$liveServices = @{}

foreach ($service in $Services) {
  if ($service -notmatch '^[a-z0-9][a-z0-9-]*[a-z0-9]$') {
    throw "Invalid service name '$service'."
  }
  $metadata = Get-LiveServiceMetadata -ServiceName $service
  $liveServices[$service] = $metadata
  $traces = @(Get-Traces -ServiceName $service -ServiceVersion $metadata.version -Headers $headers -Start $start -End $end)
  $latestStart = if ($traces.Count -gt 0) { [string]$traces[0].spans[0].startTime } else { $null }
  $results += [ordered]@{
    service = $service
    environment = $Environment
    cloudRunService = $metadata.cloudRunService
    revision = $metadata.revision
    version = $metadata.version
    traces = $traces.Count
    latestStartTime = $latestStart
    status = if ($traces.Count -gt 0) { "PASS" } else { "FAIL" }
  }
}

$exporterErrors = @()
if ($ExporterErrorLookbackMinutes -gt 0) {
  $errorStart = $end.AddMinutes(-$ExporterErrorLookbackMinutes).ToString("o")
  $serviceFilter = @($Services | Where-Object { $_ -ne "api-gateway" } | ForEach-Object {
    'resource.labels.revision_name="{0}"' -f $liveServices[$_].revision
  }) -join " OR "
  if (-not [string]::IsNullOrWhiteSpace($serviceFilter)) {
    $loggingFilter = 'resource.type="cloud_run_revision" AND ({0}) AND timestamp>="{1}" AND (textPayload:"Failed to export spans" OR jsonPayload.message:"Failed to export spans")' -f $serviceFilter, $errorStart
    $body = [ordered]@{
      resourceNames = @("projects/$ProjectId")
      filter = $loggingFilter
      orderBy = "timestamp desc"
      pageSize = 100
    } | ConvertTo-Json -Compress
    $loggingResponse = Invoke-RestMethod -Method Post `
      -Uri "https://logging.googleapis.com/v2/entries:list" `
      -Headers $headers -ContentType "application/json" -Body $body -TimeoutSec 30
    $exporterErrors = @($loggingResponse.entries | Where-Object { $null -ne $_ } | ForEach-Object {
      [ordered]@{
        timestamp = [string]$_.timestamp
        service = [string]$_.resource.labels.service_name
        revision = [string]$_.resource.labels.revision_name
      }
    })
  }
}

$failedServices = @($results | Where-Object { $_.status -ne "PASS" })
$status = if ($failedServices.Count -eq 0 -and ($AllowExporterErrors -or $exporterErrors.Count -eq 0)) { "PASS" } else { "FAIL" }
$evidence = [ordered]@{
  status = $status
  projectId = $ProjectId
  environment = $Environment
  region = $Region
  checkedAtUtc = $end.ToString("o")
  lookbackMinutes = $LookbackMinutes
  exporterErrorLookbackMinutes = $ExporterErrorLookbackMinutes
  services = $results
  exporterErrorCount = $exporterErrors.Count
  exporterErrors = $exporterErrors
}

$evidence | ConvertTo-Json -Depth 8
if ($status -ne "PASS") {
  throw "Cloud Trace verification failed: missingServices=$($failedServices.Count) exporterErrors=$($exporterErrors.Count)."
}

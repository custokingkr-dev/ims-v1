param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [string]$InstanceName = "custoking-db-dev",
  [Parameter(Mandatory = $true)]
  [ValidateSet("start", "stop", "status")]
  [string]$State,
  [switch]$Wait,
  [int]$TimeoutSeconds = 1200
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if ($InstanceName -notmatch "-dev$") {
  throw "Refusing to manage non-development Cloud SQL instance '$InstanceName'."
}

function Get-InstanceStatus {
  $json = & $GcloudCommand sql instances describe $InstanceName "--project=$ProjectId" --format=json
  if ($LASTEXITCODE -ne 0) { throw "Could not read Cloud SQL instance '$InstanceName'." }
  $instance = $json | ConvertFrom-Json
  return [pscustomobject]@{
    State = [string]$instance.state
    ActivationPolicy = [string]$instance.settings.activationPolicy
  }
}

if ($State -eq "status") {
  $status = Get-InstanceStatus
  Write-Output "$($status.State) activation=$($status.ActivationPolicy)"
  exit 0
}

$activationPolicy = if ($State -eq "start") { "ALWAYS" } else { "NEVER" }
$current = Get-InstanceStatus
$activationMatches = $current.ActivationPolicy -eq $activationPolicy
$runtimeMatches = if ($State -eq "start") {
  $current.State -eq "RUNNABLE"
} else {
  $current.State -eq "STOPPED"
}
$operationName = $null

if ($activationMatches -and $runtimeMatches) {
  Write-Host "Cloud SQL instance $InstanceName is already $($current.State) with activation policy $($current.ActivationPolicy)."
  exit 0
}

if (-not $activationMatches) {
  $operationJson = & $GcloudCommand sql instances patch $InstanceName `
    "--project=$ProjectId" `
    "--activation-policy=$activationPolicy" `
    --async `
    --quiet `
    --format=json
  if ($LASTEXITCODE -ne 0) { throw "Could not request Cloud SQL $State for '$InstanceName'." }
  $operation = ($operationJson -join "`n") | ConvertFrom-Json
  $operationName = [string]$operation.name
  if ([string]::IsNullOrWhiteSpace($operationName)) {
    throw "Cloud SQL $State request did not return an operation id for '$InstanceName'."
  }
}

if (-not $Wait) {
  Write-Host "Requested Cloud SQL $State for $InstanceName."
  exit 0
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
if (-not [string]::IsNullOrWhiteSpace($operationName)) {
  do {
    $operationJson = & $GcloudCommand sql operations describe $operationName `
      "--project=$ProjectId" `
      --format=json
    if ($LASTEXITCODE -ne 0) {
      if ((Get-Date) -ge $deadline) {
        throw "Could not read Cloud SQL operation '$operationName' before timeout."
      }
      Start-Sleep -Seconds 5
      continue
    }
    $operation = ($operationJson -join "`n") | ConvertFrom-Json
    if ([string]$operation.status -eq "DONE") {
      if ($null -ne $operation.PSObject.Properties["error"]) {
        throw "Cloud SQL $State operation '$operationName' completed with an error."
      }
      break
    }
    Start-Sleep -Seconds 10
  } while ((Get-Date) -lt $deadline)

  if ([string]$operation.status -ne "DONE") {
    throw "Timed out waiting for Cloud SQL operation '$operationName'."
  }
}

do {
  $current = Get-InstanceStatus
  $activationMatches = $current.ActivationPolicy -eq $activationPolicy
  $runtimeMatches = if ($State -eq "start") {
    $current.State -eq "RUNNABLE"
  } else {
    $current.State -eq "STOPPED"
  }
  if ($activationMatches -and $runtimeMatches) {
    Write-Host "Cloud SQL instance $InstanceName is $($current.State) with activation policy $($current.ActivationPolicy)."
    exit 0
  }
  Start-Sleep -Seconds 10
} while ((Get-Date) -lt $deadline)

throw "Timed out waiting for $InstanceName to apply activation policy $activationPolicy; current state is $($current.State), activation policy $($current.ActivationPolicy)."

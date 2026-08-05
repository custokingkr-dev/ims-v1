param(
  [string]$ProjectId = "custoking",
  [string]$InstanceName = "custoking-db-dev",
  [Parameter(Mandatory = $true)]
  [ValidateSet("start", "stop", "status")]
  [string]$State,
  [switch]$Wait,
  [int]$TimeoutSeconds = 600
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
$runtimeMatches = $State -eq "stop" -or $current.State -eq "RUNNABLE"

if ($activationMatches -and $runtimeMatches) {
  Write-Host "Cloud SQL instance $InstanceName is already $($current.State) with activation policy $($current.ActivationPolicy)."
  exit 0
}

if (-not $activationMatches) {
  & $GcloudCommand sql instances patch $InstanceName `
    "--project=$ProjectId" `
    "--activation-policy=$activationPolicy" `
    --async `
    --quiet
  if ($LASTEXITCODE -ne 0) { throw "Could not request Cloud SQL $State for '$InstanceName'." }
}

if (-not $Wait) {
  Write-Host "Requested Cloud SQL $State for $InstanceName."
  exit 0
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
  $current = Get-InstanceStatus
  $activationMatches = $current.ActivationPolicy -eq $activationPolicy
  $runtimeMatches = $State -eq "stop" -or $current.State -eq "RUNNABLE"
  if ($activationMatches -and $runtimeMatches) {
    Write-Host "Cloud SQL instance $InstanceName is $($current.State) with activation policy $($current.ActivationPolicy)."
    exit 0
  }
  Start-Sleep -Seconds 10
} while ((Get-Date) -lt $deadline)

throw "Timed out waiting for $InstanceName to apply activation policy $activationPolicy; current state is $($current.State), activation policy $($current.ActivationPolicy)."

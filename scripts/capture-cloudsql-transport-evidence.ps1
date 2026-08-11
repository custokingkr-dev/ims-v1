param(
  [ValidateSet("dev", "prod")]
  [string]$Environment = "dev",
  [string]$ProjectId = "custoking",
  [string]$Region = "asia-south2",
  [string]$InstanceName = "",
  [string]$JobName = "",
  [string]$Database = "",
  [string]$DbUser = "appuser",
  [ValidateRange(1, 65535)]
  [int]$Port = 5432,
  [string]$OutputJson = "",
  [ValidateRange(60, 900)]
  [int]$TimeoutSeconds = 300,
  [switch]$AllowProductionEvidenceCapture,
  [string]$ConfirmProductionInstance = "",
  [string]$GcloudPath = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = [System.IO.Path]::GetFullPath((Resolve-Path (Join-Path $PSScriptRoot "..")))
$captureRequestedAtUtc = [datetime]::UtcNow
$expectedInstance = "custoking-db-$Environment"
$expectedJob = "ims-gateway-smoke-sql-$Environment"

if ([string]::IsNullOrWhiteSpace($InstanceName)) {
  $InstanceName = $expectedInstance
}
if ([string]::IsNullOrWhiteSpace($JobName)) {
  $JobName = $expectedJob
}
if ([string]::IsNullOrWhiteSpace($Database)) {
  $Database = "custoking_$Environment"
}
if ($InstanceName -cne $expectedInstance) {
  throw "InstanceName must be the environment-matched instance $expectedInstance."
}
if ($JobName -cne $expectedJob) {
  throw "JobName must be the reviewed private-VPC evidence executor $expectedJob."
}
if ($Database -cne "custoking_$Environment") {
  throw "Database must be the environment-matched application database custoking_$Environment."
}
if ($DbUser -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
  throw "DbUser must be a PostgreSQL identifier; it is used in-memory and is never written to evidence."
}
if ($Environment -eq "prod") {
  if (-not $AllowProductionEvidenceCapture) {
    throw "Production evidence capture requires -AllowProductionEvidenceCapture."
  }
  if ($ConfirmProductionInstance -cne $expectedInstance) {
    throw "Production evidence capture also requires -ConfirmProductionInstance $expectedInstance."
  }
}

$artifactsRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "artifacts"))
if ([string]::IsNullOrWhiteSpace($OutputJson)) {
  $captureTimestamp = $captureRequestedAtUtc.ToString(
    "yyyyMMdd'T'HHmmssfff'Z'",
    [System.Globalization.CultureInfo]::InvariantCulture
  )
  $OutputJson = Join-Path $artifactsRoot "cloudsql-transport-$Environment-$captureTimestamp.json"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputJson)) {
  $OutputJson = Join-Path $repoRoot $OutputJson
}
$outputPath = [System.IO.Path]::GetFullPath($OutputJson)
$artifactsPrefix = $artifactsRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
if (-not $outputPath.StartsWith($artifactsPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "OutputJson must remain below the repository artifacts directory."
}
$outputDirectory = [System.IO.Path]::GetFullPath((Split-Path -Parent $outputPath))
if (-not $outputDirectory.Equals($artifactsRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "OutputJson must be a direct child of the repository artifacts directory."
}
if (Test-Path -LiteralPath $outputPath) {
  throw "Refusing to replace existing Cloud SQL transport evidence: $outputPath"
}

$queryPath = Join-Path $PSScriptRoot "audit-cloudsql-transport-security.sql"
if (-not (Test-Path -LiteralPath $queryPath)) {
  throw "Missing reviewed transport evidence query: $queryPath"
}
$query = (Get-Content -Raw -LiteralPath $queryPath) -replace "`r`n", "`n"
$expectedQuerySha256 = "96a5dc9ace56e61257096ae24fdd8124dc7bc30a33caab00f9aa78a3c97ef8c4"
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
  $queryBytes = [System.Text.Encoding]::UTF8.GetBytes($query)
  $actualQuerySha256 = ([System.BitConverter]::ToString($sha256.ComputeHash($queryBytes))).Replace("-", "").ToLowerInvariant()
} finally {
  $sha256.Dispose()
}
if ($actualQuerySha256 -cne $expectedQuerySha256) {
  throw "Transport evidence query checksum differs from the reviewed PII-free aggregate query."
}

$gcloud = if (-not [string]::IsNullOrWhiteSpace($GcloudPath)) {
  $GcloudPath
} elseif ($env:OS -eq "Windows_NT") {
  "gcloud.cmd"
} else {
  "gcloud"
}

function Invoke-GcloudJson([string[]]$Arguments) {
  $json = @(& $gcloud @Arguments)
  if ($LASTEXITCODE -ne 0) {
    throw "gcloud failed: $($Arguments -join ' ')"
  }
  if ($json.Count -eq 0) {
    throw "gcloud returned no JSON for: $($Arguments -join ' ')"
  }
  return (($json -join "`n") | ConvertFrom-Json)
}

function Get-JobContainer($Job) {
  $container = $Job.spec.template.spec.template.spec.containers[0]
  if ($null -eq $container) {
    $container = $Job.spec.template.template.containers[0]
  }
  if ($null -eq $container) {
    throw "Existing Cloud Run job has no inspectable container contract."
  }
  return $container
}

function Get-JobVpcAccess($Job) {
  $vpcAccess = $Job.spec.template.spec.template.spec.vpcAccess
  if ($null -eq $vpcAccess) {
    $vpcAccess = $Job.spec.template.template.vpcAccess
  }
  if ($null -ne $vpcAccess) {
    return $vpcAccess
  }

  # Cloud Run v1/gcloud job descriptors expose direct-VPC configuration as
  # template annotations rather than the v2 vpcAccess object. Parse that
  # normalized shape without accepting an unnetworked executor.
  $annotations = $Job.spec.template.metadata.annotations
  if ($null -eq $annotations) {
    $annotations = $Job.spec.template.spec.template.metadata.annotations
  }
  if ($null -eq $annotations) {
    return $null
  }
  $networkInterfacesJson = [string]$annotations.'run.googleapis.com/network-interfaces'
  $egress = [string]$annotations.'run.googleapis.com/vpc-access-egress'
  if ([string]::IsNullOrWhiteSpace($networkInterfacesJson)) {
    return $null
  }
  try {
    $networkInterfaces = @($networkInterfacesJson | ConvertFrom-Json)
  } catch {
    throw "Evidence executor direct-VPC annotation is not valid JSON."
  }
  return [pscustomobject]@{
    networkInterfaces = $networkInterfaces
    connector = $null
    egress = $egress
  }
}

function Assert-ReviewedJobContract($Job) {
  $container = Get-JobContainer $Job
  $vpcAccess = Get-JobVpcAccess $Job
  $networkInterfaces = @($vpcAccess.networkInterfaces)
  $validNetworkInterfaces = @($networkInterfaces | Where-Object {
      -not [string]::IsNullOrWhiteSpace([string]$_.network) -and
      -not [string]::IsNullOrWhiteSpace([string]$_.subnetwork)
    })
  $hasNetworkInterface = $networkInterfaces.Count -gt 0 -and
    $validNetworkInterfaces.Count -eq $networkInterfaces.Count
  $hasConnector = -not [string]::IsNullOrWhiteSpace([string]$vpcAccess.connector)
  if ($null -eq $vpcAccess -or (-not $hasNetworkInterface -and -not $hasConnector)) {
    throw "Evidence executor must already have private VPC access; this helper will not create or replace networking."
  }
  $normalizedEgress = ([string]$vpcAccess.egress).ToLowerInvariant().Replace("_", "-")
  if ($normalizedEgress -cne "private-ranges-only") {
    throw "Evidence executor must retain reviewed private-ranges-only VPC egress."
  }
  if ([string]$container.image -notmatch '(?i)(?:^|/)postgres(?::|@)') {
    throw "Evidence executor must use the reviewed PostgreSQL client image."
  }
  if ([string]@($container.command)[0] -notin @("sh", "/bin/sh")) {
    throw "Evidence executor must use sh as its existing command for the argument-only execution override."
  }
  return $container
}

function Get-ExecutionId($Operation) {
  foreach ($candidate in @(
      [string]$Operation.response.name,
      [string]$Operation.metadata.target,
      [string]$Operation.metadata.name
    )) {
    if ($candidate -match '/executions/(?<id>[A-Za-z0-9-]+)$') {
      return [string]$Matches.id
    }
  }
  return ""
}

function Remove-EvidenceExecution([string]$ExecutionId) {
  if ($ExecutionId -notmatch "^$([regex]::Escape($JobName))-[a-z0-9-]+$") {
    throw "Refusing to delete an execution that is not scoped to the reviewed evidence job."
  }
  $previousPreference = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $deleteOutput = @(& $gcloud run jobs executions delete $ExecutionId `
        --project=$ProjectId `
        --region=$Region `
        --quiet 2>&1)
    $deleteExitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousPreference
  }
  if ($deleteExitCode -ne 0) {
    throw "Could not delete temporary Cloud Run execution $ExecutionId."
  }

  for ($attempt = 1; $attempt -le 6; $attempt++) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
      $describeOutput = @(& $gcloud run jobs executions describe $ExecutionId `
          --project=$ProjectId `
          --region=$Region `
          --format=json 2>&1)
      $describeExitCode = $LASTEXITCODE
    } finally {
      $ErrorActionPreference = $previousPreference
    }
    if ($describeExitCode -ne 0) {
      $describeError = $describeOutput -join "`n"
      if ($describeError -match '(?i)(NOT_FOUND|not found)') {
        return
      }
      throw "Could not verify deletion of temporary Cloud Run execution $ExecutionId."
    }
    if ($attempt -lt 6) {
      Start-Sleep -Seconds 2
    }
  }
  throw "Temporary Cloud Run execution $ExecutionId still exists after deletion."
}

$instance = Invoke-GcloudJson @(
  "sql", "instances", "describe", $InstanceName,
  "--project=$ProjectId", "--format=json"
)
$privateIp = [string](@($instance.ipAddresses | Where-Object { $_.type -eq "PRIVATE" } | Select-Object -First 1)[0].ipAddress)
if ([bool]$instance.settings.ipConfiguration.ipv4Enabled -or [string]::IsNullOrWhiteSpace($privateIp)) {
  throw "$InstanceName must be private-IP-only and expose a private address."
}

$job = Invoke-GcloudJson @(
  "run", "jobs", "describe", $JobName,
  "--project=$ProjectId", "--region=$Region", "--format=json"
)
$container = Assert-ReviewedJobContract $job
$currentSslMode = [string](@($container.env | Where-Object {
      $_.name -eq "PGSSLMODE"
    } | Select-Object -First 1)[0].value)
if ($currentSslMode.ToLowerInvariant() -ne "require") {
  Write-Host "Reconciling PGSSLMODE=require on existing Cloud Run job $JobName"
  & $gcloud run jobs update $JobName `
    --project=$ProjectId `
    --region=$Region `
    --update-env-vars=PGSSLMODE=require | Out-Null
  if ($LASTEXITCODE -ne 0) {
    throw "Could not require encrypted PostgreSQL transport on existing Cloud Run job $JobName."
  }
  $job = Invoke-GcloudJson @(
    "run", "jobs", "describe", $JobName,
    "--project=$ProjectId", "--region=$Region", "--format=json"
  )
  $container = Assert-ReviewedJobContract $job
  $currentSslMode = [string](@($container.env | Where-Object {
        $_.name -eq "PGSSLMODE"
      } | Select-Object -First 1)[0].value)
  if ($currentSslMode.ToLowerInvariant() -ne "require") {
    throw "Cloud Run job $JobName did not retain PGSSLMODE=require after reconciliation."
  }
}

$marker = "IMS_CLOUDSQL_TLS_EVIDENCE_" + (New-Guid).ToString("n")
$encodedQuery = [System.Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($query))
$shellScript = "set -euo pipefail; evidence_file=/tmp/cloudsql-transport-evidence.sql; " +
  "trap 'rm -f `"`$evidence_file`"' EXIT; printf '%s' '$encodedQuery' | base64 -d > `"`$evidence_file`"; " +
  "psql -X -q -t -A -v ON_ERROR_STOP=1 -h $privateIp -p $Port -U $DbUser -d $Database -f `"`$evidence_file`" " +
  "| sed 's/^/$marker|/'"

$accessToken = ((& $gcloud auth print-access-token) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($accessToken)) {
  throw "Could not obtain a short-lived access token for the Cloud Run execution."
}
$runUri = "https://run.googleapis.com/v2/projects/$ProjectId/locations/$Region/jobs/${JobName}:run"
$runBody = @{
  overrides = @{
    containerOverrides = @(
      @{ args = @("-c", $shellScript) }
    )
  }
} | ConvertTo-Json -Depth 8
$headers = @{ Authorization = "Bearer $accessToken" }
$operation = Invoke-RestMethod -Uri $runUri -Method Post -Headers $headers `
  -ContentType "application/json" -Body $runBody -TimeoutSec 60
if ([string]::IsNullOrWhiteSpace([string]$operation.name)) {
  throw "Cloud Run evidence execution did not return an operation name."
}

$deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
$operationUri = "https://run.googleapis.com/v2/$($operation.name)"
$executionId = Get-ExecutionId $operation
$executionCleanupConfirmed = $false
$evidence = $null
$counts = $null
try {
  do {
    Start-Sleep -Seconds 5
    $operation = Invoke-RestMethod -Uri $operationUri -Headers $headers -TimeoutSec 60
    $resolvedExecutionId = Get-ExecutionId $operation
    if (-not [string]::IsNullOrWhiteSpace($resolvedExecutionId)) {
      $executionId = $resolvedExecutionId
    }
  } while (-not $operation.done -and [datetime]::UtcNow -lt $deadline)
  if (-not $operation.done) {
    throw "Timed out waiting for Cloud Run evidence execution."
  }
  if ([string]::IsNullOrWhiteSpace($executionId)) {
    throw "Cloud Run operation did not identify its temporary execution; cleanup cannot be proven."
  }
  if ($operation.error) {
    throw "Cloud Run evidence execution failed: $($operation.error.message)"
  }

  $filter = "resource.type=cloud_run_job AND resource.labels.job_name=$JobName AND textPayload:$marker"
  $matchingLines = @()
  do {
    $logLines = @(& $gcloud logging read $filter `
        --project=$ProjectId `
        --freshness=15m `
        --limit=10 `
        --format="value(textPayload)")
    if ($LASTEXITCODE -ne 0) {
      throw "Could not read the marker-scoped aggregate evidence log."
    }
    $matchingLines = @($logLines | Where-Object { $_ -like "$marker|*" } | Select-Object -Unique)
    if ($matchingLines.Count -eq 0 -and [datetime]::UtcNow -lt $deadline) {
      Start-Sleep -Seconds 3
    }
  } while ($matchingLines.Count -eq 0 -and [datetime]::UtcNow -lt $deadline)
  if ($matchingLines.Count -ne 1) {
    throw "Expected exactly one marker-scoped aggregate result; found $($matchingLines.Count)."
  }

  $payloadText = [string]$matchingLines[0].Substring($marker.Length + 1)
  if ([string]::IsNullOrWhiteSpace($payloadText)) {
    throw "Aggregate evidence output was empty."
  }
  try {
    $payload = $payloadText | ConvertFrom-Json
  } catch {
    throw "Aggregate evidence output was not valid JSON."
  }
  $actualFields = @($payload.PSObject.Properties.Name | Sort-Object)
  $expectedFields = @("clientBackends", "encryptedBackends", "unencryptedBackends") | Sort-Object
  if (($actualFields -join ",") -cne ($expectedFields -join ",")) {
    throw "Aggregate evidence output contained missing or unexpected fields."
  }
  $counts = @{}
  foreach ($field in $expectedFields) {
    [int64]$parsed = 0
    if (-not [int64]::TryParse([string]$payload.$field, [ref]$parsed) -or $parsed -lt 0) {
      throw "Aggregate evidence field $field was not a non-negative integer."
    }
    $counts[$field] = $parsed
  }
  if ($counts.clientBackends -le 0 -or
      $counts.encryptedBackends + $counts.unencryptedBackends -ne $counts.clientBackends) {
    throw "Aggregate evidence was empty or internally inconsistent."
  }

  $evidence = [ordered]@{
    environment = $Environment
    projectId = $ProjectId
    instance = $InstanceName
    capturedAtUtc = [datetime]::UtcNow.ToString("o")
    clientBackends = [int64]$counts.clientBackends
    encryptedBackends = [int64]$counts.encryptedBackends
    unencryptedBackends = [int64]$counts.unencryptedBackends
  }
} finally {
  if (-not [string]::IsNullOrWhiteSpace($executionId)) {
    Remove-EvidenceExecution $executionId
    $executionCleanupConfirmed = $true
  }
}
if (-not $executionCleanupConfirmed -or $null -eq $evidence) {
  throw "Evidence cannot be finalized without valid aggregates and confirmed execution cleanup."
}
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$temporaryOutput = Join-Path $outputDirectory (".cloudsql-transport-" + (New-Guid).ToString("n") + ".tmp")
try {
  $evidence | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $temporaryOutput -Encoding utf8
  if (Test-Path -LiteralPath $outputPath) {
    throw "Refusing to replace Cloud SQL transport evidence created concurrently: $outputPath"
  }
  Move-Item -LiteralPath $temporaryOutput -Destination $outputPath
} finally {
  if (Test-Path -LiteralPath $temporaryOutput) {
    Remove-Item -LiteralPath $temporaryOutput -Force
  }
}

Write-Host "Cloud SQL transport evidence captured: environment=$Environment, client=$($counts.clientBackends), encrypted=$($counts.encryptedBackends), unencrypted=$($counts.unencryptedBackends)"
Write-Host "Evidence path: $outputPath"

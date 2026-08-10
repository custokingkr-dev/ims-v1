param(
  [ValidateSet("dev", "stage", "prod")]
  [string]$Environment = "prod",
  [ValidateRange(20, 10000)]
  [int]$MaxConnections = 200,
  [ValidateRange(5, 5000)]
  [int]$ReservedConnections = 40
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$targetPath = Join-Path $repoRoot "deploy/clouddeploy/targets-$Environment.yaml"
$services = @(
  "identity-service",
  "school-core-service",
  "operations-service",
  "platform-service",
  "billing-service"
)

if ($ReservedConnections -ge $MaxConnections) {
  throw "ReservedConnections must be lower than MaxConnections."
}
if (-not (Test-Path -LiteralPath $targetPath)) {
  throw "Cloud Deploy target file not found: $targetPath"
}

$targetDocuments = (Get-Content -Raw -LiteralPath $targetPath) -split '(?m)^---\s*$'
$rows = [System.Collections.Generic.List[object]]::new()
foreach ($service in $services) {
  $document = @($targetDocuments | Where-Object { $_ -match "(?m)^\s*name:\s+$([regex]::Escape($service))-$Environment\s*$" })
  if ($document.Count -ne 1) {
    throw "Expected exactly one $service-$Environment target; found $($document.Count)."
  }
  $maxMatch = [regex]::Match($document[0], '(?m)^\s*domain_max_instances:\s*"?(\d+)"?\s*$')
  if (-not $maxMatch.Success) { throw "domain_max_instances is missing for $service-$Environment." }
  $maxInstances = [int]$maxMatch.Groups[1].Value

  $manifestPath = Join-Path $repoRoot "deploy/cloudrun/$service.yaml"
  $manifest = Get-Content -Raw -LiteralPath $manifestPath
  $poolMatch = [regex]::Match($manifest, '(?ms)- name:\s*DB_POOL_MAX\s*\r?\n\s*value:\s*"?(\d+)"?')
  if ($poolMatch.Success) {
    $poolMax = [int]$poolMatch.Groups[1].Value
    $poolSource = "cloudrun manifest"
  } else {
    $applicationPath = Join-Path $repoRoot "services/$service/src/main/resources/application.yml"
    $application = Get-Content -Raw -LiteralPath $applicationPath
    $defaultMatch = [regex]::Match($application, 'maximum-pool-size:\s*\$\{DB_POOL_MAX:(\d+)\}')
    if (-not $defaultMatch.Success) { throw "Cannot resolve DB_POOL_MAX for $service." }
    $poolMax = [int]$defaultMatch.Groups[1].Value
    $poolSource = "application default"
  }
  $rows.Add([pscustomobject]@{
      service = $service
      maxInstances = $maxInstances
      poolMax = $poolMax
      maximumConnections = $maxInstances * $poolMax
      poolSource = $poolSource
  })
}

$configuredMaximum = [int](($rows | Measure-Object maximumConnections -Sum).Sum)
$availableToApplications = $MaxConnections - $ReservedConnections
$result = [ordered]@{
  environment = $Environment
  databaseMaxConnections = $MaxConnections
  reservedForMigrationsJobsAndOperators = $ReservedConnections
  applicationBudget = $availableToApplications
  configuredFleetMaximum = $configuredMaximum
  utilizationOfDatabaseLimit = [math]::Round($configuredMaximum / $MaxConnections, 3)
  withinBudget = $configuredMaximum -le $availableToApplications
  services = @($rows)
}
$result | ConvertTo-Json -Depth 6
if (-not $result.withinBudget) {
  throw "Configured Hikari fleet ceiling $configuredMaximum exceeds application budget $availableToApplications."
}

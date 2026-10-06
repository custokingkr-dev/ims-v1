param(
  [ValidateSet("dev", "stage", "prod")]
  [string]$Environment = "prod",
  [ValidateRange(20, 10000)]
  [int]$MaxConnections = 200,
  [ValidateRange(5, 5000)]
  [int]$ReservedConnections = 40,
  [ValidateRange(1, 4)]
  [int]$RevisionOverlap = 2,
  [ValidateRange(0, 1000)]
  [int]$ConcurrentStartupInstancesPerService = 0,
  [ValidateRange(0, 1000)]
  [int]$JobConnections = 10,
  [string]$RepositoryRoot = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = if ($RepositoryRoot) { Resolve-Path -LiteralPath $RepositoryRoot } else { Resolve-Path (Join-Path $PSScriptRoot "..") }
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
  # Custom schema Flyway pools can coexist briefly after sequential migrations;
  # min-idle=0 is not the same as immediate pool closure. Count every configured pool.
  $configurationNames = @{
    "school-core-service" = "SchoolCoreFlywayConfig.java"
    "operations-service" = "OperationsFlywayConfig.java"
    "platform-service" = "PlatformFlywayConfig.java"
  }
  $migrationConnectionsPerInstance = 2 # Flyway lock and migration connections for single-schema services.
  $migrationPoolCount = 1
  $migrationPoolMax = 2
  if ($configurationNames.ContainsKey($service)) {
    $configurationFiles = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "services/$service/src/main/java") -Recurse -File |
      Where-Object { $_.Name -eq $configurationNames[$service] })
    if ($configurationFiles.Count -ne 1) { throw "Expected one Flyway configuration for $service." }
    $configuration = Get-Content -Raw -LiteralPath $configurationFiles[0].FullName
    $migrationPoolCount = [regex]::Matches($configuration, '\.dataSource\(migrationDs\(').Count
    $migrationMaxMatch = [regex]::Match($configuration, 'setMaximumPoolSize\((\d+)\)')
    if ($migrationPoolCount -lt 1 -or -not $migrationMaxMatch.Success) { throw "Cannot resolve migration pool ceiling for $service." }
    $migrationPoolMax = [int]$migrationMaxMatch.Groups[1].Value
    $migrationConnectionsPerInstance = $migrationPoolCount * $migrationPoolMax
  }
  $startupInstances = if ($ConcurrentStartupInstancesPerService -gt 0) { [Math]::Min($maxInstances, $ConcurrentStartupInstancesPerService) } else { $maxInstances }
  $rows.Add([pscustomobject]@{
      service = $service
      maxInstances = $maxInstances
      poolMax = $poolMax
      maximumConnections = $maxInstances * $poolMax
      overlappingRuntimeConnections = $maxInstances * $poolMax * $RevisionOverlap
      migrationPoolCount = $migrationPoolCount
      migrationPoolMax = $migrationPoolMax
      migrationConnectionsPerInstance = $migrationConnectionsPerInstance
      concurrentStartingInstances = $startupInstances
      startupMigrationConnections = $startupInstances * $migrationConnectionsPerInstance
      poolSource = $poolSource
  })
}

$configuredMaximum = [int](($rows | Measure-Object maximumConnections -Sum).Sum)
$overlappingRuntime = [int](($rows | Measure-Object overlappingRuntimeConnections -Sum).Sum)
$startupMigrationMaximum = [int](($rows | Measure-Object startupMigrationConnections -Sum).Sum)
$configuredPeak = $overlappingRuntime + $startupMigrationMaximum + $JobConnections
$availableToApplications = $MaxConnections - $ReservedConnections
$result = [ordered]@{
  environment = $Environment
  databaseMaxConnections = $MaxConnections
  reservedForOperatorsAndOtherClients = $ReservedConnections
  applicationBudget = $availableToApplications
  configuredFleetMaximum = $configuredMaximum
  revisionOverlap = $RevisionOverlap
  overlappingRuntimeMaximum = $overlappingRuntime
  startupMigrationMaximum = $startupMigrationMaximum
  concurrentJobConnections = $JobConnections
  configuredPeakConnections = $configuredPeak
  totalIncludingReserve = $configuredPeak + $ReservedConnections
  remainingHeadroom = $MaxConnections - $configuredPeak - $ReservedConnections
  assumptions = @("Single-schema Flyway has at most two simultaneous connections per starting instance.", "Custom Flyway pools may retain all schema pools until idle housekeeping drains them.", "Instance caps are configured ceilings, not proof of live rollout/revision or Cloud SQL limits.")
  utilizationOfDatabaseLimit = [math]::Round(($configuredPeak + $ReservedConnections) / $MaxConnections, 3)
  withinBudget = $configuredPeak -le $availableToApplications
  services = @($rows)
}
$result | ConvertTo-Json -Depth 6
if (-not $result.withinBudget) {
  throw "Configured overlap/startup/job ceiling $configuredPeak exceeds nonreserved budget $availableToApplications (steady runtime: $configuredMaximum)."
}

param()
$ErrorActionPreference = "Stop"
$audit = Join-Path $PSScriptRoot "../audit-db-connection-budget.ps1"
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("ims-db-budget-" + [Guid]::NewGuid().ToString("N"))
$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
function Assert($condition, [string]$message) { if (-not $condition) { throw $message } }
function Write-Fixture([int]$maxInstances, [int]$schoolPool, [int]$otherPool) {
  $targets = @()
  foreach ($service in @("identity-service","school-core-service","operations-service","platform-service","billing-service")) {
    $targets += "apiVersion: deploy.cloud.google.com/v1`nmetadata:`n  name: $service-dev`ndeployParameters:`n  domain_max_instances: `"$maxInstances`"`n---"
    $pool = if ($service -eq "school-core-service") { $schoolPool } else { $otherPool }
    Set-Content -LiteralPath (Join-Path $fixture "deploy/cloudrun/$service.yaml") -Value "env:`n  - name: DB_POOL_MAX`n    value: `"$pool`"" -Encoding UTF8
  }
  Set-Content -LiteralPath (Join-Path $fixture "deploy/clouddeploy/targets-dev.yaml") -Value ($targets -join "`n") -Encoding UTF8
}
function Run-Audit([hashtable]$extra) {
  $lines = New-Object System.Collections.Generic.List[string]
  $failed = $false
  try { & $audit -Environment dev -RepositoryRoot $fixture @extra | ForEach-Object { $lines.Add([string]$_) } }
  catch { $failed = $true }
  return @{ failed=$failed; data=(($lines -join "`n") | ConvertFrom-Json) }
}
try {
  New-Item -ItemType Directory -Force -Path (Join-Path $fixture "deploy/cloudrun"),(Join-Path $fixture "deploy/clouddeploy") | Out-Null
  foreach ($pair in @(@("school-core-service","SchoolCoreFlywayConfig.java",5),@("operations-service","OperationsFlywayConfig.java",2),@("platform-service","PlatformFlywayConfig.java",3))) {
    $directory = Join-Path $fixture "services/$($pair[0])/src/main/java"
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $body = "setMaximumPoolSize(3);`n" + ((1..([int]$pair[2]) | ForEach-Object { '.dataSource(migrationDs(url,user,password))' }) -join "`n")
    Set-Content -LiteralPath (Join-Path $directory $pair[1]) -Value $body -Encoding UTF8
  }
  Write-Fixture 4 20 5
  $before = Run-Audit @{}
  Assert $before.failed "Old single-revision dev budget must fail once overlap and migrations are included."
  Assert ($before.data.configuredFleetMaximum -eq 160 -and $before.data.configuredPeakConnections -eq 466) "Original fleet peak must include 320 runtime + 136 migrations + 10 jobs."
  Write-Fixture 2 8 3
  $after = Run-Audit @{}
  Assert (-not $after.failed -and $after.data.withinBudget) "Reduced fleet should fit the full conservative budget."
  Assert ($after.data.configuredFleetMaximum -eq 40 -and $after.data.totalIncludingReserve -eq 198 -and $after.data.remainingHeadroom -eq 2) "Reduced fleet must include every startup pool and reserved/job connection."
  Assert (($after.data.services | Where-Object service -eq 'school-core-service').migrationConnectionsPerInstance -eq 15) "Count all five school-core pools, not only the active migration."
  $jobOverrun = Run-Audit @{JobConnections=40}
  Assert $jobOverrun.failed "Additional jobs must consume budget and fail an oversubscribed configuration."
  $serial = Run-Audit @{ConcurrentStartupInstancesPerService=1}
  Assert ($serial.data.startupMigrationMaximum -eq 34 -and $serial.data.totalIncludingReserve -eq 164) "An explicitly enforced one-startup-instance budget must be accounted separately."
  Write-Output "DB connection overlap/startup/job budget tests passed (5 scenarios)."
} finally {
  $resolved = [System.IO.Path]::GetFullPath($fixture)
  if (-not $resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or -not [System.IO.Path]::GetFileName($resolved).StartsWith('ims-db-budget-')) {
    throw "Refusing to clean an unexpected fixture path: $resolved"
  }
  if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}

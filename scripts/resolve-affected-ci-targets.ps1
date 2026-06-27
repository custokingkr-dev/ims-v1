param(
  [string]$BaseRef,
  [string]$HeadRef = "HEAD"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")
. (Join-Path $PSScriptRoot "microservice-test-catalog.ps1")

$buildByName = @{}
foreach ($entry in @(Get-MicroserviceBuildCatalog)) {
  $buildByName[$entry.Name] = $entry
}

$services = @(Get-MicroserviceTestCatalog | ForEach-Object {
  $build = $buildByName[$_.Name]
  if (-not $build) {
    throw "Missing build catalog entry for test target: $($_.Name)"
  }
  $context = $build.Context -replace "\\", "/"
  if (-not $context.StartsWith("./")) {
    $context = "./$context"
  }
  [pscustomobject]@{
    name = $_.Name
    path = ($_.Path -replace "\\", "/")
    tool = $_.Tool
    context = $context
    image = $build.Image
    build_args = if ($build.BuildArgs) { $build.BuildArgs -join "`n" } else { "" }
  }
})

if (-not $BaseRef) {
  $BaseRef = "HEAD~1"
}

$changedFiles = @(git diff --name-only $BaseRef $HeadRef | ForEach-Object { $_ -replace "\\", "/" })
$allServiceTriggers = @(
  ".github/workflows/ci.yml",
  ".github/workflows/whole-application-validation.yml",
  "docker-compose.yml",
  "cloudbuild.yaml",
  "Tiltfile"
)

$scriptTriggers = @(
  "scripts/resolve-affected-ci-targets.ps1",
  "scripts/invoke-microservice-tests.ps1",
  "scripts/microservice-test-catalog.ps1",
  "scripts/microservice-build-catalog.ps1",
  "scripts/verify-microservice-migration.ps1",
  "scripts/smoke-gateway-routes.ps1",
  "scripts/smoke-microservice-features.ps1"
)

$deployTriggers = @(
  "deploy/",
  ".github/workflows/deploy.yml",
  ".github/workflows/gcp-deploy-"
)

$allAffected = $false
foreach ($file in $changedFiles) {
  if ($allServiceTriggers -contains $file) {
    $allAffected = $true
  }
  if ($scriptTriggers -contains $file) {
    $allAffected = $true
  }
  foreach ($prefix in $deployTriggers) {
    if ($file.StartsWith($prefix)) {
      $allAffected = $true
    }
  }
}

$affected = New-Object System.Collections.Generic.List[object]
if ($allAffected) {
  foreach ($service in $services) {
    $affected.Add($service)
  }
} else {
  foreach ($service in $services) {
    foreach ($file in $changedFiles) {
      if ($file.StartsWith("$($service.path)/")) {
        $affected.Add($service)
        break
      }
    }
  }
}

$unique = @($affected | Sort-Object -Property name -Unique)
$serviceMatrix = @{ include = @($unique) }
$dockerMatrix = @{
  include = @(
    $unique | ForEach-Object {
      @{
        name = $_.name
        context = $_.context
        image = $_.image
        build_args = $_.build_args
      }
    }
  )
}

[pscustomobject]@{
  changed_files = $changedFiles
  has_service_changes = ($unique.Count -gt 0)
  service_matrix = $serviceMatrix
  docker_matrix = $dockerMatrix
} | ConvertTo-Json -Depth 10 -Compress

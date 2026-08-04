param(
  [string]$BaseRef,
  [string]$HeadRef = "HEAD",
  [ValidateSet("", "dev", "prod")]
  [string]$Environment = "",
  [switch]$ForceAll
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
if ($LASTEXITCODE -ne 0) {
  throw "Could not resolve changed files between '$BaseRef' and '$HeadRef'."
}

$allServiceTriggers = @(
  ".github/workflows/ci-pr.yml",
  ".github/workflows/build-release.yml",
  ".github/workflows/rollback.yml",
  ".github/workflows/security-scan.yml",
  "docker-compose.yml",
  "deploy/skaffold.yaml",
  "Tiltfile"
)

$scriptTriggers = @(
  "scripts/resolve-affected-ci-targets.ps1",
  "scripts/invoke-microservice-tests.ps1",
  "scripts/microservice-test-catalog.ps1",
  "scripts/microservice-build-catalog.ps1",
  "scripts/verify-microservice-migration.ps1",
  "scripts/resolve-image-source-id.ps1",
  "scripts/invoke-direct-cloudrun-release.ps1",
  "scripts/invoke-clouddeploy-release.ps1",
  "scripts/wait-clouddeploy-rollouts.ps1",
  "scripts/verify-cloudrun-release.ps1",
  "scripts/render-clouddeploy-targets.ps1",
  "scripts/smoke-gateway-routes.ps1",
  "scripts/smoke-microservice-features.ps1"
)

$globalDeployTriggers = @(
  "deploy/clouddeploy/delivery-pipelines.yaml",
  "deploy/skaffold.yaml",
  ".github/workflows/_build-image.yml",
  ".github/workflows/_detect-changes.yml",
  ".github/workflows/_smoke-environment.yml",
  ".github/workflows/_test-java-service.yml",
  ".github/workflows/_test-node-service.yml"
)

$allAffected = $ForceAll.IsPresent
$deploymentConfigChanged = $false
foreach ($file in $changedFiles) {
  if ($allServiceTriggers -contains $file) {
    $allAffected = $true
  }
  if ($scriptTriggers -contains $file) {
    $allAffected = $true
  }
  foreach ($prefix in $globalDeployTriggers) {
    if ($file.StartsWith($prefix)) {
      $allAffected = $true
    }
  }
  $targetConfigMatches = if ([string]::IsNullOrWhiteSpace($Environment)) {
    $file.StartsWith("deploy/clouddeploy/targets-")
  } else {
    $file -eq "deploy/clouddeploy/targets-$Environment.yaml"
  }
  if ($targetConfigMatches) {
    $allAffected = $true
  }
  if ($file.StartsWith("deploy/cloudrun/") -or
      $file -eq "deploy/clouddeploy/delivery-pipelines.yaml" -or
      $file -eq "deploy/skaffold.yaml" -or
      $file -eq "scripts/render-clouddeploy-targets.ps1" -or
      $targetConfigMatches) {
    $deploymentConfigChanged = $true
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
      if ($file.StartsWith("$($service.path)/") -or $file -eq "deploy/cloudrun/$($service.name).yaml") {
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
  deployment_config_changed = $deploymentConfigChanged
  service_matrix = $serviceMatrix
  docker_matrix = $dockerMatrix
} | ConvertTo-Json -Depth 10 -Compress

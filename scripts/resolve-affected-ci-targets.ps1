param(
  [string]$BaseRef,
  [string]$HeadRef = "HEAD"
)

$ErrorActionPreference = "Stop"

$services = @(
  @{ name = "identity-service"; path = "services/identity-service"; tool = "maven"; context = "./services/identity-service"; image = "custoking-identity-service"; build_args = "" },
  @{ name = "tenant-school-service"; path = "services/tenant-school-service"; tool = "maven"; context = "./services/tenant-school-service"; image = "custoking-tenant-school-service"; build_args = "" },
  @{ name = "student-service"; path = "services/student-service"; tool = "maven"; context = "./services/student-service"; image = "custoking-student-service"; build_args = "" },
  @{ name = "attendance-service"; path = "services/attendance-service"; tool = "maven"; context = "./services/attendance-service"; image = "custoking-attendance-service"; build_args = "" },
  @{ name = "fee-service"; path = "services/fee-service"; tool = "maven"; context = "./services/fee-service"; image = "custoking-fee-service"; build_args = "" },
  @{ name = "catalog-service"; path = "services/catalog-service"; tool = "maven"; context = "./services/catalog-service"; image = "custoking-catalog-service"; build_args = "" },
  @{ name = "workflow-service"; path = "services/workflow-service"; tool = "maven"; context = "./services/workflow-service"; image = "custoking-workflow-service"; build_args = "" },
  @{ name = "firefighting-service"; path = "services/firefighting-service"; tool = "maven"; context = "./services/firefighting-service"; image = "custoking-firefighting-service"; build_args = "" },
  @{ name = "reporting-service"; path = "services/reporting-service"; tool = "maven"; context = "./services/reporting-service"; image = "custoking-reporting-service"; build_args = "" },
  @{ name = "billing-service"; path = "services/billing-service"; tool = "maven"; context = "./services/billing-service"; image = "custoking-billing-service"; build_args = "" },
  @{ name = "audit-service"; path = "services/audit-service"; tool = "maven"; context = "./services/audit-service"; image = "custoking-audit-service"; build_args = "" },
  @{ name = "notification-service"; path = "services/notification-service"; tool = "maven"; context = "./services/notification-service"; image = "custoking-notification-service"; build_args = "" },
  @{ name = "api-gateway"; path = "services/api-gateway"; tool = "node"; context = "./services/api-gateway"; image = "custoking-api-gateway"; build_args = "" },
  @{ name = "frontend"; path = "frontend"; tool = "npm"; context = "./frontend"; image = "custoking-frontend"; build_args = "VITE_API_BASE_URL=/api/v1" }
) | ForEach-Object { [pscustomobject]$_ }

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

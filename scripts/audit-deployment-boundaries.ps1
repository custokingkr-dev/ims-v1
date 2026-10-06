param(
  [string]$WorkflowFile = ".github/workflows/build-release.yml",
  [string]$ComposeFile = "docker-compose.yml",
  [string]$GatewayFile = "services/api-gateway/server.js",
  [string]$CloudRunDirectory = "deploy/cloudrun"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")

function Read-RequiredFile([string]$Path) {
  $resolved = Join-Path $repoRoot $Path
  if (-not (Test-Path -LiteralPath $resolved)) {
    throw "Required file not found: $Path"
  }
  return Get-Content -Raw -Path $resolved
}

function Test-DomainServiceGlobalScale([string]$Manifest) {
  # Only service metadata before spec counts; a revision annotation is insufficient.
  $serviceMetadata=($Manifest -split '(?m)^spec:',2)[0]
  if($serviceMetadata -notmatch '(?m)^  annotations:\r?\n(?:    [^\r\n]+\r?\n)*?    run\.googleapis\.com/maxScale: "2" # from-param: \$\{domain_max_instances\}') {
    return $false
  }
  return $true
}

$workflow = Read-RequiredFile $WorkflowFile
$compose = Read-RequiredFile $ComposeFile
$gateway = Read-RequiredFile $GatewayFile
$directRelease = Read-RequiredFile "scripts/invoke-direct-cloudrun-release.ps1"
$cloudDeployRelease = Read-RequiredFile "scripts/invoke-clouddeploy-release.ps1"
$cloudDeployWaiter = Read-RequiredFile "scripts/wait-clouddeploy-rollout.ps1"
$releaseVerification = Read-RequiredFile "scripts/verify-cloudrun-release.ps1"
$violations = New-Object System.Collections.Generic.List[string]
$catalog = @(Get-MicroserviceBuildCatalog)

$activeDeploymentText = "$workflow`n$compose`n$gateway"
foreach ($manifest in Get-ChildItem (Join-Path $repoRoot $CloudRunDirectory) -Filter "*.yaml" -File) {
  $activeDeploymentText += "`n" + (Get-Content -Raw -Path $manifest.FullName)
}

foreach ($retired in @("custoking-backend", "BACKEND_UPSTREAM", "./backend", "backend:")) {
  if ($activeDeploymentText.Contains($retired)) {
    $violations.Add("Retired backend deployment reference still present: $retired")
  }
}

foreach ($service in $catalog) {
  $manifestPath = Join-Path $repoRoot "$CloudRunDirectory/$($service.Name).yaml"
  if (-not (Test-Path -LiteralPath $manifestPath)) {
    $violations.Add("Cloud Run manifest missing for $($service.Name): $manifestPath")
    continue
  }
  $manifest = Get-Content -Raw -Path $manifestPath
  if ($service.Name -in @('identity-service','school-core-service','operations-service','billing-service','platform-service') -and -not (Test-DomainServiceGlobalScale $manifest)) {
    $violations.Add("Java service $($service.Name) requires service-level maxScale2 with domain_max_instances binding.")
  }
  foreach ($required in @("custoking-$($service.Name)-dev", $service.Image)) {
    if (-not $manifest.Contains($required)) {
      $violations.Add("Cloud Run manifest for $($service.Name) is missing: $required")
    }
  }
  if (-not $compose.Contains($service.Name)) {
    $violations.Add("docker-compose.yml is missing service: $($service.Name)")
  }

  $dockerfilePath = Join-Path $repoRoot $(if ($service.Dockerfile) { $service.Dockerfile } else { "$($service.Context)/Dockerfile" })
  $dockerfile = Get-Content -Raw -Path $dockerfilePath
  foreach ($fromLine in @($dockerfile -split "`r?`n" | Where-Object { $_ -match "^FROM\s" })) {
    if ($fromLine -notmatch "@sha256:[0-9a-f]{64}") {
      $violations.Add("Docker base image is not pinned by digest for $($service.Name): $fromLine")
    }
  }
}

foreach ($required in @(
  "needs.detect.outputs.docker_matrix",
  "max-parallel: 4",
  "cache-from: type=gha",
  "cache-to: type=gha",
  "resolve-image-source-id.ps1",
  "databaseBackedServices",
  "Skipping development Cloud SQL start: no database-backed service is affected.",
  "dev-approved-",
  "invoke-direct-cloudrun-release.ps1",
  "invoke-clouddeploy-release.ps1",
  "WaitForRollout",
  "verify-cloudrun-release.ps1",
  "smoke-gateway-health.ps1",
  "group: cd-environment-",
  "cancel-in-progress:")) {
  if (-not $workflow.Contains($required)) {
    $violations.Add("Release workflow missing required deployment control: $required")
  }
}

foreach ($required in @("--update-env-vars", "OTEL_RESOURCE_ATTRIBUTES", "service.version=`$commitSha", "traceMetadataCurrent", "currentResourceAttributes", 'status = "submitted"', 'status = "already-current"')) {
  if (-not $directRelease.Contains($required)) {
    $violations.Add("Direct dev release is missing deployment metadata control: $required")
  }
}

function Test-ReleaseReadinessSource([string]$DirectSource, [string]$CloudSource) {
  # Parse executable order/call nodes; inspect the required helper and profile implementation bodies.
  $expectedOrder = 'api-gateway,identity-service,frontend,school-core-service,operations-service,billing-service,platform-service'
  foreach ($item in @(@{Name='direct';Source=$DirectSource}, @{Name='CloudDeploy';Source=$CloudSource})) {
    $parseErrors=$null
    $ast=[System.Management.Automation.Language.Parser]::ParseInput($item.Source,[ref]$null,[ref]$parseErrors)
    if($parseErrors.Count -gt 0){ "Release readiness: $($item.Name) syntax invalid";continue }
    $order=$ast.Find({param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$releaseOrder'},$true)
    $values=@($order.Right.FindAll({param($n) $n -is [System.Management.Automation.Language.StringConstantExpressionAst]},$true)|ForEach-Object {$_.Value})
    if(($values -join ',') -ne $expectedOrder){ "Release readiness: $($item.Name) must install gateway first in reviewed dependency order" }
    if($item.Name -eq 'direct') {
      $async=$ast.Find({param($n) $n -is [System.Management.Automation.Language.StringConstantExpressionAst] -and $n.Value -eq '--async'},$true)
      if($async){ 'Release readiness: asynchronous direct deployment is forbidden' }
      $helper=$ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-DirectCloudRunDeployment'},$true)
      foreach($required in @('AddMinutes(10)','Wait-Job $worker -Timeout 30','Stop-Job $worker','$result.ExitCode -ne 0','throw','Remove-Job $worker')) {
        if(-not $helper -or -not $helper.Extent.Text.Contains($required)){ "Release readiness: bounded synchronous helper missing $required" }
      }
      $call=$ast.Find({param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Invoke-DirectCloudRunDeployment'},$true)
      $preflight=$ast.Find({param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Assert-DirectReleaseProfile'},$true)
      $loop=$ast.Find({param($n) $n -is [System.Management.Automation.Language.ForEachStatementAst] -and $n.Variable.Extent.Text -eq '$service'},$true)
      if(-not $call -or -not $preflight -or -not $loop -or $preflight.Extent.StartOffset -ge $loop.Extent.StartOffset){ 'Release readiness: all profiles must fail fast before the deployment loop' }
      $profile=$ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Assert-DirectReleaseProfile'},$true)
      foreach($required in @('APP_MIGRATIONS_ENABLED','FLYWAY_','SPRING_FLYWAY_','SPRING_DATASOURCE_USERNAME','RUNTIME_DB_ROLE','secretKeyRef','DB_POOL_MAX','8080','latestRevision','latestReadyRevisionName')) {
        if(-not $profile -or -not $profile.Extent.Text.Contains($required)){ "Release readiness: direct security profile missing $required" }
      }
    } elseif(-not $item.Source.Contains('if (-not $WaitForRollout -and') -or -not $item.Source.Contains('require -WaitForRollout')) {
      'Release readiness: CloudDeploy carrier/backend releases must reject missing serialized rollout wait'
    }
  }
}
foreach($readinessViolation in @(Test-ReleaseReadinessSource $directRelease $cloudDeployRelease)) { $violations.Add($readinessViolation) }

foreach ($required in @("WaitForRollout", "Write-DeploymentEvidence", "wait-clouddeploy-rollout.ps1", "SourceStagingDir", "--gcs-source-staging-dir", "automatic bucket discovery is not allowed")) {
  if (-not $cloudDeployRelease.Contains($required)) {
    $violations.Add("Cloud Deploy release orchestration is missing serialized rollout control: $required")
  }
}

foreach ($required in @('$state -eq "IN_PROGRESS"', 'PENDING_RELEASE')) {
  if (-not $cloudDeployWaiter.Contains($required)) {
    $violations.Add("Cloud Deploy rollout waiter is missing pending-release protection: $required")
  }
}

foreach ($required in @("runtimeRef", "TimeoutMinutes", "latestTraffic", "update-traffic", "--to-latest")) {
  if (-not $releaseVerification.Contains($required)) {
    $violations.Add("Cloud Run release verification is missing bounded runtime-digest validation: $required")
  }
}

foreach ($required in @(
  "IDENTITY_UPSTREAM",
  "TENANT_SCHOOL_UPSTREAM",
  "STUDENT_UPSTREAM",
  "ATTENDANCE_UPSTREAM",
  "FEE_UPSTREAM",
  "CATALOG_UPSTREAM",
  "WORKFLOW_UPSTREAM",
  "FIREFIGHTING_UPSTREAM",
  "REPORTING_UPSTREAM",
  "BILLING_UPSTREAM",
  "AUDIT_UPSTREAM",
  "NOTIFICATION_UPSTREAM",
  "IDENTITY_SERVICE_TOKEN",
  "X-Request-ID",
  "traceparent")) {
  if (-not $gateway.Contains($required)) {
    $violations.Add("API gateway missing routing/security value: $required")
  }
}

$frontendDockerfile = Get-Content -Raw -Path (Join-Path $repoRoot "frontend/Dockerfile")
$npmInstallIndex = $frontendDockerfile.IndexOf("RUN npm ci")
$sourceCopyIndex = $frontendDockerfile.IndexOf("COPY src ./src")
if ($npmInstallIndex -lt 0 -or $sourceCopyIndex -lt 0 -or $npmInstallIndex -gt $sourceCopyIndex) {
  $violations.Add("Frontend Dockerfile must install dependencies before copying application source.")
}

if ($violations.Count -gt 0) {
  Write-Host "Deployment boundary violations found:"
  $violations | ForEach-Object { Write-Host "  $_" }
  exit 1
}

Write-Host "Deployment boundary audit passed: affected-service promotion, pinned images, Cloud Run manifests, and gateway routes are guarded."

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")
. (Join-Path $PSScriptRoot "microservice-test-catalog.ps1")

$catalog = @(Get-MicroserviceBuildCatalog)
$testCatalog = @(Get-MicroserviceTestCatalog)
$ciPath = Join-Path $repoRoot ".github/workflows/ci-pr.yml"
$deployPath = Join-Path $repoRoot ".github/workflows/build-release.yml"
$resolverPath = Join-Path $repoRoot "scripts/resolve-affected-ci-targets.ps1"
$verifyPath = Join-Path $repoRoot "scripts/verify-microservice-migration.ps1"

$ci = Get-Content -Raw -Path $ciPath
$deploy = Get-Content -Raw -Path $deployPath
$resolver = Get-Content -Raw -Path $resolverPath
$verify = Get-Content -Raw -Path $verifyPath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @("_detect-changes.yml", "service_matrix", "docker_matrix", "fromJSON(needs.detect.outputs.service_matrix)", "fromJSON(needs.detect.outputs.docker_matrix)", "matrix.context", "matrix.image")) {
    if ($ci -notmatch [regex]::Escape($required)) {
        $violations.Add("CI workflow missing dynamic catalog contract: $required")
    }
}

foreach ($required in @("Get-MicroserviceBuildCatalog", "Get-MicroserviceTestCatalog", "microservice-build-catalog.ps1", "microservice-test-catalog.ps1")) {
    if ($resolver -notmatch [regex]::Escape($required)) {
        $violations.Add("Affected-target resolver must use shared catalogs: $required")
    }
}

foreach ($service in $catalog) {
    $context = $service.Context
    $contextPath = Join-Path $repoRoot $context
    if (-not (Test-Path -LiteralPath $contextPath)) {
        $violations.Add("Build catalog context does not exist for $($service.Name): $context")
    } elseif (-not (Test-Path -LiteralPath (Join-Path $contextPath "Dockerfile"))) {
        $violations.Add("Build catalog context has no Dockerfile for $($service.Name): $context")
    }

    if ($verify -notmatch [regex]::Escape("Get-MicroserviceBuildCatalog")) {
        $violations.Add("verify-microservice-migration.ps1 must use the shared microservice build catalog.")
        break
    }
}

foreach ($required in @("needs.detect.outputs.docker_matrix", "cache-from: type=gha", "cache-to: type=gha", "resolve-image-source-id.ps1", "matrix.context", "matrix.image")) {
    if ($deploy -notmatch [regex]::Escape($required)) {
        $violations.Add("Deployment workflow missing affected-image build contract: $required")
    }
}

if ($ci -notmatch "fail-fast:\s+false") {
    $violations.Add("CI docker-build matrix should keep fail-fast disabled so all service image failures are visible.")
}

if ($violations.Count -gt 0) {
    Write-Host "Microservice build catalog violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Microservice build catalog audit passed: PR CI, branch CD, and local verification use the shared catalog."

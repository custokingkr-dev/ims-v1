$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")
. (Join-Path $PSScriptRoot "microservice-test-catalog.ps1")

$catalog = @(Get-MicroserviceBuildCatalog)
$testCatalog = @(Get-MicroserviceTestCatalog)
$ciPath = Join-Path $repoRoot ".github/workflows/ci.yml"
$resolverPath = Join-Path $repoRoot "scripts/resolve-affected-ci-targets.ps1"
$cloudBuildPath = Join-Path $repoRoot "cloudbuild.yaml"
$verifyPath = Join-Path $repoRoot "scripts/verify-microservice-migration.ps1"

$ci = Get-Content -Raw -Path $ciPath
$resolver = Get-Content -Raw -Path $resolverPath
$cloudBuild = Get-Content -Raw -Path $cloudBuildPath
$verify = Get-Content -Raw -Path $verifyPath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @("resolve-affected-ci-targets.ps1", "service_matrix", "docker_matrix", "fromJson(needs.detect-changes.outputs.service_matrix)", "fromJson(needs.detect-changes.outputs.docker_matrix)", "matrix.context", "matrix.image")) {
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
    $posixContext = $context -replace "\\", "/"
    $image = $service.Image

    if ($cloudBuild -notmatch [regex]::Escape($posixContext) -and
        $cloudBuild -notmatch [regex]::Escape("./$posixContext")) {
        $violations.Add("Cloud Build missing build context for $($service.Name): $posixContext")
    }

    if ($cloudBuild -notmatch [regex]::Escape($image)) {
        $violations.Add("Cloud Build missing image name for $($service.Name): $image")
    }

    if ($verify -notmatch [regex]::Escape("Get-MicroserviceBuildCatalog")) {
        $violations.Add("verify-microservice-migration.ps1 must use the shared microservice build catalog.")
        break
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

Write-Host "Microservice build catalog audit passed: CI, Cloud Build, and local verification include all catalogued services."

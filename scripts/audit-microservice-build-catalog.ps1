$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")
. (Join-Path $PSScriptRoot "microservice-test-catalog.ps1")

$catalog = @(Get-MicroserviceBuildCatalog)
$testCatalog = @(Get-MicroserviceTestCatalog)
$ciPath = Join-Path $repoRoot ".github/workflows/ci.yml"
$cloudBuildPath = Join-Path $repoRoot "cloudbuild.yaml"
$verifyPath = Join-Path $repoRoot "scripts/verify-microservice-migration.ps1"

$ci = Get-Content -Raw -Path $ciPath
$cloudBuild = Get-Content -Raw -Path $cloudBuildPath
$verify = Get-Content -Raw -Path $verifyPath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($service in $catalog) {
    $context = $service.Context
    $posixContext = $context -replace "\\", "/"
    $ciContext = "./" + $posixContext
    $image = $service.Image

    if ($ci -notmatch [regex]::Escape($ciContext)) {
        $violations.Add("CI docker-build matrix missing context for $($service.Name): $ciContext")
    }

    if ($ci -notmatch [regex]::Escape($image)) {
        $violations.Add("CI docker-build matrix missing image for $($service.Name): $image")
    }

    if ($cloudBuild -notmatch [regex]::Escape($posixContext) -and
        $cloudBuild -notmatch [regex]::Escape($ciContext)) {
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

foreach ($service in $testCatalog) {
    if ($ci -notmatch [regex]::Escape($service.Name)) {
        $violations.Add("CI service-test matrix missing service name: $($service.Name)")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Microservice build catalog violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Microservice build catalog audit passed: CI, Cloud Build, and local verification include all catalogued services."

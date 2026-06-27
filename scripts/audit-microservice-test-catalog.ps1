$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-test-catalog.ps1")

$catalog = @(Get-MicroserviceTestCatalog)
$ciPath = Join-Path $repoRoot ".github/workflows/ci.yml"
$resolverPath = Join-Path $repoRoot "scripts/resolve-affected-ci-targets.ps1"
$runnerPath = Join-Path $repoRoot "scripts/invoke-microservice-tests.ps1"
$ci = Get-Content -Raw -Path $ciPath
$resolver = Get-Content -Raw -Path $resolverPath
$runner = Get-Content -Raw -Path $runnerPath
$violations = New-Object System.Collections.Generic.List[string]

foreach ($service in $catalog) {
    $path = $service.Path -replace "\\", "/"
    if (-not (Test-Path (Join-Path $repoRoot $service.Path))) {
        $violations.Add("Test catalog path does not exist for $($service.Name): $($service.Path)")
    }

    if ($service.Tool -eq "maven" -and -not (Test-Path (Join-Path $repoRoot (Join-Path $service.Path "pom.xml")))) {
        $violations.Add("Maven test catalog entry missing pom.xml for $($service.Name): $($service.Path)")
    }

    if ($service.Tool -eq "npm" -and -not (Test-Path (Join-Path $repoRoot (Join-Path $service.Path "package.json")))) {
        $violations.Add("NPM test catalog entry missing package.json for $($service.Name): $($service.Path)")
    }

    if ($service.Tool -eq "node" -and -not ($service.Command -contains "--test")) {
        $violations.Add("Node test catalog entry must run node --test for $($service.Name): $($service.Path)")
    }

}

if ($runner -notmatch [regex]::Escape("Get-MicroserviceTestCatalog")) {
    $violations.Add("invoke-microservice-tests.ps1 must use the shared microservice test catalog.")
}

foreach ($required in @("Get-MicroserviceTestCatalog", "Get-MicroserviceBuildCatalog", "service_matrix", "tool", "path")) {
    if ($resolver -notmatch [regex]::Escape($required)) {
        $violations.Add("resolve-affected-ci-targets.ps1 must derive service-test matrix from shared catalog: $required")
    }
}

foreach ($required in @("fromJson(needs.detect-changes.outputs.service_matrix)", "matrix.tool", "matrix.path", "matrix.name")) {
    if ($ci -notmatch [regex]::Escape($required)) {
        $violations.Add("CI service-test job missing dynamic matrix contract: $required")
    }
}

if ($ci -notmatch "service-test:") {
    $violations.Add("CI missing service-test job.")
}

if ($violations.Count -gt 0) {
    Write-Host "Microservice test catalog violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Microservice test catalog audit passed: CI and local test runner include all catalogued services."

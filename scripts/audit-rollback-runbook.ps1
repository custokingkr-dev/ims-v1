param(
    [string]$RunbookPath = "docs/MICROSERVICE-ROLLBACK-RUNBOOK.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")

$resolvedRunbook = Join-Path $repoRoot $RunbookPath
if (-not (Test-Path $resolvedRunbook)) {
    throw "Rollback runbook not found: $RunbookPath"
}

$runbook = Get-Content -Raw -Path $resolvedRunbook
$catalog = @(Get-MicroserviceBuildCatalog)
$violations = New-Object System.Collections.Generic.List[string]

foreach ($service in $catalog) {
    foreach ($required in @($service.Name, $service.Image)) {
        if (-not $runbook.Contains($required)) {
            $violations.Add("Rollback runbook missing catalog value for $($service.Name): $required")
        }
    }
}

foreach ($required in @(
    "gcloud run revisions list",
    "gcloud run services describe",
    "gcloud run services update-traffic",
    "scripts\smoke-deployment-readiness.ps1",
    "/actuator/health",
    "/gateway-health",
    "forward-only repair migration",
    "Do not delete notification inbox rows")) {
    if (-not $runbook.Contains($required)) {
        $violations.Add("Rollback runbook missing required operator instruction: $required")
    }
}

if ($runbook -notmatch "Never use destructive schema rollback") {
    $violations.Add("Rollback runbook must explicitly prohibit destructive schema rollback.")
}

if ($violations.Count -gt 0) {
    Write-Host "Rollback runbook violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Rollback runbook audit passed: all catalogued services and required rollback steps are documented."

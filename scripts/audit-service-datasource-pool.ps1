$ErrorActionPreference = "Stop"

# Ensures every Spring service declares explicit HikariCP connection-pool bounds
# so pool sizing is consistent and tunable per environment instead of relying on
# implicit framework defaults that can exhaust Cloud SQL connections across the fleet.

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$servicesRoot = Join-Path $repoRoot "services"

$requiredKeys = @(
    "maximum-pool-size",
    "minimum-idle",
    "connection-timeout",
    "validation-timeout"
)

$violations = New-Object System.Collections.Generic.List[string]
$checked = 0

foreach ($pom in Get-ChildItem -Path $servicesRoot -Recurse -Filter "pom.xml" -File) {
    $serviceDir = $pom.Directory.FullName
    $appYml = Join-Path $serviceDir "src/main/resources/application.yml"
    if (-not (Test-Path $appYml)) {
        continue
    }

    $relativePath = $appYml.Substring($repoRoot.Path.Length + 1).Replace("\", "/")
    $source = Get-Content -Raw -Path $appYml

    # Only services that own a datasource are required to bound a pool.
    if ($source -notmatch '(?m)^\s*datasource:') {
        continue
    }

    $checked++
    foreach ($key in $requiredKeys) {
        if ($source -notmatch [regex]::Escape($key)) {
            $violations.Add("Missing HikariCP '$key' in $relativePath")
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Datasource connection-pool audit violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Datasource connection-pool audit passed: $checked services declare explicit HikariCP pool bounds."

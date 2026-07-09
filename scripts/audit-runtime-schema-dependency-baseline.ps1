param(
    [string]$ServicesRoot = "services",
    [string]$BaselinePath = "docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$servicesRootPath = Resolve-Path (Join-Path $repoRoot $ServicesRoot)
$baselineFile = Resolve-Path (Join-Path $repoRoot $BaselinePath)
$baseline = Get-Content -Raw -Path $baselineFile | ConvertFrom-Json
$violations = New-Object System.Collections.Generic.List[string]

function Convert-ToStringArray {
    param($Value)

    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [System.Array]) {
        return @($Value | ForEach-Object { [string]$_ })
    }
    return @([string]$Value)
}

function Get-SqlSchemaReferencePatterns {
    param([string]$Schema)

    $escapedSchema = [regex]::Escape($Schema)
    $patterns = New-Object System.Collections.Generic.List[string]
    [void]$patterns.Add('\bFROM\s+' + $escapedSchema + '\.')
    [void]$patterns.Add('\bJOIN\s+' + $escapedSchema + '\.')
    [void]$patterns.Add('\bUPDATE\s+' + $escapedSchema + '\.')
    [void]$patterns.Add('\bINTO\s+' + $escapedSchema + '\.')
    [void]$patterns.Add('\bDELETE\s+FROM\s+' + $escapedSchema + '\.')
    [void]$patterns.Add('\bTRUNCATE\s+(?:TABLE\s+)?' + $escapedSchema + '\.')
    [void]$patterns.Add('\bREFERENCES\s+' + $escapedSchema + '\.')
    [void]$patterns.Add('\b(?:nextval|currval|setval)\s*\(\s*[''"]' + $escapedSchema + '\.')
    return $patterns.ToArray()
}

$ownedSchemas = @{}
foreach ($property in $baseline.ownedSchemas.PSObject.Properties) {
    $ownedSchemas[$property.Name] = @(Convert-ToStringArray $property.Value)
}

$schemaNames = @($ownedSchemas.Values | ForEach-Object { $_ } | Sort-Object -Unique)
$detected = @{}

foreach ($service in Get-ChildItem -Path $servicesRootPath -Directory) {
    if (-not $ownedSchemas.ContainsKey($service.Name)) {
        continue
    }

    $javaRoot = Join-Path $service.FullName "src/main/java"
    if (-not (Test-Path $javaRoot)) {
        continue
    }

    $serviceSchemas = New-Object System.Collections.Generic.HashSet[string]
    foreach ($file in Get-ChildItem -Path $javaRoot -Recurse -Filter "*.java") {
        $source = Get-Content -Raw -Path $file.FullName
        foreach ($schema in $schemaNames) {
            foreach ($pattern in (Get-SqlSchemaReferencePatterns $schema)) {
                if ($source -match $pattern) {
                    [void]$serviceSchemas.Add($schema)
                    break
                }
            }
        }
    }

    $serviceOwnedSchemas = @($ownedSchemas[$service.Name])
    $detected[$service.Name] = @($serviceSchemas | Where-Object { $_ -notin $serviceOwnedSchemas } | Sort-Object)
}

foreach ($serviceName in ($ownedSchemas.Keys | Sort-Object)) {
    $allowedProperty = $baseline.allowedExternalSchemas.PSObject.Properties[$serviceName]
    $allowed = @()
    if ($allowedProperty) {
        $allowed = @($allowedProperty.Value | Sort-Object)
    }
    $actual = @()
    if ($detected.ContainsKey($serviceName)) {
        $actual = @($detected[$serviceName] | Sort-Object)
    }

    $unexpected = @($actual | Where-Object { $_ -notin $allowed })
    foreach ($schema in $unexpected) {
        $violations.Add("Unexpected runtime schema dependency: $serviceName -> $schema")
    }

    $stale = @($allowed | Where-Object { $_ -notin $actual })
    foreach ($schema in $stale) {
        $violations.Add("Stale allowlist entry, dependency no longer detected: $serviceName -> $schema")
    }
}

foreach ($property in $baseline.allowedExternalSchemas.PSObject.Properties) {
    if (-not $ownedSchemas.ContainsKey($property.Name)) {
        $violations.Add("Allowlist references unknown service: $($property.Name)")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Runtime schema dependency baseline violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Runtime schema dependency baseline audit passed: no new cross-service schema dependencies detected."

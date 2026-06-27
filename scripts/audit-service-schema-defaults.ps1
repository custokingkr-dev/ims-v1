$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$servicesRoot = Join-Path $repoRoot "services"
$schemaBaselinePath = Join-Path $repoRoot "docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json"
$schemaBaseline = Get-Content -Raw -Path $schemaBaselinePath | ConvertFrom-Json
$ownedSchemas = @{}
foreach ($property in $schemaBaseline.ownedSchemas.PSObject.Properties) {
    $ownedSchemas[$property.Name] = [string]$property.Value
}
$violations = New-Object System.Collections.Generic.List[string]

foreach ($file in Get-ChildItem -Path $servicesRoot -Recurse -Include "application.yml", "*.java", "*.sql" -File) {
    $relativePath = $file.FullName.Substring($repoRoot.Path.Length + 1).Replace("\", "/")
    if ($relativePath -match '/target/') {
        continue
    }

    $source = Get-Content -Raw -Path $file.FullName

    if ($source -match '\$\{[A-Z0-9_]*DB_SCHEMA:public\}') {
        $violations.Add("DB schema placeholder must not default to public: $relativePath")
    }

    if ($file.Extension -eq ".java" -and $source -match '\.db\.schema:public') {
        $violations.Add("Injected service schema must not default to public: $relativePath")
    }

    if ($file.Extension -eq ".sql" -and $source -match '\bREFERENCES\s+public\.') {
        $violations.Add("Service migrations must not create hard foreign keys to public schema tables: $relativePath")
    }

    if ($file.Extension -eq ".sql") {
        $serviceName = $relativePath.Split("/")[1]
        if ($ownedSchemas.ContainsKey($serviceName)) {
            $ownedSchema = $ownedSchemas[$serviceName]
            foreach ($match in [regex]::Matches($source, '\bREFERENCES\s+([a-z][a-z0-9_]*)\.')) {
                $referencedSchema = $match.Groups[1].Value
                if ($referencedSchema -ne $ownedSchema) {
                    $violations.Add("Service migrations must not create hard cross-service foreign keys: $relativePath references $referencedSchema")
                }
            }
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Service schema default violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Service schema default audit passed: DB schema defaults do not fall back to public."

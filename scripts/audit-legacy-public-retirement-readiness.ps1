param(
    [string]$OutputSql = ""
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "legacy-compatibility-map.ps1")

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$requiredScripts = @(
    "scripts\audit-legacy-compatibility-state.ps1",
    "scripts\audit-legacy-compatibility-cloudsql.ps1",
    "scripts\generate-legacy-public-retirement-sql.ps1",
    "scripts\generate-legacy-public-retirement-compact-sql.ps1",
    "scripts\new-legacy-retirement-evidence.ps1"
)

$violations = New-Object System.Collections.Generic.List[string]
foreach ($path in $requiredScripts) {
    if (-not (Test-Path (Join-Path $repoRoot $path))) {
        $violations.Add("Required legacy retirement file is missing: $path")
    }
}

$mappings = @(Get-LegacyCompatibilityMappings)
if ($mappings.Count -lt 45) {
    $violations.Add("Legacy compatibility map is unexpectedly small: $($mappings.Count) mapped table(s).")
}

$duplicateMappings = @($mappings | Group-Object { $_.PublicTable } | Where-Object { $_.Count -gt 1 })
foreach ($duplicate in $duplicateMappings) {
    $violations.Add("Legacy compatibility map has duplicate public table mapping: $($duplicate.Name)")
}

$requiredDomains = @(
    "identity",
    "tenant-school",
    "student",
    "attendance",
    "fee",
    "catalog",
    "workflow",
    "firefighting",
    "reporting",
    "notification",
    "billing",
    "audit"
)
foreach ($domain in $requiredDomains) {
    if (-not ($mappings | Where-Object { $_.Domain -eq $domain } | Select-Object -First 1)) {
        $violations.Add("Legacy compatibility map missing domain: $domain")
    }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ims-legacy-retirement-audit-{0}" -f ([Guid]::NewGuid().ToString("N")))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    $auditJson = Join-Path $tempRoot "legacy-compatibility-audit.json"
    $generatedSql = if ($OutputSql) { $OutputSql } else { Join-Path $tempRoot "legacy-public-retirement.sql" }
    [ordered]@{
        summary = [ordered]@{
            totalMappedTables = $mappings.Count
            needsBackfillReview = 0
            publicTablesWithRows = 0
        }
        rows = @()
    } | ConvertTo-Json -Depth 5 | Set-Content -Path $auditJson -Encoding UTF8

    $global:LASTEXITCODE = 0
    & (Join-Path $PSScriptRoot "generate-legacy-public-retirement-sql.ps1") `
        -CompatibilityAuditJson $auditJson `
        -OutputSql $generatedSql | Out-Null
    if ($LASTEXITCODE -ne 0) {
        $violations.Add("Legacy public retirement SQL generator failed.")
    }

    $sql = Get-Content -Raw -Path $generatedSql
    foreach ($required in @("CREATE SCHEMA IF NOT EXISTS", "legacy_public_archive", "DROP TABLE", "RESTRICT")) {
        if (-not $sql.Contains($required)) {
            $violations.Add("Generated retirement SQL missing expected marker: $required")
        }
    }
}
finally {
    if (-not $OutputSql -and (Test-Path $tempRoot)) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Legacy public retirement readiness violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Legacy public retirement readiness audit passed: mapping and retirement SQL generation are usable."

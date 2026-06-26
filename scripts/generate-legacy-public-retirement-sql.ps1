param(
    [string]$OutputSql = "legacy-public-retirement.sql",
    [string]$CompatibilityAuditJson,
    [string]$ArchiveSchema = "legacy_public_archive",
    [string[]]$IncludeDomains,
    [string[]]$ExcludeDomains,
    [switch]$IncludeDropStatements,
    [switch]$OmitTransaction,
    [switch]$AllowBackfillIssues
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "legacy-compatibility-map.ps1")

function ConvertTo-SqlIdentifier {
    param([string]$Value)
    return '"' + ($Value -replace '"', '""') + '"'
}

function ConvertTo-SqlLiteral {
    param([string]$Value)
    return "'" + ($Value -replace "'", "''") + "'"
}

function ConvertTo-ArchiveTableName {
    param(
        [string]$Domain,
        [string]$PublicTable,
        [string]$SnapshotSuffix
    )

    $safeDomain = $Domain -replace "[^a-zA-Z0-9_]", "_"
    $safeTable = $PublicTable -replace "[^a-zA-Z0-9_]", "_"
    return "$($safeDomain)_$($safeTable)_$SnapshotSuffix"
}

$mappings = @(Get-LegacyCompatibilityMappings)

if ($IncludeDomains -and $IncludeDomains.Count -gt 0) {
    $domainSet = @{}
    foreach ($domain in $IncludeDomains) {
        $domainSet[$domain] = $true
    }
    $mappings = @($mappings | Where-Object { $domainSet.ContainsKey($_.Domain) })
}

if ($ExcludeDomains -and $ExcludeDomains.Count -gt 0) {
    $domainSet = @{}
    foreach ($domain in $ExcludeDomains) {
        $domainSet[$domain] = $true
    }
    $mappings = @($mappings | Where-Object { -not $domainSet.ContainsKey($_.Domain) })
}

if ($mappings.Count -eq 0) {
    throw "No legacy public table mappings selected."
}

if ($CompatibilityAuditJson) {
    $audit = Get-Content -Raw -Path $CompatibilityAuditJson | ConvertFrom-Json
    if (-not $audit.summary) {
        throw "Compatibility audit JSON must include a summary block. Re-run audit-legacy-compatibility-state.ps1 without -RowsOnlyJson."
    }
    $issueCount = [int]$audit.summary.needsBackfillReview
    if ($issueCount -gt 0 -and -not $AllowBackfillIssues) {
        throw "Compatibility audit has $issueCount backfill/schema issue(s). Re-run after backfill or pass -AllowBackfillIssues to generate review SQL anyway."
    }
}

$snapshotSuffix = (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmss")
$archiveSchemaIdentifier = ConvertTo-SqlIdentifier $ArchiveSchema
$lines = New-Object System.Collections.Generic.List[string]

$lines.Add("-- Generated legacy public table retirement plan.")
$lines.Add("-- Review before execution. Run only after audit-legacy-compatibility-state.ps1 -FailOnNeedsBackfill passes for the target database.")
$lines.Add("-- Archive tables are created as snapshots in $ArchiveSchema.")
$lines.Add("")
if (-not $OmitTransaction) {
    $lines.Add("BEGIN;")
    $lines.Add("")
}
$lines.Add("CREATE SCHEMA IF NOT EXISTS $archiveSchemaIdentifier;")
$lines.Add("")

foreach ($mapping in ($mappings | Sort-Object Domain, PublicTable)) {
    $publicTableIdentifier = (ConvertTo-SqlIdentifier "public") + "." + (ConvertTo-SqlIdentifier $mapping.PublicTable)
    $archiveTableName = ConvertTo-ArchiveTableName -Domain $mapping.Domain -PublicTable $mapping.PublicTable -SnapshotSuffix $snapshotSuffix
    $archiveTableIdentifier = $archiveSchemaIdentifier + "." + (ConvertTo-SqlIdentifier $archiveTableName)
    $publicRegclass = ConvertTo-SqlLiteral ("public." + $mapping.PublicTable)

    $lines.Add("-- $($mapping.Domain): public.$($mapping.PublicTable) -> $($mapping.TargetSchema).$($mapping.TargetTable)")
    $lines.Add("DO `$`$")
    $lines.Add("BEGIN")
    $lines.Add("    IF to_regclass($publicRegclass) IS NOT NULL THEN")
    $lines.Add("        EXECUTE 'CREATE TABLE $archiveTableIdentifier AS TABLE $publicTableIdentifier WITH DATA';")
    if ($IncludeDropStatements) {
        $lines.Add("        EXECUTE 'DROP TABLE $publicTableIdentifier RESTRICT';")
    } else {
        $lines.Add("        -- DROP TABLE $publicTableIdentifier RESTRICT;")
    }
    $lines.Add("    END IF;")
    $lines.Add("END")
    $lines.Add("`$`$;")
    $lines.Add("")
}

if (-not $OmitTransaction) {
    $lines.Add("COMMIT;")
    $lines.Add("")
}
$lines.Add("-- After execution, run scripts/audit-microservice-db-boundaries.ps1 and scripts/audit-legacy-compatibility-state.ps1 again.")

Set-Content -Path $OutputSql -Value $lines -Encoding UTF8
Write-Host "Generated legacy public retirement SQL: $OutputSql"
Write-Host "Mapped tables included: $($mappings.Count)"
if ($IncludeDropStatements) {
    Write-Host "DROP statements are active and use RESTRICT."
} else {
    Write-Host "DROP statements are commented. Re-run with -IncludeDropStatements only for an approved destructive cleanup window."
}
if ($OmitTransaction) {
    Write-Host "Transaction wrapper omitted."
}

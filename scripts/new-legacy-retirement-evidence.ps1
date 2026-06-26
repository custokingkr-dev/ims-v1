param(
    [string]$CompatibilityAuditJson = "legacy-compatibility-audit.json",
    [string]$RetirementSql = "legacy-public-retirement.sql",
    [string]$OutputJson = "legacy-retirement-evidence.json"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $repoRoot $Path
}

$auditPath = Resolve-RepoPath $CompatibilityAuditJson
$sqlPath = Resolve-RepoPath $RetirementSql

if (-not (Test-Path $auditPath)) {
    throw "Compatibility audit JSON not found: $CompatibilityAuditJson"
}
if (-not (Test-Path $sqlPath)) {
    throw "Legacy retirement SQL not found: $RetirementSql"
}

$audit = Get-Content -Raw -Path $auditPath | ConvertFrom-Json
if (-not $audit.summary) {
    throw "Compatibility audit JSON must contain a summary block."
}

$sql = Get-Content -Raw -Path $sqlPath
$sqlHash = Get-FileHash -Algorithm SHA256 -Path $sqlPath
$activeDropStatements = @([regex]::Matches($sql, "(?im)^\s*DROP\s+TABLE\s+")).Count
$commentedDropStatements = @([regex]::Matches($sql, "(?im)^\s*--\s*DROP\s+TABLE\s+")).Count
$archiveStatements = @([regex]::Matches($sql, "(?im)CREATE\s+TABLE\s+")).Count

[ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    compatibilityAuditJson = $CompatibilityAuditJson
    retirementSql = $RetirementSql
    retirementSqlSha256 = $sqlHash.Hash.ToLowerInvariant()
    totalMappedTables = [int]$audit.summary.totalMappedTables
    needsBackfillReview = [int]$audit.summary.needsBackfillReview
    publicTablesWithRows = [int]$audit.summary.publicTablesWithRows
    archiveStatements = $archiveStatements
    commentedDropStatements = $commentedDropStatements
    activeDropStatements = $activeDropStatements
    destructiveDropsActive = ($activeDropStatements -gt 0)
} | ConvertTo-Json -Depth 5 | Set-Content -Path $OutputJson -Encoding UTF8

Write-Host "Created legacy retirement evidence: $OutputJson"
Write-Host "Mapped tables: $($audit.summary.totalMappedTables)"
Write-Host "Backfill issues: $($audit.summary.needsBackfillReview)"
Write-Host "Active DROP statements: $activeDropStatements"

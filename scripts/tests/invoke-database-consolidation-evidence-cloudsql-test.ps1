$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "../..")
$runnerPath = Join-Path $root "scripts/invoke-database-consolidation-evidence-cloudsql.ps1"
$sqlPath = Join-Path $root "scripts/database-consolidation-evidence.sql"

$runner = Get-Content -LiteralPath $runnerPath -Raw
$sql = Get-Content -LiteralPath $sqlPath -Raw

# Parse the runner before checking the safety contract.
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile(
    $runnerPath,
    [ref]$tokens,
    [ref]$parseErrors
)
if (@($parseErrors).Count -gt 0) {
    throw "Runner has PowerShell parse errors: $($parseErrors -join '; ')"
}

$requiredRunnerFragments = @(
    '[string]$HostAddress = "10.92.0.3"',
    '[string]$Database = "custoking_prod"',
    '[string]$PasswordSecret = "db-password-prod"',
    'BEGIN READ ONLY;',
    'psql -X -q -v ON_ERROR_STOP=1',
    "export PGOPTIONS='-c default_transaction_read_only=on'",
    'name = "PGSSLMODE"; value = "require"',
    'secretKeyRef = [ordered]@{ name = $PasswordSecret; key = "latest" }',
    'serviceAccountName = $MigrationOperatorServiceAccount',
    'image = "postgres:16-alpine"',
    '"run.googleapis.com/network-interfaces"',
    '"run.googleapis.com/vpc-access-egress" = "private-ranges-only"',
    'maxRetries = 0',
    'purpose = "database-consolidation-evidence"',
    'run", "jobs", "replace", $manifestPath',
    '[IO.File]::WriteAllText(',
    'Remove-Item -LiteralPath $manifestPath -Force',
    'run jobs delete $jobName',
    'Test-JobAbsent',
    'cannot find'
)
foreach ($fragment in $requiredRunnerFragments) {
    if (-not $runner.Contains($fragment)) {
        throw "Runner safety contract is missing: $fragment"
    }
}

if ($runner -match '(?i)--set-env-vars=[^\r\n]*PGPASSWORD') {
    throw "Runner must not pass the database password through ordinary environment variables."
}
if ($runner -match '(?i)(password\s*=\s*["''][^"'']+["''])') {
    throw "Runner appears to contain a literal password."
}

$forbiddenSql = '(?im)^\s*(INSERT|UPDATE|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|COPY|CALL|DO|VACUUM|REINDEX|CLUSTER|REFRESH)\b'
if ($sql -match $forbiddenSql) {
    throw "Evidence SQL contains a mutating or privileged statement: $($Matches[1])"
}
$unsupportedMetaCommands = @(
    [regex]::Matches($sql, '(?im)^\s*\\([A-Za-z]+)\b') |
        ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } |
        Where-Object { $_ -notin @('echo', 'pset') }
)
if ($unsupportedMetaCommands.Count -gt 0) {
    throw "Evidence SQL contains an unsupported psql meta-command: $($unsupportedMetaCommands[0])"
}
foreach ($aggregateMarker in @(
    'count(*)',
    'migration_summary',
    'migration_readiness',
    'reconciliation_summary',
    'guardian repair planning buckets',
    'SAFE_LEGACY_ONLY',
    'REVIEW_SHARED_DIVERGENCE',
    'REVIEW_BOTH_PRESENT_DIFFERENT',
    'REVIEW_NORMALIZED_ONLY',
    'REVIEW_INACTIVE_OR_MISSING_EFFECTIVE_LINK',
    'REVIEW_TENANT_OR_LINK_ANOMALY',
    'plan_sha256',
    'pg_stat_user_tables',
    'pg_stat_user_indexes'
)) {
    if (-not $sql.Contains($aggregateMarker)) {
        throw "Evidence SQL is missing expected aggregate/metadata marker: $aggregateMarker"
    }
}

$dryRunOutput = @(& $runnerPath 6>&1 | ForEach-Object { [string]$_ })
if ($dryRunOutput -notcontains 'Dry run passed: aggregate-only SQL and production safety invariants validated.') {
    throw "Runner dry run did not complete its local safety validation."
}

$applyGateWorked = $false
try {
    & $runnerPath -Apply 6>&1 | Out-Null
} catch {
    $applyGateWorked = $_.Exception.Message -eq 'Production execution requires both -Apply and -ConfirmProductionReadOnly.'
}
if (-not $applyGateWorked) {
    throw "Runner did not enforce the explicit production read-only confirmation gate before contacting GCP."
}

$identityGateWorked = $false
try {
    & $runnerPath -MigrationOperatorServiceAccount 'default@custoking-prod.iam.gserviceaccount.com' 6>&1 | Out-Null
} catch {
    $identityGateWorked = $_.Exception.Message -eq `
        'The evidence job must use the dedicated production migration-operator service account.'
}
if (-not $identityGateWorked) {
    throw "Runner allowed substitution of the dedicated production migration-operator identity."
}

Write-Host "database consolidation evidence Cloud SQL runner tests passed"

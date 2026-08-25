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
    '[IO.Compression.GZipStream]::new(',
    '$encodedSql.Length -gt 30000',
    '| base64 -d | gzip -dc > /tmp/evidence.sql',
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
    'SAFE_CREATE_UNLINKED_STUDENT',
    'REVIEW_UNLINKED_SCHOOL_MISSING_OR_INACTIVE',
    'REVIEW_UNLINKED_INVALID_OR_UNAPPROVED_LEGACY_VALUE',
    'REVIEW_UNLINKED_GUARDIAN_CONSENT',
    'REVIEW_UNLINKED_DETERMINISTIC_ID_EXISTS',
    'REVIEW_UNLINKED_IDENTITY_CANDIDATE',
    'REVIEW_UNLINKED_SHARED_LEGACY_CLUSTER',
    'REVIEW_SHARED_DIVERGENCE',
    'REVIEW_BOTH_PRESENT_DIFFERENT',
    'REVIEW_NORMALIZED_ONLY',
    'REVIEW_INACTIVE_OR_MISSING_EFFECTIVE_LINK',
    'REVIEW_TENANT_OR_LINK_ANOMALY',
    'plan_sha256',
    'safe_create_plan_sha256',
    'safe_create_students',
    'pg_stat_user_tables',
    'pg_stat_user_indexes'
)) {
    if (-not $sql.Contains($aggregateMarker)) {
        throw "Evidence SQL is missing expected aggregate/metadata marker: $aggregateMarker"
    }
}

# The production runner transports the read-only bundle through one Cloud Run environment value. Verify
# the current SQL round-trips through gzip and remains below the runner's guarded 30,000-character limit.
$wrappedSql = "\set ON_ERROR_STOP on`nBEGIN READ ONLY;`n$sql`nCOMMIT;"
$inputBytes = [Text.Encoding]::UTF8.GetBytes($wrappedSql)
$compressed = [IO.MemoryStream]::new()
$compressor = [IO.Compression.GZipStream]::new(
    $compressed,
    [IO.Compression.CompressionMode]::Compress,
    $true
)
try {
    $compressor.Write($inputBytes, 0, $inputBytes.Length)
} finally {
    $compressor.Dispose()
}
$compressedBytes = $compressed.ToArray()
$encoded = [Convert]::ToBase64String($compressedBytes)
if ($encoded.Length -gt 30000) {
    throw "Compressed evidence fixture exceeds the guarded Cloud Run transport envelope: $($encoded.Length)"
}
$compressed.Position = 0
$decompressor = [IO.Compression.GZipStream]::new(
    $compressed,
    [IO.Compression.CompressionMode]::Decompress
)
$reader = [IO.StreamReader]::new($decompressor, [Text.Encoding]::UTF8)
try {
    $roundTrip = $reader.ReadToEnd()
} finally {
    $reader.Dispose()
    $compressed.Dispose()
}
if ($roundTrip -ne $wrappedSql) {
    throw "Compressed evidence fixture did not round-trip exactly."
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

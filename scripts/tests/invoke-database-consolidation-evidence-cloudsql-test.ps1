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
    'image = "postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685"',
    '"run.googleapis.com/network-interfaces"',
    '"run.googleapis.com/vpc-access-egress" = "private-ranges-only"',
    'maxRetries = 0',
    'purpose = "database-consolidation-evidence"',
    'run", "jobs", "replace", $manifestPath',
    '[IO.File]::WriteAllText(',
    'Remove-Item -LiteralPath $manifestPath -Force',
    'run jobs delete $jobName',
    'Test-JobAbsent',
    'cannot find',
    'Disable-MigrationOperator',
    '"iam", "service-accounts", "disable"',
    '$identityDisableFailure'
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

$nonexistentGcloud = Join-Path ([IO.Path]::GetTempPath()) `
    "gcloud-must-not-run-$([guid]::NewGuid().ToString('N')).invalid"
$dryRunOutput = @(& $runnerPath -Gcloud $nonexistentGcloud 6>&1 | ForEach-Object { [string]$_ })
if ($dryRunOutput -notcontains 'Dry run passed: aggregate-only SQL and production safety invariants validated.') {
    throw "Runner dry run did not complete its local safety validation."
}

function Assert-GateFailure {
    param(
        [Parameter(Mandatory)] [hashtable]$BoundParameters,
        [Parameter(Mandatory)] [string]$ExpectedMessage
    )
    $worked = $false
    try {
        & $runnerPath @BoundParameters 6>&1 | Out-Null
    } catch {
        $worked = $_.Exception.Message -eq $ExpectedMessage
    }
    if (-not $worked) {
        throw "Evidence runner did not enforce gate: $ExpectedMessage"
    }
}

Assert-GateFailure -BoundParameters @{ Apply = $true } `
    -ExpectedMessage 'Production execution requires both -Apply and -ConfirmProductionReadOnly.'
Assert-GateFailure -BoundParameters @{
    MigrationOperatorServiceAccount = 'default@custoking-prod.iam.gserviceaccount.com'
} -ExpectedMessage 'The evidence job must use the dedicated production migration-operator service account.'
Assert-GateFailure -BoundParameters @{ Region = 'us-central1' } `
    -ExpectedMessage 'The evidence job region must be asia-south2.'
Assert-GateFailure -BoundParameters @{ HostAddress = '127.0.0.1' } `
    -ExpectedMessage 'The evidence job host must be the reviewed production private address.'
Assert-GateFailure -BoundParameters @{ Port = 5433 } `
    -ExpectedMessage 'The evidence job port must be 5432.'
Assert-GateFailure -BoundParameters @{ Database = 'postgres' } `
    -ExpectedMessage 'The evidence job database must be custoking_prod.'
Assert-GateFailure -BoundParameters @{ DatabaseUser = 'app_rt' } `
    -ExpectedMessage 'The evidence job must use the production database owner account.'
Assert-GateFailure -BoundParameters @{ PasswordSecret = 'db-password-dev' } `
    -ExpectedMessage 'The evidence job must use db-password-prod.'
Assert-GateFailure -BoundParameters @{ Network = 'other' } `
    -ExpectedMessage 'The evidence job network must be default.'
Assert-GateFailure -BoundParameters @{ Subnet = 'other' } `
    -ExpectedMessage 'The evidence job subnet must be default.'

# Exercise success and failure cleanup with a local gcloud double. Both paths must delete the
# disposable job and disable/verify the temporary migration identity.
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "ims-evidence-runner-test-$([guid]::NewGuid().ToString('N'))"
$fakeGcloud = Join-Path $fixtureRoot "fake-gcloud.ps1"
$callLog = Join-Path $fixtureRoot "calls.jsonl"
$manifestCapture = Join-Path $fixtureRoot "manifest.json"
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
try {
    $fakeSource = @'
param([Parameter(ValueFromRemainingArguments = $true)] [string[]]$Rest)
[IO.File]::AppendAllText($env:EVIDENCE_RUNNER_CALL_LOG, (ConvertTo-Json -Compress @($Rest)) + [Environment]::NewLine)
if ($Rest.Count -ge 4 -and $Rest[0] -eq 'run' -and $Rest[1] -eq 'jobs' -and $Rest[2] -eq 'replace') {
    Copy-Item -LiteralPath $Rest[3] -Destination $env:EVIDENCE_RUNNER_MANIFEST -Force
}
if ($Rest.Count -ge 3 -and $Rest[0] -eq 'run' -and $Rest[1] -eq 'jobs' -and $Rest[2] -eq 'describe') {
    Write-Output 'not found'
    & $env:ComSpec /d /c exit 1
    return
}
if ($Rest.Count -ge 3 -and $Rest[0] -eq 'run' -and $Rest[1] -eq 'jobs' -and
    $Rest[2] -eq 'execute' -and $env:EVIDENCE_RUNNER_FAIL_EXECUTE -eq '1') {
    & $env:ComSpec /d /c exit 1
    return
}
if ($Rest.Count -ge 3 -and $Rest[0] -eq 'iam' -and $Rest[1] -eq 'service-accounts' -and $Rest[2] -eq 'describe') {
    if (@($Rest | Where-Object { $_ -eq '--format=json' }).Count -gt 0) {
        Write-Output '{"disabled":false}'
    } else {
        Write-Output 'true'
    }
}
& $env:ComSpec /d /c exit 0
'@
    [IO.File]::WriteAllText($fakeGcloud, $fakeSource, [Text.UTF8Encoding]::new($false))
    $env:EVIDENCE_RUNNER_CALL_LOG = $callLog
    $env:EVIDENCE_RUNNER_MANIFEST = $manifestCapture

    $applyOutput = @(& $runnerPath -Apply -ConfirmProductionReadOnly -Gcloud $fakeGcloud 6>&1 |
        ForEach-Object { [string]$_ })
    if ($applyOutput -notcontains `
        'Disposable Cloud Run job deletion and migration-operator disablement were confirmed. Aggregate results are available in Cloud Logging.') {
        throw "Evidence runner did not complete its disposable lifecycle with the gcloud double."
    }
    $callText = Get-Content -LiteralPath $callLog -Raw
    foreach ($requiredCall in @(
        '"run","jobs","replace"',
        '"run","jobs","execute"',
        '"run","jobs","delete"',
        '"iam","service-accounts","disable"'
    )) {
        if (-not $callText.Contains($requiredCall)) {
            throw "Evidence lifecycle did not invoke: $requiredCall"
        }
    }

    $manifest = Get-Content -LiteralPath $manifestCapture -Raw | ConvertFrom-Json
    $task = $manifest.spec.template.spec.template.spec
    if ($task.serviceAccountName -ne 'migration-operator@custoking-prod.iam.gserviceaccount.com') {
        throw "Evidence job did not use the dedicated owner identity."
    }
    $container = $task.containers[0]
    if ($container.args[1] -notmatch "-h '10\.92\.0\.3' -p '5432' -U 'appuser' -d 'custoking_prod'") {
        throw "Evidence manifest did not preserve the exact production database coordinates."
    }
    $password = @($container.env | Where-Object { $_.name -eq 'PGPASSWORD' })[0]
    if ($password.valueFrom.secretKeyRef.name -ne 'db-password-prod') {
        throw "Evidence manifest did not pin the production password secret."
    }

    [IO.File]::WriteAllText($callLog, "", [Text.UTF8Encoding]::new($false))
    $env:EVIDENCE_RUNNER_FAIL_EXECUTE = '1'
    $operationFailed = $false
    try {
        & $runnerPath -Apply -ConfirmProductionReadOnly -Gcloud $fakeGcloud 6>&1 | Out-Null
    } catch {
        $operationFailed = $_.Exception.Message -eq `
            'gcloud failed while attempting to execute the read-only evidence job.'
    }
    if (-not $operationFailed) {
        throw "Evidence runner did not surface the simulated execution failure."
    }
    $failureCallText = Get-Content -LiteralPath $callLog -Raw
    foreach ($requiredCleanupCall in @(
        '"run","jobs","delete"',
        '"iam","service-accounts","disable"'
    )) {
        if (-not $failureCallText.Contains($requiredCleanupCall)) {
            throw "Evidence failure path did not invoke cleanup: $requiredCleanupCall"
        }
    }
} finally {
    Remove-Item Env:EVIDENCE_RUNNER_FAIL_EXECUTE -ErrorAction SilentlyContinue
    Remove-Item Env:EVIDENCE_RUNNER_CALL_LOG -ErrorAction SilentlyContinue
    Remove-Item Env:EVIDENCE_RUNNER_MANIFEST -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}

Write-Host "database consolidation evidence Cloud SQL runner tests passed"

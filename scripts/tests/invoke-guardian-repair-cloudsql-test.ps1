$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "../..")
$runnerPath = Join-Path $root "scripts/invoke-guardian-repair-cloudsql.ps1"
$contractPath = Join-Path $root "scripts/guardian-repair-contract.sql"
$approvedPlan = "fe0425a615d15a1444cd8cbd9b3bbe64a5360a6b8a3a9f33e5b6110be7684492"
$approvedContract = "fa0ca25fd6c2f2e63f9040cebeb3899481415540ca3cc61a331624836012b641"
$approvedPayload = "6f3a742cf411d2a0829a40ddd580f894a095048a73e2f5095fea3118a114db21"
$approvalReference = "github:guardian-repair-test"
$sourceRevision = "0123456789abcdef0123456789abcdef01234567"

$runner = Get-Content -LiteralPath $runnerPath -Raw
$contract = (Get-Content -LiteralPath $contractPath -Raw) -replace "`r`n?", "`n"

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile(
    $runnerPath,
    [ref]$tokens,
    [ref]$parseErrors
)
if (@($parseErrors).Count -gt 0) {
    throw "Guardian repair runner has PowerShell parse errors: $($parseErrors -join '; ')"
}

$requiredRunnerFragments = @(
    '[switch]$ConfirmProductionWrite',
    '$approvedStudents = 13',
    '$approvedRelationships = 14',
    '$approvedFields = 15',
    '$approvedPlanSha256 = "fe0425a615d15a1444cd8cbd9b3bbe64a5360a6b8a3a9f33e5b6110be7684492"',
    '$approvedContractDigest = "fa0ca25fd6c2f2e63f9040cebeb3899481415540ca3cc61a331624836012b641"',
    '$approvedPayloadSha256 = "6f3a742cf411d2a0829a40ddd580f894a095048a73e2f5095fea3118a114db21"',
    'BEGIN ISOLATION LEVEL SERIALIZABLE;',
    "SET LOCAL TIME ZONE 'UTC';",
    "pg_try_advisory_xact_lock(hashtextextended('student.guardian-repair-v1', 0))",
    'IN SHARE MODE NOWAIT;',
    '[IO.Compression.GZipStream]::new(',
    '$encodedSql.Length -gt 30000',
    '| base64 -d | gzip -dc > /tmp/guardian-repair.sql',
    "default_transaction_isolation=serializable",
    'psql -X -q -v ON_ERROR_STOP=1',
    'name = "PGSSLMODE"; value = "require"',
    'secretKeyRef = [ordered]@{ name = $PasswordSecret; key = "latest" }',
    'serviceAccountName = $MigrationOperatorServiceAccount',
    'image = "postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685"',
    '"run.googleapis.com/network-interfaces"',
    '"run.googleapis.com/vpc-access-egress" = "private-ranges-only"',
    'maxRetries = 0',
    'purpose = "guardian-repair"; access = "owner-only"',
    'run", "jobs", "replace", $manifestPath',
    'run jobs delete $jobName',
    'Disable-MigrationOperator',
    '"iam", "service-accounts", "disable"',
    'Remove-Item -LiteralPath $manifestPath -Force',
    'Test-JobAbsent'
)
foreach ($fragment in $requiredRunnerFragments) {
    if (-not $runner.Contains($fragment)) {
        throw "Guardian repair runner safety contract is missing: $fragment"
    }
}

if ($runner -match '(?i)--set-env-vars=[^\r\n]*PGPASSWORD') {
    throw "Guardian repair runner must not pass the database password through ordinary environment variables."
}
if ($runner -match '(?i)(password\s*=\s*["''][^"'']+["''])') {
    throw "Guardian repair runner appears to contain a literal password."
}

$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $contractDigest = ([BitConverter]::ToString(
        $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($contract))
    ) -replace '-', '').ToLowerInvariant()
} finally {
    $sha256.Dispose()
}
if ($contractDigest -ne $approvedPayload) {
    throw "Guardian repair contract digest changed: $contractDigest"
}
if ($contract -notmatch '(?im)^\s*SELECT\s+student\.execute_guardian_repair_v1\s*\(') {
    throw "Guardian repair contract does not call the reviewed capability."
}
if (@([regex]::Matches($contract, '(?m);\s*(?:--[^\r\n]*)?$')).Count -ne 1) {
    throw "Guardian repair contract must contain exactly one SQL statement."
}
if ($contract -match '(?im)^\s*(INSERT|UPDATE|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|COPY|CALL|DO)\b') {
    throw "Guardian repair contract must invoke only the reviewed database capability."
}

$wrappedSql = @"
\set ON_ERROR_STOP on
BEGIN ISOLATION LEVEL SERIALIZABLE;
SET LOCAL TIME ZONE 'UTC';
SELECT pg_try_advisory_xact_lock(hashtextextended('student.guardian-repair-v1', 0))
    AS guardian_repair_lock_acquired \gset
\if :guardian_repair_lock_acquired
\else
    \echo 'Another guardian repair transaction holds the execution lock.'
    \quit 3
\endif
LOCK TABLE tenant_school.schools, student.students, student.guardians,
           student.student_guardians, student.student_consent_events,
           student.student_review_campaigns, student.student_review_items
    IN SHARE MODE NOWAIT;
$contract
COMMIT;
"@
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
$encoded = [Convert]::ToBase64String($compressed.ToArray())
if ($encoded.Length -gt 30000) {
    throw "Compressed guardian repair contract exceeds the guarded Cloud Run transport envelope."
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
    throw "Compressed guardian repair contract did not round-trip exactly."
}

$nonexistentGcloud = Join-Path ([IO.Path]::GetTempPath()) `
    "gcloud-must-not-run-$([guid]::NewGuid().ToString('N')).invalid"
$dryRunOutput = @(& $runnerPath -Gcloud $nonexistentGcloud 6>&1 | ForEach-Object { [string]$_ })
if ($dryRunOutput -notcontains `
    'Dry run passed: the pinned guardian repair capability and transport invariants validated.') {
    throw "Guardian repair runner dry run did not complete local safety validation."
}
if ($dryRunOutput -notcontains `
    'No Google Cloud resource was created and no database statement was executed.') {
    throw "Guardian repair runner dry run did not state its no-execution guarantee."
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
        throw "Guardian repair runner did not enforce gate: $ExpectedMessage"
    }
}

Assert-GateFailure -BoundParameters @{ Apply = $true } `
    -ExpectedMessage 'Production guardian repair requires both -Apply and -ConfirmProductionWrite.'
Assert-GateFailure -BoundParameters @{ Apply = $true; ConfirmProductionWrite = $true } `
    -ExpectedMessage 'ExpectedPlanSha256 must exactly match the reviewed production safe-create plan hash.'
Assert-GateFailure -BoundParameters @{
    Apply = $true
    ConfirmProductionWrite = $true
    ExpectedPlanSha256 = $approvedPlan
    ExpectedStudents = 13
    ExpectedRelationships = 14
    ExpectedFields = 14
} -ExpectedMessage 'Expected guardian repair counts must exactly match 13 students, 14 relationships, and 15 fields.'
Assert-GateFailure -BoundParameters @{
    Apply = $true
    ConfirmProductionWrite = $true
    ExpectedPlanSha256 = $approvedPlan
    ExpectedStudents = 13
    ExpectedRelationships = 14
    ExpectedFields = 15
    ExpectedContractDigest = ('0' * 64)
} -ExpectedMessage 'ExpectedContractDigest must exactly match the reviewed guardian planner contract digest.'
Assert-GateFailure -BoundParameters @{
    MigrationOperatorServiceAccount = 'default@custoking-prod.iam.gserviceaccount.com'
} `
    -ExpectedMessage 'The guardian repair job must use the dedicated production migration-operator service account.'
Assert-GateFailure -BoundParameters @{ DatabaseUser = 'app_rt' } `
    -ExpectedMessage 'The guardian repair must use the production database owner account.'
Assert-GateFailure -BoundParameters @{ Region = 'us-central1' } `
    -ExpectedMessage 'The guardian repair region must be asia-south2.'
Assert-GateFailure -BoundParameters @{ HostAddress = '127.0.0.1' } `
    -ExpectedMessage 'The guardian repair host must be the reviewed production private address.'
Assert-GateFailure -BoundParameters @{ Port = 5433 } `
    -ExpectedMessage 'The guardian repair port must be 5432.'
Assert-GateFailure -BoundParameters @{ Database = 'postgres' } `
    -ExpectedMessage 'The guardian repair database must be custoking_prod.'
Assert-GateFailure -BoundParameters @{ PasswordSecret = 'db-password-dev' } `
    -ExpectedMessage 'The guardian repair must use db-password-prod.'
Assert-GateFailure -BoundParameters @{ Network = 'other' } `
    -ExpectedMessage 'The guardian repair network must be default.'
Assert-GateFailure -BoundParameters @{ Subnet = 'other' } `
    -ExpectedMessage 'The guardian repair subnet must be default.'
Assert-GateFailure -BoundParameters @{
    Apply = $true
    ConfirmProductionWrite = $true
    ExpectedPlanSha256 = $approvedPlan
    ExpectedStudents = 13
    ExpectedRelationships = 14
    ExpectedFields = 15
    ExpectedContractDigest = $approvedContract
} -ExpectedMessage 'ApprovalReference must identify the reviewed production-write approval.'
Assert-GateFailure -BoundParameters @{
    Apply = $true
    ConfirmProductionWrite = $true
    ExpectedPlanSha256 = $approvedPlan
    ExpectedStudents = 13
    ExpectedRelationships = 14
    ExpectedFields = 15
    ExpectedContractDigest = $approvedContract
    ApprovalReference = $approvalReference
} -ExpectedMessage 'SourceRevision must be the exact lowercase deployed Git revision.'

# Exercise the complete external lifecycle with a local gcloud double. It captures the generated manifest,
# reports the unique disposable job absent after deletion, and proves identity disablement is verified.
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "ims-guardian-runner-test-$([guid]::NewGuid().ToString('N'))"
$fakeGcloud = Join-Path $fixtureRoot "fake-gcloud.ps1"
$callLog = Join-Path $fixtureRoot "calls.jsonl"
$manifestCapture = Join-Path $fixtureRoot "manifest.json"
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
try {
    $fakeSource = @'
param([Parameter(ValueFromRemainingArguments = $true)] [string[]]$Rest)
[IO.File]::AppendAllText($env:GUARDIAN_RUNNER_CALL_LOG, (ConvertTo-Json -Compress @($Rest)) + [Environment]::NewLine)
if ($Rest.Count -ge 4 -and $Rest[0] -eq 'run' -and $Rest[1] -eq 'jobs' -and $Rest[2] -eq 'replace') {
    Copy-Item -LiteralPath $Rest[3] -Destination $env:GUARDIAN_RUNNER_MANIFEST -Force
}
if ($Rest.Count -ge 3 -and $Rest[0] -eq 'run' -and $Rest[1] -eq 'jobs' -and $Rest[2] -eq 'describe') {
    Write-Output 'not found'
    & $env:ComSpec /d /c exit 1
    return
}
if ($Rest.Count -ge 3 -and $Rest[0] -eq 'run' -and $Rest[1] -eq 'jobs' -and
    $Rest[2] -eq 'execute' -and $env:GUARDIAN_RUNNER_FAIL_EXECUTE -eq '1') {
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
    $env:GUARDIAN_RUNNER_CALL_LOG = $callLog
    $env:GUARDIAN_RUNNER_MANIFEST = $manifestCapture
    $applyOutput = @(& $runnerPath `
        -Apply -ConfirmProductionWrite `
        -ExpectedPlanSha256 $approvedPlan `
        -ExpectedStudents 13 -ExpectedRelationships 14 -ExpectedFields 15 `
        -ExpectedContractDigest $approvedContract `
        -ApprovalReference $approvalReference -SourceRevision $sourceRevision `
        -Gcloud $fakeGcloud 6>&1 | ForEach-Object { [string]$_ })
    if ($applyOutput -notcontains `
        'Disposable Cloud Run job deletion and migration-operator disablement were confirmed.') {
        throw "Guardian repair runner did not complete its disposable lifecycle with the gcloud double."
    }

    $callText = (Get-Content -LiteralPath $callLog -Raw)
    foreach ($requiredCall in @(
        '"run","jobs","replace"',
        '"run","jobs","execute"',
        '"run","jobs","delete"',
        '"iam","service-accounts","disable"'
    )) {
        if (-not $callText.Contains($requiredCall)) {
            throw "Guardian repair lifecycle did not invoke: $requiredCall"
        }
    }

    $manifest = Get-Content -LiteralPath $manifestCapture -Raw | ConvertFrom-Json
    $task = $manifest.spec.template.spec.template.spec
    if ($task.maxRetries -ne 0) { throw "Guardian repair job maxRetries must be zero." }
    if ($task.serviceAccountName -ne 'migration-operator@custoking-prod.iam.gserviceaccount.com') {
        throw "Guardian repair job did not use the dedicated owner identity."
    }
    $container = $task.containers[0]
    if ($container.image -ne 'postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685') {
        throw "Guardian repair job image was not pinned to the reviewed immutable digest."
    }
    $environment = @{}
    foreach ($entry in $container.env) { $environment[$entry.name] = $entry }
    if ($environment.EXPECTED_PLAN_SHA256.value -ne $approvedPlan -or
        [int]$environment.EXPECTED_STUDENTS.value -ne 13 -or
        [int]$environment.EXPECTED_RELATIONSHIPS.value -ne 14 -or
        [int]$environment.EXPECTED_FIELDS.value -ne 15 -or
        $environment.EXPECTED_CONTRACT_DIGEST.value -ne $approvedContract -or
        $environment.APPROVAL_REFERENCE.value -ne $approvalReference -or
        $environment.SOURCE_REVISION.value -ne $sourceRevision -or
        $environment.RUNNER_PAYLOAD_SHA256.value -ne $approvedPayload -or
        [string]::IsNullOrWhiteSpace($environment.OPERATOR_JOB_NAME.value)) {
        throw "Guardian repair manifest did not preserve the exact reviewed contract inputs."
    }
    if ($environment.PGPASSWORD.valueFrom.secretKeyRef.name -ne 'db-password-prod') {
        throw "Guardian repair manifest did not source the owner password from Secret Manager."
    }
    if ([string]::IsNullOrWhiteSpace($environment.REPAIR_SQL_B64.value) -or
        $environment.REPAIR_SQL_B64.value.Length -gt 30000) {
        throw "Guardian repair manifest payload was missing or exceeded its guarded envelope."
    }

    [IO.File]::WriteAllText($callLog, "", [Text.UTF8Encoding]::new($false))
    $env:GUARDIAN_RUNNER_FAIL_EXECUTE = '1'
    $operationFailed = $false
    try {
        & $runnerPath `
            -Apply -ConfirmProductionWrite `
            -ExpectedPlanSha256 $approvedPlan `
            -ExpectedStudents 13 -ExpectedRelationships 14 -ExpectedFields 15 `
            -ExpectedContractDigest $approvedContract `
            -ApprovalReference $approvalReference -SourceRevision $sourceRevision `
            -Gcloud $fakeGcloud 6>&1 | Out-Null
    } catch {
        $operationFailed = $_.Exception.Message -eq `
            'gcloud failed while attempting to execute the guardian repair job.'
    }
    if (-not $operationFailed) {
        throw "Guardian repair runner did not surface the simulated execution failure."
    }
    $failureCallText = Get-Content -LiteralPath $callLog -Raw
    foreach ($requiredCleanupCall in @(
        '"run","jobs","delete"',
        '"iam","service-accounts","disable"'
    )) {
        if (-not $failureCallText.Contains($requiredCleanupCall)) {
            throw "Guardian repair failure path did not invoke cleanup: $requiredCleanupCall"
        }
    }
} finally {
    Remove-Item Env:GUARDIAN_RUNNER_FAIL_EXECUTE -ErrorAction SilentlyContinue
    Remove-Item Env:GUARDIAN_RUNNER_CALL_LOG -ErrorAction SilentlyContinue
    Remove-Item Env:GUARDIAN_RUNNER_MANIFEST -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}

Write-Host "guardian repair Cloud SQL runner contract tests passed"

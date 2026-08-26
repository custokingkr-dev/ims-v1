$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$runnerPath = Join-Path $root "scripts/invoke-student-projection-requeue-cloudsql.ps1"
$contractPath = Join-Path $root "scripts/student-projection-requeue-contract.sql"
$approvedReference = "projection-requeue-v1:3fb7f7a0c94560561684f02754e9dec824feb6ae1c2e032664d88949de28fb17"
$approvedPayloadSha256 = "af8e1e2021ce0c3c33219dc9a2dcb92a9d1e295b3e2854bb07274bc1cf285141"

function Invoke-Runner {
    param([string[]]$Arguments)
    $output = @(& pwsh -NoProfile -File $runnerPath @Arguments 2>&1)
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($output -join "`n") }
}

function Assert-Failure {
    param([string[]]$Arguments, [string]$ExpectedMessage)
    $result = Invoke-Runner -Arguments $Arguments
    if ($result.ExitCode -eq 0) { throw "Expected runner failure containing: $ExpectedMessage" }
    if ($result.Output -notmatch [regex]::Escape($ExpectedMessage)) {
        throw "Runner failure did not contain '$ExpectedMessage'.`n$($result.Output)"
    }
}

if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) { throw "Projection runner is missing." }
if (-not (Test-Path -LiteralPath $contractPath -PathType Leaf)) { throw "Projection contract is missing." }

$contract = (Get-Content -LiteralPath $contractPath -Raw) -replace "`r`n?", "`n"
$bytes = [Text.Encoding]::UTF8.GetBytes($contract)
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $actualDigest = ([BitConverter]::ToString($sha256.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
} finally {
    $sha256.Dispose()
}
if ($actualDigest -ne $approvedPayloadSha256) { throw "Projection contract digest drifted." }

$requiredContractFragments = @(
    $approvedReference,
    "BEGIN ISOLATION LEVEL SERIALIZABLE",
    "LOCK TABLE reporting.reporting_event_inbox IN SHARE ROW EXCLUSIVE MODE NOWAIT",
    "exactly_one_current_issue",
    "exactly_one_approved_candidate",
    "reporting.requeue_student_projection(student_id)",
    "approved_event_is_received"
)
$runner = Get-Content -LiteralPath $runnerPath -Raw
$combined = "$runner`n$contract"
foreach ($fragment in $requiredContractFragments) {
    if ($combined -notmatch [regex]::Escape($fragment)) { throw "Projection guard is missing: $fragment" }
}
if ($runner -notmatch 'maxRetries\s*=\s*0') { throw "Projection job retries must remain disabled." }
if ($runner -notmatch 'PGSSLMODE.*require') { throw "Projection job must require TLS." }
if ($runner -notmatch 'Disable-MigrationOperator') { throw "Projection runner must disable its temporary identity." }

$dryRun = Invoke-Runner -Arguments @()
if ($dryRun.ExitCode -ne 0 -or $dryRun.Output -notmatch 'Dry run passed') {
    throw "Projection runner dry run failed.`n$($dryRun.Output)"
}

Assert-Failure -Arguments @('-ProjectId', 'custoking-dev') `
    -ExpectedMessage 'This runner is restricted to custoking-prod.'
Assert-Failure -Arguments @('-Apply') `
    -ExpectedMessage 'Production projection requeue requires both -Apply and -ConfirmProductionWrite.'
Assert-Failure -Arguments @(
    '-Apply', '-ConfirmProductionWrite',
    '-ApprovalReference', 'projection-requeue-v1:wrong',
    '-SourceRevision', '2031865a8238c87af16a23a84589e0a402832b98'
) -ExpectedMessage 'ApprovalReference must exactly match the reviewed single-projection approval.'
Assert-Failure -Arguments @(
    '-Apply', '-ConfirmProductionWrite',
    '-ApprovalReference', $approvedReference,
    '-SourceRevision', 'not-a-deployed-revision'
) -ExpectedMessage 'SourceRevision must be the exact lowercase deployed Git revision.'

Write-Host "student projection requeue Cloud SQL runner contract tests passed"

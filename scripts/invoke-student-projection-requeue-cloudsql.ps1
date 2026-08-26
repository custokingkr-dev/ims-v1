param(
    [string]$ProjectId = "custoking-prod",
    [string]$Region = "asia-south2",
    [string]$HostAddress = "10.92.0.3",
    [int]$Port = 5432,
    [string]$Database = "custoking_prod",
    [string]$DatabaseUser = "appuser",
    [string]$PasswordSecret = "db-password-prod",
    [string]$MigrationOperatorServiceAccount = "",
    [string]$Network = "default",
    [string]$Subnet = "default",
    [string]$ApprovalReference = "",
    [string]$SourceRevision = "",
    [switch]$Apply,
    [switch]$ConfirmProductionWrite,
    [string]$Gcloud = $(if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" })
)

$ErrorActionPreference = "Stop"

$approvedReference = "projection-requeue-v1:3fb7f7a0c94560561684f02754e9dec824feb6ae1c2e032664d88949de28fb17"
$approvedPayloadSha256 = "af8e1e2021ce0c3c33219dc9a2dcb92a9d1e295b3e2854bb07274bc1cf285141"

if ($ProjectId -ne "custoking-prod") { throw "This runner is restricted to custoking-prod." }
if ($Region -ne "asia-south2") { throw "The projection requeue region must be asia-south2." }
if ($HostAddress -ne "10.92.0.3") { throw "The projection requeue host must be the reviewed production private address." }
if ($Port -ne 5432) { throw "The projection requeue port must be 5432." }
if ($Database -ne "custoking_prod") { throw "The projection requeue database must be custoking_prod." }
if ($DatabaseUser -ne "appuser") { throw "The projection requeue must use the production database owner account." }
if ($PasswordSecret -ne "db-password-prod") { throw "The projection requeue must use db-password-prod." }
if ($Network -ne "default" -or $Subnet -ne "default") { throw "The projection requeue must use the reviewed default network and subnet." }
if ([string]::IsNullOrWhiteSpace($MigrationOperatorServiceAccount)) {
    $MigrationOperatorServiceAccount = "migration-operator@$ProjectId.iam.gserviceaccount.com"
}
if ($MigrationOperatorServiceAccount -ne "migration-operator@$ProjectId.iam.gserviceaccount.com") {
    throw "The projection requeue must use the dedicated migration-operator identity."
}

function Get-CanonicalSha256 {
    param([Parameter(Mandatory)] [string]$Text)
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Text -replace "`r`n?", "`n"))
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha256.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

$contractPath = Join-Path $PSScriptRoot "student-projection-requeue-contract.sql"
if (-not (Test-Path -LiteralPath $contractPath -PathType Leaf)) {
    throw "Projection requeue contract was not found: $contractPath"
}
$contractSql = (Get-Content -LiteralPath $contractPath -Raw) -replace "`r`n?", "`n"
if ((Get-CanonicalSha256 -Text $contractSql) -ne $approvedPayloadSha256) {
    throw "The projection requeue payload digest does not match the reviewed capability."
}
if ($contractSql -notmatch [regex]::Escape($approvedReference)) {
    throw "The projection requeue payload does not pin the approved reference."
}
if ($contractSql -notmatch 'reporting\.requeue_student_projection\(student_id\)') {
    throw "The projection requeue payload does not invoke the owner-only repair capability."
}

$wrappedSql = @"
\set ON_ERROR_STOP on
BEGIN ISOLATION LEVEL SERIALIZABLE;
SET LOCAL TIME ZONE 'UTC';
SELECT pg_try_advisory_xact_lock(hashtextextended('reporting.student-projection-requeue-v1', 0))
    AS projection_requeue_lock_acquired \gset
\if :projection_requeue_lock_acquired
\else
    \echo 'Another projection requeue transaction holds the execution lock.'
    \quit 3
\endif
LOCK TABLE reporting.reporting_event_inbox IN SHARE ROW EXCLUSIVE MODE NOWAIT;
LOCK TABLE reporting.dim_student, reporting.student_projection_tombstones IN SHARE MODE NOWAIT;
$contractSql
COMMIT;
"@

$wrappedBytes = [Text.Encoding]::UTF8.GetBytes($wrappedSql)
$compressed = [IO.MemoryStream]::new()
$gzip = [IO.Compression.GZipStream]::new($compressed, [IO.Compression.CompressionMode]::Compress, $true)
try { $gzip.Write($wrappedBytes, 0, $wrappedBytes.Length) } finally { $gzip.Dispose() }
$encodedSql = [Convert]::ToBase64String($compressed.ToArray())
$compressed.Dispose()
if ($encodedSql.Length -gt 30000) { throw "The compressed projection contract exceeds the safe Cloud Run environment envelope." }

if (-not $Apply) {
    Write-Host "Dry run passed: the pinned single-projection requeue capability and transport invariants validated."
    Write-Host "No Google Cloud resource was created and no database statement was executed."
    return
}
if (-not $ConfirmProductionWrite) {
    throw "Production projection requeue requires both -Apply and -ConfirmProductionWrite."
}
if ($ApprovalReference -ne $approvedReference) {
    throw "ApprovalReference must exactly match the reviewed single-projection approval."
}
if ($SourceRevision -notmatch '^[0-9a-f]{40}$') {
    throw "SourceRevision must be the exact lowercase deployed Git revision."
}

$jobName = "ims-projection-requeue-$([datetime]::UtcNow.ToString('yyyyMMddHHmmss'))-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$manifestPath = [IO.Path]::GetFullPath((Join-Path $tempRoot "$jobName.json"))
if (-not $manifestPath.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The disposable projection manifest escaped the operating-system temporary directory."
}

function Invoke-GcloudCaptured {
    param(
        [Parameter(Mandatory)] [string]$Operation,
        [Parameter(Mandatory)] [string[]]$Arguments
    )
    $null = @(& $Gcloud @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "gcloud failed while attempting to $Operation." }
}

function Disable-MigrationOperator {
    Invoke-GcloudCaptured -Operation "disable the migration-operator service account" -Arguments @(
        "iam", "service-accounts", "disable", $MigrationOperatorServiceAccount,
        "--project=$ProjectId", "--quiet"
    )
    $disabled = @(& $Gcloud iam service-accounts describe $MigrationOperatorServiceAccount `
        "--project=$ProjectId" "--format=value(disabled)" 2>&1)
    if ($LASTEXITCODE -ne 0 -or (($disabled -join "`n").Trim() -notmatch '^(?i:true)$')) {
        throw "The migration-operator service account disablement could not be verified."
    }
}

$containerCommand = "umask 077 && printf '%s' `"`$REQUEUE_SQL_B64`" | base64 -d | gzip -dc > /tmp/requeue.sql && export PGOPTIONS='-c default_transaction_isolation=serializable -c timezone=UTC' && exec psql -X -q -v ON_ERROR_STOP=1 -v `"approval_reference=`$APPROVAL_REFERENCE`" -h '$HostAddress' -p '$Port' -U '$DatabaseUser' -d '$Database' -f /tmp/requeue.sql"
$manifest = [ordered]@{
    apiVersion = "run.googleapis.com/v1"
    kind = "Job"
    metadata = [ordered]@{
        name = $jobName
        labels = [ordered]@{ purpose = "student-projection-requeue"; access = "owner-only" }
    }
    spec = [ordered]@{
        template = [ordered]@{
            metadata = [ordered]@{
                annotations = [ordered]@{
                    "run.googleapis.com/execution-environment" = "gen2"
                    "run.googleapis.com/network-interfaces" = "[{`"network`":`"$Network`",`"subnetwork`":`"$Subnet`"}]"
                    "run.googleapis.com/vpc-access-egress" = "private-ranges-only"
                }
            }
            spec = [ordered]@{
                taskCount = 1
                template = [ordered]@{
                    spec = [ordered]@{
                        containers = @([ordered]@{
                            image = "postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685"
                            command = @("sh")
                            args = @("-c", $containerCommand)
                            env = @(
                                [ordered]@{ name = "PGSSLMODE"; value = "require" },
                                [ordered]@{ name = "REQUEUE_SQL_B64"; value = $encodedSql },
                                [ordered]@{ name = "APPROVAL_REFERENCE"; value = $ApprovalReference },
                                [ordered]@{ name = "SOURCE_REVISION"; value = $SourceRevision },
                                [ordered]@{ name = "RUNNER_PAYLOAD_SHA256"; value = $approvedPayloadSha256 },
                                [ordered]@{
                                    name = "PGPASSWORD"
                                    valueFrom = [ordered]@{
                                        secretKeyRef = [ordered]@{ name = $PasswordSecret; key = "latest" }
                                    }
                                }
                            )
                        })
                        maxRetries = 0
                        serviceAccountName = $MigrationOperatorServiceAccount
                        timeoutSeconds = "300"
                    }
                }
            }
        }
    }
}

[IO.File]::WriteAllText(
    $manifestPath,
    ($manifest | ConvertTo-Json -Depth 20 -Compress),
    [Text.UTF8Encoding]::new($false)
)

$operationFailure = $null
$cleanupFailure = $null
$identityFailure = $null
try {
    $identity = @(& $Gcloud iam service-accounts describe $MigrationOperatorServiceAccount `
        "--project=$ProjectId" "--format=value(disabled)" 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect the migration-operator identity." }
    if (($identity -join "`n").Trim() -match '^(?i:true)$') {
        throw "The dedicated migration-operator service account is disabled; do not substitute another identity."
    }
    Invoke-GcloudCaptured -Operation "create the disposable projection requeue job" -Arguments @(
        "run", "jobs", "replace", $manifestPath,
        "--project=$ProjectId", "--region=$Region", "--quiet"
    )
    Invoke-GcloudCaptured -Operation "execute the disposable projection requeue job" -Arguments @(
        "run", "jobs", "execute", $jobName,
        "--project=$ProjectId", "--region=$Region", "--wait", "--quiet"
    )
} catch {
    $operationFailure = $_
} finally {
    try {
        & $Gcloud run jobs delete $jobName "--project=$ProjectId" "--region=$Region" --quiet *> $null
        $global:LASTEXITCODE = 0
        $remaining = @(& $Gcloud run jobs list "--project=$ProjectId" "--region=$Region" `
            "--filter=metadata.name=$jobName" "--format=value(metadata.name)" 2>&1)
        if ($LASTEXITCODE -ne 0 -or ($remaining | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -ne 0) {
            $cleanupFailure = "The disposable projection requeue job still exists after deletion."
        }
    } catch {
        $cleanupFailure = $_.Exception.Message
    } finally {
        if (Test-Path -LiteralPath $manifestPath) { Remove-Item -LiteralPath $manifestPath -Force }
    }
    try { Disable-MigrationOperator } catch { $identityFailure = $_.Exception.Message }
}

if ($identityFailure) { throw "Projection cleanup could not disable the migration identity: $identityFailure" }
if ($cleanupFailure) { throw "Projection job cleanup could not be confirmed: $cleanupFailure" }
if ($operationFailure) { throw $operationFailure }

Write-Host "Production single-projection requeue completed with its exact approved fingerprint."
Write-Host "Disposable Cloud Run job deletion and migration-operator disablement were confirmed."

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
    [string]$ExpectedPlanSha256 = "",
    [int]$ExpectedStudents = 0,
    [int]$ExpectedRelationships = 0,
    [int]$ExpectedFields = 0,
    [string]$ExpectedContractDigest = "",
    [string]$ApprovalReference = "",
    [string]$SourceRevision = "",
    [switch]$Apply,
    [switch]$ConfirmProductionWrite,
    [string]$Gcloud = $(if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" })
)

$ErrorActionPreference = "Stop"

# These values are the reviewed, read-only production classifier result. Changing any one of them
# requires a new evidence capture and a reviewed source change; command-line input cannot redefine them.
$approvedPlanSha256 = "fe0425a615d15a1444cd8cbd9b3bbe64a5360a6b8a3a9f33e5b6110be7684492"
$approvedStudents = 13
$approvedRelationships = 14
$approvedFields = 15
$approvedContractDigest = "fa0ca25fd6c2f2e63f9040cebeb3899481415540ca3cc61a331624836012b641"
$approvedPayloadSha256 = "6f3a742cf411d2a0829a40ddd580f894a095048a73e2f5095fea3118a114db21"

if ($ProjectId -ne "custoking-prod") {
    throw "This runner is restricted to the custoking-prod project."
}
if ($Region -ne "asia-south2") { throw "The guardian repair region must be asia-south2." }
if ($HostAddress -ne "10.92.0.3") { throw "The guardian repair host must be the reviewed production private address." }
if ($Port -ne 5432) { throw "The guardian repair port must be 5432." }
if ($Database -ne "custoking_prod") { throw "The guardian repair database must be custoking_prod." }
if ($DatabaseUser -ne "appuser") {
    throw "The guardian repair must use the production database owner account."
}
if ($PasswordSecret -ne "db-password-prod") { throw "The guardian repair must use db-password-prod." }
if ($Network -ne "default") { throw "The guardian repair network must be default." }
if ($Subnet -ne "default") { throw "The guardian repair subnet must be default." }
if ([string]::IsNullOrWhiteSpace($MigrationOperatorServiceAccount)) {
    $MigrationOperatorServiceAccount = "migration-operator@$ProjectId.iam.gserviceaccount.com"
}
$expectedServiceAccount = "migration-operator@$ProjectId.iam.gserviceaccount.com"
if ($MigrationOperatorServiceAccount -ne $expectedServiceAccount) {
    throw "The guardian repair job must use the dedicated production migration-operator service account."
}

$safeDnsOrIpv4 = '^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$'
$safeIdentifier = '^[A-Za-z_][A-Za-z0-9_-]{0,62}$'
$safeResourceName = '^[A-Za-z0-9][A-Za-z0-9_-]{0,254}$'
$sha256Pattern = '^[0-9a-f]{64}$'
if ($HostAddress -notmatch $safeDnsOrIpv4) { throw "HostAddress contains unsupported characters." }
if ($Port -lt 1 -or $Port -gt 65535) { throw "Port must be between 1 and 65535." }
if ($Database -notmatch $safeIdentifier) { throw "Database contains unsupported characters." }
if ($PasswordSecret -notmatch $safeResourceName) { throw "PasswordSecret contains unsupported characters." }
if ($Network -notmatch $safeResourceName) { throw "Network contains unsupported characters." }
if ($Subnet -notmatch $safeResourceName) { throw "Subnet contains unsupported characters." }

function Get-CanonicalSha256 {
    param([Parameter(Mandatory)] [string]$Text)

    $canonicalText = $Text -replace "`r`n?", "`n"
    $bytes = [Text.Encoding]::UTF8.GetBytes($canonicalText)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha256.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

$contractPath = Join-Path $PSScriptRoot "guardian-repair-contract.sql"
if (-not (Test-Path -LiteralPath $contractPath -PathType Leaf)) {
    throw "Guardian repair contract SQL was not found: $contractPath"
}
$contractSql = (Get-Content -LiteralPath $contractPath -Raw) -replace "`r`n?", "`n"
$actualContractSha256 = Get-CanonicalSha256 -Text $contractSql
if ($actualContractSha256 -ne $approvedPayloadSha256) {
    throw "The guardian repair invocation payload digest does not match the reviewed capability."
}
if ($contractSql -notmatch '(?im)^\s*SELECT\s+student\.execute_guardian_repair_v1\s*\(') {
    throw "The guardian repair contract does not invoke the reviewed database capability."
}
$contractStatements = @([regex]::Matches($contractSql, '(?m);\s*(?:--[^\r\n]*)?$'))
if ($contractStatements.Count -ne 1) {
    throw "The guardian repair contract must contain exactly one SQL statement."
}
$unsupportedMetaCommands = @([regex]::Matches($contractSql, '(?im)^\s*\\([A-Za-z]+)\b'))
if ($unsupportedMetaCommands.Count -gt 0) {
    throw "The guardian repair contract contains an unsupported psql meta-command."
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
$contractSql
COMMIT;
"@
$wrappedSqlBytes = [Text.Encoding]::UTF8.GetBytes($wrappedSql)
$compressedSqlStream = [IO.MemoryStream]::new()
$gzip = [IO.Compression.GZipStream]::new(
    $compressedSqlStream,
    [IO.Compression.CompressionMode]::Compress,
    $true
)
try {
    $gzip.Write($wrappedSqlBytes, 0, $wrappedSqlBytes.Length)
} finally {
    $gzip.Dispose()
}
$encodedSql = [Convert]::ToBase64String($compressedSqlStream.ToArray())
$compressedSqlStream.Dispose()
# Cloud Run caps an individual environment value at 32 KiB. Keep safety headroom and fail before
# contacting GCP if a future reviewed contract no longer fits the disposable-job transport.
if ($encodedSql.Length -gt 30000) {
    throw "The compressed guardian repair contract exceeds the safe Cloud Run environment-value envelope."
}

$jobName = "ims-guardian-repair-$([datetime]::UtcNow.ToString('yyyyMMddHHmmss'))-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$manifestPath = [IO.Path]::GetFullPath((Join-Path $tempRoot "$jobName.json"))
if (-not $manifestPath.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The disposable guardian repair manifest path escaped the operating-system temporary directory."
}

if (-not $Apply) {
    Write-Host "Dry run passed: the pinned guardian repair capability and transport invariants validated."
    Write-Host "No Google Cloud resource was created and no database statement was executed."
    return
}
if (-not $ConfirmProductionWrite) {
    throw "Production guardian repair requires both -Apply and -ConfirmProductionWrite."
}
if ($ExpectedPlanSha256 -notmatch $sha256Pattern -or $ExpectedPlanSha256 -ne $approvedPlanSha256) {
    throw "ExpectedPlanSha256 must exactly match the reviewed production safe-create plan hash."
}
if ($ExpectedStudents -ne $approvedStudents -or
    $ExpectedRelationships -ne $approvedRelationships -or
    $ExpectedFields -ne $approvedFields) {
    throw "Expected guardian repair counts must exactly match 13 students, 14 relationships, and 15 fields."
}
if ($ExpectedContractDigest -notmatch $sha256Pattern -or
    $ExpectedContractDigest -ne $approvedContractDigest) {
    throw "ExpectedContractDigest must exactly match the reviewed guardian planner contract digest."
}
if ($ApprovalReference -notmatch '^[A-Za-z0-9][A-Za-z0-9._:/#-]{6,254}$') {
    throw "ApprovalReference must identify the reviewed production-write approval."
}
if ($SourceRevision -notmatch '^[0-9a-f]{40}$') {
    throw "SourceRevision must be the exact lowercase deployed Git revision."
}

function Invoke-GcloudCaptured {
    param(
        [Parameter(Mandatory)] [string]$Operation,
        [Parameter(Mandatory)] [string[]]$Arguments
    )

    $output = @(& $Gcloud @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        # Do not include provider output or the container command: future diagnostics must not copy
        # Secret Manager-backed values or the repair capability into an operator-facing exception.
        throw "gcloud failed while attempting to $Operation."
    }
    return $output
}

function Test-JobAbsent {
    $priorErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $description = @(& $Gcloud run jobs describe $jobName `
            "--project=$ProjectId" "--region=$Region" --format="value(name)" 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $priorErrorActionPreference
        $global:LASTEXITCODE = 0
    }

    if ($exitCode -eq 0) { return $false }
    $message = ($description | ForEach-Object { [string]$_ }) -join "`n"
    if ($message -match '(?i)(404|not found|cannot find|does not exist)') { return $true }
    throw "Could not confirm removal of the disposable guardian repair job."
}

function Disable-MigrationOperator {
    Invoke-GcloudCaptured -Operation "disable the migration-operator service account" -Arguments @(
        "iam", "service-accounts", "disable", $MigrationOperatorServiceAccount,
        "--project=$ProjectId", "--quiet"
    ) | Out-Null
    $disabled = ((Invoke-GcloudCaptured -Operation "verify the migration-operator service account is disabled" `
        -Arguments @(
            "iam", "service-accounts", "describe", $MigrationOperatorServiceAccount,
            "--project=$ProjectId", "--format=value(disabled)"
        )) -join "`n").Trim()
    if ($disabled -notmatch '^(?i:true)$') {
        throw "The migration-operator service account disablement could not be verified."
    }
}

$operationFailure = $null
$cleanupFailure = $null
$identityDisableFailure = $null
try {
    $serviceAccountJson = (Invoke-GcloudCaptured -Operation "inspect the migration-operator service account" `
        -Arguments @(
            "iam", "service-accounts", "describe", $MigrationOperatorServiceAccount,
            "--project=$ProjectId", "--format=json"
        )) -join "`n"
    $serviceAccount = $serviceAccountJson | ConvertFrom-Json
    if ($serviceAccount.disabled -eq $true) {
        throw "The dedicated migration-operator service account is disabled; an owner must explicitly enable it for the approved window."
    }

    # The owner password stays in Secret Manager. Only reviewed hashes and counts are ordinary
    # environment values, and each is independently constrained above before this manifest exists.
    $containerCommand = "umask 077 && printf '%s' `"`$REPAIR_SQL_B64`" | base64 -d | gzip -dc > /tmp/guardian-repair.sql && export PGOPTIONS='-c default_transaction_isolation=serializable -c timezone=UTC' && exec psql -X -q -v ON_ERROR_STOP=1 -v `"expected_plan_sha256=`$EXPECTED_PLAN_SHA256`" -v `"expected_students=`$EXPECTED_STUDENTS`" -v `"expected_relationships=`$EXPECTED_RELATIONSHIPS`" -v `"expected_fields=`$EXPECTED_FIELDS`" -v `"expected_contract_digest=`$EXPECTED_CONTRACT_DIGEST`" -v `"approval_reference=`$APPROVAL_REFERENCE`" -v `"source_revision=`$SOURCE_REVISION`" -v `"runner_payload_sha256=`$RUNNER_PAYLOAD_SHA256`" -v `"operator_job_name=`$OPERATOR_JOB_NAME`" -h '$HostAddress' -p '$Port' -U '$DatabaseUser' -d '$Database' -f /tmp/guardian-repair.sql"
    $manifest = [ordered]@{
        apiVersion = "run.googleapis.com/v1"
        kind = "Job"
        metadata = [ordered]@{
            name = $jobName
            labels = [ordered]@{ purpose = "guardian-repair"; access = "owner-only" }
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
                                    [ordered]@{ name = "REPAIR_SQL_B64"; value = $encodedSql },
                                    [ordered]@{ name = "EXPECTED_PLAN_SHA256"; value = $ExpectedPlanSha256 },
                                    [ordered]@{ name = "EXPECTED_STUDENTS"; value = [string]$ExpectedStudents },
                                    [ordered]@{ name = "EXPECTED_RELATIONSHIPS"; value = [string]$ExpectedRelationships },
                                    [ordered]@{ name = "EXPECTED_FIELDS"; value = [string]$ExpectedFields },
                                    [ordered]@{ name = "EXPECTED_CONTRACT_DIGEST"; value = $ExpectedContractDigest },
                                    [ordered]@{ name = "APPROVAL_REFERENCE"; value = $ApprovalReference },
                                    [ordered]@{ name = "SOURCE_REVISION"; value = $SourceRevision },
                                    [ordered]@{ name = "RUNNER_PAYLOAD_SHA256"; value = $approvedPayloadSha256 },
                                    [ordered]@{ name = "OPERATOR_JOB_NAME"; value = $jobName },
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
                            timeoutSeconds = "900"
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

    Invoke-GcloudCaptured -Operation "create the disposable guardian repair job" -Arguments @(
        "run", "jobs", "replace", $manifestPath,
        "--project=$ProjectId", "--region=$Region", "--quiet"
    ) | Out-Null

    Invoke-GcloudCaptured -Operation "execute the guardian repair job" -Arguments @(
        "run", "jobs", "execute", $jobName,
        "--project=$ProjectId", "--region=$Region", "--wait", "--quiet"
    ) | Out-Null
} catch {
    $operationFailure = $_
} finally {
    $priorErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $Gcloud run jobs delete $jobName `
            "--project=$ProjectId" "--region=$Region" --quiet *> $null
        $global:LASTEXITCODE = 0
        if (-not (Test-JobAbsent)) {
            $cleanupFailure = "The disposable guardian repair job still exists after deletion."
        }
    } catch {
        $cleanupFailure = $_.Exception.Message
    } finally {
        if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
            Remove-Item -LiteralPath $manifestPath -Force
        }
        $ErrorActionPreference = $priorErrorActionPreference
    }
    try {
        Disable-MigrationOperator
    } catch {
        $identityDisableFailure = $_.Exception.Message
    }
}

if ($null -ne $identityDisableFailure -and $null -ne $cleanupFailure) {
    throw "Guardian repair cleanup failed to remove the job and disable the migration identity: job=$cleanupFailure identity=$identityDisableFailure"
}
if ($null -ne $identityDisableFailure) {
    throw "Guardian repair cleanup could not disable the migration-operator identity: $identityDisableFailure"
}
if ($null -ne $cleanupFailure) {
    throw "Guardian repair job cleanup could not be confirmed: $cleanupFailure"
}
if ($null -ne $operationFailure) {
    throw $operationFailure
}

Write-Host "Production guardian repair capability completed with its exact reviewed contract."
Write-Host "Disposable Cloud Run job deletion and migration-operator disablement were confirmed."

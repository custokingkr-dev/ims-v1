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
    [switch]$Apply,
    [switch]$ConfirmProductionReadOnly,
    [string]$Gcloud = $(if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" })
)

$ErrorActionPreference = "Stop"

if ($ProjectId -ne "custoking-prod") {
    throw "This runner is restricted to the custoking-prod project."
}
if ([string]::IsNullOrWhiteSpace($MigrationOperatorServiceAccount)) {
    $MigrationOperatorServiceAccount = "migration-operator@$ProjectId.iam.gserviceaccount.com"
}
$expectedServiceAccount = "migration-operator@$ProjectId.iam.gserviceaccount.com"
if ($MigrationOperatorServiceAccount -ne $expectedServiceAccount) {
    throw "The evidence job must use the dedicated production migration-operator service account."
}
if ($Region -ne "asia-south2") {
    throw "The evidence job region must be asia-south2."
}
if ($HostAddress -ne "10.92.0.3") {
    throw "The evidence job host must be the reviewed production private address."
}
if ($Port -ne 5432) {
    throw "The evidence job port must be 5432."
}
if ($Database -ne "custoking_prod") {
    throw "The evidence job database must be custoking_prod."
}
if ($DatabaseUser -ne "appuser") {
    throw "The evidence job must use the production database owner account."
}
if ($PasswordSecret -ne "db-password-prod") {
    throw "The evidence job must use db-password-prod."
}
if ($Network -ne "default") {
    throw "The evidence job network must be default."
}
if ($Subnet -ne "default") {
    throw "The evidence job subnet must be default."
}

$safeDnsOrIpv4 = '^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$'
$safeIdentifier = '^[A-Za-z_][A-Za-z0-9_-]{0,62}$'
$safeResourceName = '^[A-Za-z0-9][A-Za-z0-9_-]{0,254}$'
if ($HostAddress -notmatch $safeDnsOrIpv4) { throw "HostAddress contains unsupported characters." }
if ($Port -lt 1 -or $Port -gt 65535) { throw "Port must be between 1 and 65535." }
if ($Database -notmatch $safeIdentifier) { throw "Database contains unsupported characters." }
if ($DatabaseUser -notmatch $safeIdentifier) { throw "DatabaseUser contains unsupported characters." }
if ($PasswordSecret -notmatch $safeResourceName) { throw "PasswordSecret contains unsupported characters." }
if ($Network -notmatch $safeResourceName) { throw "Network contains unsupported characters." }
if ($Subnet -notmatch $safeResourceName) { throw "Subnet contains unsupported characters." }

$sqlPath = Join-Path $PSScriptRoot "database-consolidation-evidence.sql"
if (-not (Test-Path -LiteralPath $sqlPath -PathType Leaf)) {
    throw "Database consolidation evidence SQL was not found: $sqlPath"
}
$evidenceSql = Get-Content -LiteralPath $sqlPath -Raw

# Keep this reusable runner structurally incapable of executing a mutating evidence bundle.
# PostgreSQL read-only mode is still enforced independently below, so this check is defense in depth.
$forbiddenSql = '(?im)^\s*(INSERT|UPDATE|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|COPY|CALL|DO|VACUUM|REINDEX|CLUSTER|REFRESH)\b'
if ($evidenceSql -match $forbiddenSql) {
    throw "The evidence SQL contains a statement outside the aggregate/read-only allowlist: $($Matches[1])."
}
if ($evidenceSql -notmatch '(?im)^\s*SELECT\b') {
    throw "The evidence SQL contains no SELECT evidence queries."
}
$unsupportedMetaCommands = @(
    [regex]::Matches($evidenceSql, '(?im)^\s*\\([A-Za-z]+)\b') |
        ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } |
        Where-Object { $_ -notin @('echo', 'pset') }
)
if ($unsupportedMetaCommands.Count -gt 0) {
    throw "The evidence SQL contains an unsupported psql meta-command: $($unsupportedMetaCommands[0])."
}

$wrappedSql = @"
\set ON_ERROR_STOP on
BEGIN READ ONLY;
$evidenceSql
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
# Cloud Run caps one environment-variable value at 32 KiB. Leave headroom for platform validation and
# fail locally before resource creation if future evidence growth stops compressing within the envelope.
if ($encodedSql.Length -gt 30000) {
    throw "The compressed evidence bundle exceeds the safe Cloud Run environment-value envelope."
}
$jobName = "ims-db-evidence-$([datetime]::UtcNow.ToString('yyyyMMddHHmmss'))-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$manifestPath = [IO.Path]::GetFullPath((Join-Path $tempRoot "$jobName.json"))
if (-not $manifestPath.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The disposable job manifest path escaped the operating-system temporary directory."
}

if (-not $Apply) {
    Write-Host "Dry run passed: aggregate-only SQL and production safety invariants validated."
    Write-Host "No Google Cloud resource was created. Re-run with -Apply -ConfirmProductionReadOnly after review."
    return
}
if (-not $ConfirmProductionReadOnly) {
    throw "Production execution requires both -Apply and -ConfirmProductionReadOnly."
}

function Invoke-GcloudCaptured {
    param(
        [Parameter(Mandatory)] [string]$Operation,
        [Parameter(Mandatory)] [string[]]$Arguments
    )

    $output = @(& $Gcloud @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        # Intentionally omit command output and the container command from the exception. This
        # prevents a future gcloud diagnostic change from copying environment values into a log.
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
    throw "Could not confirm removal of the disposable evidence job."
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
        throw "The dedicated migration-operator service account is disabled; do not substitute another identity."
    }

    # The password is never read by this process or placed on a command line. Cloud Run resolves
    # the named Secret Manager version directly into the container's PGPASSWORD environment.
    # A temporary manifest also keeps the evidence SQL below Windows' command-line length limit.
    $containerCommand = "umask 077 && printf '%s' `"`$EVIDENCE_SQL_B64`" | base64 -d | gzip -dc > /tmp/evidence.sql && export PGOPTIONS='-c default_transaction_read_only=on' && exec psql -X -q -v ON_ERROR_STOP=1 -h '$HostAddress' -p '$Port' -U '$DatabaseUser' -d '$Database' -f /tmp/evidence.sql"
    $manifest = [ordered]@{
        apiVersion = "run.googleapis.com/v1"
        kind = "Job"
        metadata = [ordered]@{
            name = $jobName
            labels = [ordered]@{ purpose = "database-consolidation-evidence" }
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
                                    [ordered]@{ name = "EVIDENCE_SQL_B64"; value = $encodedSql },
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

    Invoke-GcloudCaptured -Operation "create the disposable read-only evidence job" -Arguments @(
        "run", "jobs", "replace", $manifestPath,
        "--project=$ProjectId", "--region=$Region",
        "--quiet"
    ) | Out-Null

    Invoke-GcloudCaptured -Operation "execute the read-only evidence job" -Arguments @(
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
            $cleanupFailure = "The disposable evidence job still exists after deletion."
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
    throw "Production evidence cleanup failed to remove the job and disable the migration identity: job=$cleanupFailure identity=$identityDisableFailure"
}
if ($null -ne $identityDisableFailure) {
    throw "Production evidence cleanup could not disable the migration-operator identity: $identityDisableFailure"
}
if ($null -ne $cleanupFailure) {
    throw "Production evidence job cleanup could not be confirmed: $cleanupFailure"
}
if ($null -ne $operationFailure) {
    throw $operationFailure
}

Write-Host "Production database consolidation evidence completed in enforced read-only mode."
Write-Host "Disposable Cloud Run job deletion and migration-operator disablement were confirmed. Aggregate results are available in Cloud Logging."

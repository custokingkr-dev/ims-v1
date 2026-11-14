param(
    [string]$Project = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "Project is required: pass -Project explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
    [string]$Region = "asia-south2",
    [ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$HostAddress = "10.116.0.3",
    [int]$Port = 5432,
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Database = "custoking_ims_v1",
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$ConnectUser = "appuser",
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Owner = "appuser",
    [string]$ConnectPasswordSecret = "db-password",
    [string]$AppRtPasswordSecret = "app-rt-password",
    [switch]$AuditOnly,
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Network = "default",
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Subnet = "default",
    [string]$Gcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd",
    # Writes the job definition and prints, as JSON, the gcloud commands a run would issue. Calls nothing.
    [switch]$RenderOnly,
    # Where the job definition is written. Default: a file in the temp directory, removed after the run.
    [string]$JobFile = ""
)
$ErrorActionPreference = "Stop"
# gcloud's exit code is checked below; PowerShell 7 must not turn it into its own error first.
$PSNativeCommandUseErrorActionPreference = $false

# The SQL never travels on a command line. gcloud.cmd runs through cmd.exe, which stops at 8191 characters, and Windows
# refuses any command line over 32767; the two files inline as base64 had grown to about 45K. Each file goes gzip
# compressed and base64 encoded in its own environment variable of a job definition that `gcloud run jobs replace` reads
# from a file. Cloud Run allows 32 KB per variable; scripts/tests/create_app_rt_role_migrated_test.py keeps both well
# under that and runs the rendered job's shell against PostgreSQL.
Add-Type -AssemblyName System.IO.Compression
function ConvertTo-GzipBase64([string]$Path) {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $buffer = New-Object System.IO.MemoryStream
    $gzip = New-Object System.IO.Compression.GZipStream($buffer, [System.IO.Compression.CompressionMode]::Compress)
    try { $gzip.Write($bytes, 0, $bytes.Length) } finally { $gzip.Dispose() }
    return [Convert]::ToBase64String($buffer.ToArray())
}
function Get-Sha256Hex([string]$Path) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([System.IO.File]::ReadAllBytes($Path)))).Replace("-", "").ToLowerInvariant() }
    finally { $sha.Dispose() }
}

$createPath = Join-Path $PSScriptRoot "create-app-rt-role.sql"
$auditPath  = Join-Path $PSScriptRoot "audit-app-rt-privileges.sql"

# Runs as appuser; create-app-rt-role.sql elevates via SET ROLE cloudsqlsuperuser (set_superuser=1, owner=appuser).
# Live RLS/DDL probes are NOT run in prod (appuser is not a superuser): the audit asserts the structural
# attributes instead (NOSUPERUSER/NOBYPASSRLS/not-a-cloudsqlsuperuser-member), which guarantees RLS will bite.
# Each decoded file is checked against the hash of the file this script read, and the hash is printed, so the job log
# names the exact script version that ran.
$connection = "-h $HostAddress -p $Port -U $ConnectUser -d $Database"
$createStep = @(
    "printf '%s' `"`$APP_RT_CREATE_SQL_GZ_B64`" | base64 -d | gunzip > /tmp/create.sql",
    "test `"`$(sha256sum /tmp/create.sql | cut -d' ' -f1)`" = '$(Get-Sha256Hex $createPath)' || { echo 'decoded create.sql does not match the file this job was made from' >&2; exit 3; }",
    "echo 'create-app-rt-role.sql sha256 $(Get-Sha256Hex $createPath)'",
    "psql -v ON_ERROR_STOP=1 -v app_rt_password=`"`$APP_RT_PASSWORD`" -v owner=$Owner -v set_superuser=1 $connection -f /tmp/create.sql"
)
$auditStep = @(
    "printf '%s' `"`$APP_RT_AUDIT_SQL_GZ_B64`" | base64 -d | gunzip > /tmp/audit.sql",
    "test `"`$(sha256sum /tmp/audit.sql | cut -d' ' -f1)`" = '$(Get-Sha256Hex $auditPath)' || { echo 'decoded audit.sql does not match the file this job was made from' >&2; exit 3; }",
    "echo 'audit-app-rt-privileges.sql sha256 $(Get-Sha256Hex $auditPath)'",
    "psql -v ON_ERROR_STOP=1 $connection -f /tmp/audit.sql"
)
$steps = @("set -eu", "export PGSSLMODE=require")
if (-not $AuditOnly) { $steps += $createStep }
$steps += $auditStep
$shell = ($steps -join "`n") + "`n"

$environment = @(
    [ordered]@{ name = "PGSSLMODE"; value = "require" },
    [ordered]@{ name = "PGPASSWORD"; valueFrom = [ordered]@{ secretKeyRef = [ordered]@{ name = $ConnectPasswordSecret; key = "latest" } } },
    [ordered]@{ name = "APP_RT_AUDIT_SQL_GZ_B64"; value = (ConvertTo-GzipBase64 $auditPath) }
)
if (-not $AuditOnly) {
    $environment += [ordered]@{ name = "APP_RT_PASSWORD"; valueFrom = [ordered]@{ secretKeyRef = [ordered]@{ name = $AppRtPasswordSecret; key = "latest" } } }
    $environment += [ordered]@{ name = "APP_RT_CREATE_SQL_GZ_B64"; value = (ConvertTo-GzipBase64 $createPath) }
}

$job = "ims-app-rt-" + (Get-Date -Format "yyyyMMddHHmmss")
$definition = [ordered]@{
    apiVersion = "run.googleapis.com/v1"
    kind = "Job"
    metadata = [ordered]@{ name = $job; labels = [ordered]@{ purpose = "app-rt-provisioning" } }
    spec = [ordered]@{ template = [ordered]@{
        metadata = [ordered]@{ annotations = [ordered]@{
            "run.googleapis.com/network-interfaces" = ('[{{"network":"{0}","subnetwork":"{1}"}}]' -f $Network, $Subnet)
            "run.googleapis.com/vpc-access-egress" = "private-ranges-only"
        } }
        spec = [ordered]@{ taskCount = 1; template = [ordered]@{ spec = [ordered]@{
            maxRetries = 0
            containers = @([ordered]@{ image = "postgres:16-alpine"; command = @("sh"); args = @("-c", $shell); env = $environment })
        } } }
    } }
}
if ([string]::IsNullOrWhiteSpace($JobFile)) { $JobFile = Join-Path ([System.IO.Path]::GetTempPath()) "$job.json" }
[System.IO.File]::WriteAllText($JobFile, ($definition | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))

$replaceArgs = @("run", "jobs", "replace", $JobFile, "--project=$Project", "--region=$Region", "--quiet")
$executeArgs = @("run", "jobs", "execute", $job, "--project=$Project", "--region=$Region", "--wait")
$deleteArgs  = @("run", "jobs", "delete", $job, "--project=$Project", "--region=$Region", "--quiet")
if ($RenderOnly) {
    [ordered]@{ job = $job; jobFile = $JobFile; gcloud = $Gcloud; commands = @($replaceArgs, $executeArgs, $deleteArgs) } |
        ConvertTo-Json -Depth 5
    return
}

try {
    & $Gcloud @replaceArgs | Write-Output
    if ($LASTEXITCODE -ne 0) { throw "Could not create the app_rt provisioning job $job (gcloud exit $LASTEXITCODE)." }
    & $Gcloud @executeArgs | Write-Output
    if ($LASTEXITCODE -ne 0) { throw "The app_rt provisioning job $job failed (gcloud exit $LASTEXITCODE). Read its execution log before re-running." }
    Write-Host "app_rt provisioning job completed: $job" -ForegroundColor Green
} finally {
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { & $Gcloud @deleteArgs *> $null }
    finally {
        $ErrorActionPreference = $prev
        Remove-Item -LiteralPath $JobFile -ErrorAction SilentlyContinue
    }
}

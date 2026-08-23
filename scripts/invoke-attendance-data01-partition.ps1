param(
    [Parameter(Mandatory)]
    [ValidateSet("Preflight", "Freeze", "Build", "Verify", "Cutover", "Finalize", "RollbackBeforeResume")]
    [string]$Phase,
    # libpq connection URI or keyword string. Prefer a short-lived environment-provided credential.
    [string]$ConnectionString = $env:DATABASE_URL,
    [switch]$MaintenanceApproved,
    [switch]$FinalizeApproved,
    [string]$EvidenceDirectory = "artifacts/capacity-gates/data01"
)

$ErrorActionPreference = "Stop"
if (-not (Get-Command psql -ErrorAction SilentlyContinue)) {
    throw "psql is required for the DATA-01 operator sequence."
}
if ([string]::IsNullOrWhiteSpace($ConnectionString)) {
    throw "ConnectionString or DATABASE_URL is required."
}

$phaseFiles = @{
    Preflight = "00_preflight.sql"
    Freeze = "10_freeze.sql"
    Build = "20_build.sql"
    Verify = "30_verify.sql"
    Cutover = "40_cutover.sql"
    Finalize = "50_finalize.sql"
    RollbackBeforeResume = "90_rollback_before_resume.sql"
}
$mutatingPhases = @("Freeze", "Build", "Verify", "Cutover", "Finalize", "RollbackBeforeResume")
if ($Phase -in $mutatingPhases -and -not $MaintenanceApproved) {
    throw "-$Phase requires -MaintenanceApproved after the school-core write drain is confirmed."
}
if ($Phase -eq "Finalize" -and -not $FinalizeApproved) {
    throw "Finalize permanently drops the legacy table and requires -FinalizeApproved."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sqlPath = Join-Path $repoRoot "scripts/sql/attendance-data01/$($phaseFiles[$Phase])"
if (-not (Test-Path -LiteralPath $sqlPath -PathType Leaf)) {
    throw "DATA-01 SQL file is missing: $sqlPath"
}

$processInfo = [Diagnostics.ProcessStartInfo]::new()
$processInfo.FileName = (Get-Command psql -ErrorAction Stop).Source
$processInfo.ArgumentList.Add("--no-psqlrc")
$processInfo.ArgumentList.Add("--set=ON_ERROR_STOP=1")
$processInfo.ArgumentList.Add("--file=$sqlPath")
$processInfo.UseShellExecute = $false
$processInfo.CreateNoWindow = $true
$processInfo.RedirectStandardOutput = $true
$processInfo.RedirectStandardError = $true
$processInfo.Environment["PGDATABASE"] = $ConnectionString

$pgOptions = @()
if ($MaintenanceApproved) { $pgOptions += "-c app.data01_maintenance_approved=DATA-01" }
if ($FinalizeApproved) { $pgOptions += "-c app.data01_finalize_approved=DROP-LEGACY-DATA-01" }
if ($pgOptions.Count -gt 0) {
    $existingOptions = [string]$processInfo.Environment["PGOPTIONS"]
    $processInfo.Environment["PGOPTIONS"] = (@($existingOptions) + $pgOptions |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "
}

$startedAt = [datetime]::UtcNow
$process = [Diagnostics.Process]::new()
$process.StartInfo = $processInfo
if (-not $process.Start()) { throw "Could not start psql." }
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
$process.WaitForExit()
$stdout = $stdoutTask.GetAwaiter().GetResult()
$stderr = $stderrTask.GetAwaiter().GetResult()

$evidence = [ordered]@{
    generatedAtUtc = [datetime]::UtcNow.ToString("o")
    phase = $Phase
    sqlFile = $phaseFiles[$Phase]
    durationSeconds = [math]::Round(([datetime]::UtcNow - $startedAt).TotalSeconds, 2)
    exitCode = $process.ExitCode
    deploymentMode = "offline-maintenance-window"
    maintenanceApproved = [bool]$MaintenanceApproved
    finalizeApproved = [bool]$FinalizeApproved
    stdout = $stdout.Trim()
    stderr = $stderr.Trim()
}
$evidencePath = Join-Path $repoRoot (Join-Path $EvidenceDirectory (
    "data01-$($Phase.ToLowerInvariant())-$([datetime]::UtcNow.ToString('yyyyMMddHHmmss')).json"))
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $evidencePath) | Out-Null
$evidence | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $evidencePath -Encoding UTF8

if ($process.ExitCode -ne 0) {
    throw "DATA-01 phase $Phase failed. Evidence: $evidencePath`n$stderr"
}

$evidence | ConvertTo-Json -Depth 4

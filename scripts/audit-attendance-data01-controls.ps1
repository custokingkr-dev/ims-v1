param(
    [string]$OperatorDirectory = "scripts/sql/attendance-data01",
    [string]$OperatorRunner = "scripts/invoke-attendance-data01-partition.ps1",
    [string]$Reporter = "services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/observability/AttendanceStorageHealthReporter.java",
    [string]$Terraform = "deploy/gcp/observability/attendance_growth.tf",
    [string]$TerraformVariables = "deploy/gcp/observability/variables.tf",
    [string]$Runbook = "docs/runbooks/ATTENDANCE-DATA01-PARTITION-ROLLOUT.md",
    [string]$Thresholds = "docs/DB-SCALING-THRESHOLDS.md"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$violations = [Collections.Generic.List[string]]::new()

function Read-RepoFile([string]$relativePath) {
    $path = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $violations.Add("Required DATA-01 file is missing: $relativePath")
        return ""
    }
    return Get-Content -LiteralPath $path -Raw
}

$requiredPhases = @(
    "00_preflight.sql",
    "10_freeze.sql",
    "20_build.sql",
    "30_verify.sql",
    "40_cutover.sql",
    "50_finalize.sql",
    "90_rollback_before_resume.sql"
)
$sql = @{}
foreach ($phase in $requiredPhases) {
    $sql[$phase] = Read-RepoFile (Join-Path $OperatorDirectory $phase)
    if (-not $sql[$phase].Contains("\set ON_ERROR_STOP on")) {
        $violations.Add("$phase must fail closed with psql ON_ERROR_STOP.")
    }
}

$runner = Read-RepoFile $OperatorRunner
$reporter = Read-RepoFile $Reporter
$terraform = Read-RepoFile $Terraform
$variables = Read-RepoFile $TerraformVariables
$runbook = Read-RepoFile $Runbook
$thresholds = Read-RepoFile $Thresholds

foreach ($required in @(
    "source_rows_at_freeze",
    "source_checksum_at_freeze",
    "data01_source_write_freeze",
    "LOCK TABLE attendance.attendance_student_records IN SHARE ROW EXCLUSIVE MODE")) {
    if (-not $sql["10_freeze.sql"].Contains($required)) {
        $violations.Add("Freeze phase missing safety contract: $required")
    }
}

foreach ($required in @(
    "attendance_student_record_identity",
    "PARTITION BY RANGE (attendance_date)",
    "attendance_student_records_default",
    "FOREIGN KEY (attendance_daily_id)",
    "ENABLE ROW LEVEL SECURITY",
    "CREATE POLICY tenant_isolation",
    "SECURITY DEFINER",
    "REVOKE ALL ON FUNCTION")) {
    if (-not $sql["20_build.sql"].Contains($required)) {
        $violations.Add("Build phase missing schema/security contract: $required")
    }
}

foreach ($required in @(
    "source_checksum IS DISTINCT FROM target_checksum",
    "identity registry differs",
    "NOT convalidated",
    "relrowsecurity",
    "tenant_isolation",
    "attendance_student_records_default")) {
    if (-not $sql["30_verify.sql"].Contains($required)) {
        $violations.Add("Verify phase missing correctness gate: $required")
    }
}

foreach ($required in @(
    "SET LOCAL lock_timeout = '10s'",
    "LOCK TABLE attendance.attendance_student_records IN ACCESS EXCLUSIVE MODE",
    "data01_post_cutover_write_guard",
    "post_cutover_write_statements")) {
    if (-not $sql["40_cutover.sql"].Contains($required)) {
        $violations.Add("Cutover phase missing bounded-lock/rollback boundary: $required")
    }
}

foreach ($required in @(
    "post_cutover_write_statements <> 0",
    "Rollback refused",
    "active_checksum",
    "legacy_checksum",
    "ACCESS EXCLUSIVE MODE")) {
    if (-not $sql["90_rollback_before_resume.sql"].Contains($required)) {
        $violations.Add("Rollback phase missing fail-closed gate: $required")
    }
}

foreach ($required in @(
    "-MaintenanceApproved",
    "-FinalizeApproved",
    "ProcessStartInfo",
    "ON_ERROR_STOP=1",
    'deploymentMode = "offline-maintenance-window"')) {
    if (-not $runner.Contains($required)) {
        $violations.Add("Operator runner missing guard/evidence contract: $required")
    }
}

foreach ($required in @(
    "pg_partition_tree",
    "pg_stat_user_tables",
    "pg_indexes_size",
    "sequentialScansInterval",
    "fullTableScanEquivalentsMilli",
    "current.approximateRows() < 1_000_000",
    "statisticsResetDetected")) {
    if (-not $reporter.Contains($required)) {
        $violations.Add("Attendance storage reporter missing bounded metric behavior: $required")
    }
}

foreach ($required in @(
    "enable_attendance_growth_monitoring ? 1 : 0",
    "attendance_partition_prepare",
    "attendance_partition_execute",
    "attendance_index_growth",
    "attendance_sequential_scan",
    "ALIGN_PERCENTILE_95",
    "REDUCE_MAX")) {
    if (-not $terraform.Contains($required)) {
        $violations.Add("DATA-01 Terraform missing disabled/reduced monitoring control: $required")
    }
}
if ($variables -notmatch '(?s)variable\s+"enable_attendance_growth_monitoring".*?default\s*=\s*false') {
    $violations.Add("DATA-01 monitoring must remain disabled by default until an approved plan/apply.")
}

foreach ($required in @(
    "offline maintenance-window migration",
    "Rollback boundary",
    "Do not resume service writes",
    "no app/Flyway deployment",
    "disk")) {
    if (-not $runbook.Contains($required, [StringComparison]::OrdinalIgnoreCase)) {
        $violations.Add("DATA-01 runbook missing operator warning: $required")
    }
}
foreach ($required in @(
    "10,000,000",
    "20,000,000",
    "25 million",
    "8 GiB",
    "one full-table equivalent",
    "enable_attendance_growth_monitoring=false")) {
    if (-not $thresholds.Contains($required, [StringComparison]::OrdinalIgnoreCase)) {
        $violations.Add("DB scaling thresholds missing DATA-01 control: $required")
    }
}

$attendanceMigrationDirectory = Join-Path $repoRoot `
    "services/school-core-service/src/main/resources/db/migration/attendance"
$automaticPartitionMigrations = @(Get-ChildItem -LiteralPath $attendanceMigrationDirectory -File |
    Where-Object { $_.Name -match '(?i)(data01|partition)' })
if ($automaticPartitionMigrations.Count -gt 0) {
    $violations.Add("DATA-01 partition DDL must not auto-run at service startup: " +
        (($automaticPartitionMigrations | ForEach-Object Name) -join ", "))
}

if ($violations.Count -gt 0) {
    Write-Host "Attendance DATA-01 control violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Attendance DATA-01 controls passed: staged freeze/build/verify/cutover/rollback safety, global uniqueness, RLS, disabled-by-default row/index/scan monitoring, and operator documentation are present."

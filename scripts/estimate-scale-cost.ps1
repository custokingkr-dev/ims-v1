param(
    [ValidateRange(1, 1000)] [int] $SchoolCount = 100,
    [ValidateRange(1, 10000000)] [int] $StudentCount = 200000,
    [ValidateRange(1, 365)] [int] $SchoolDaysPerYear = 220,
    [ValidateRange(1, 10)] [int] $AttendanceRetentionYears = 3,
    [ValidateRange(1, 500)] [int] $StaffPerSchool = 25,
    [ValidateRange(1, 500)] [int] $ApiActionsPerStaffDay = 40,
    [ValidateRange(1, 31)] [int] $BusinessDaysPerMonth = 22
)

$ErrorActionPreference = 'Stop'

# Price snapshot: billing-account pricing export for Delhi (asia-south2), 2026-08-10.
# Re-run the export query documented in docs/SCALE-READINESS-AND-COST-PLAN-2026-08-10.md
# before using this model for a commercial commitment.
$sqlZonalVcpuHourInr = 4.744053999
$sqlZonalRamGibHourInr = 0.803428499
$sqlZonalStorageGibMonthInr = 19.511834999
$cloudRunVcpuSecondInr = 0.000024 * 95.646
$cloudRunGibSecondInr = 0.0000025 * 95.646
$cloudRunMillionRequestsInr = 0.40 * 95.646

$attendanceRowsPerYear = [long]$StudentCount * $SchoolDaysPerYear
# Planning allowance includes heap tuple, TOAST/alignment, several indexes and ordinary bloat.
$attendanceGibPerYear = ($attendanceRowsPerYear * 550.0) / 1GB
$coreDataGib = ($StudentCount * 4096.0) / 1GB
$projectedUsedGib = (($attendanceGibPerYear * $AttendanceRetentionYears) + $coreDataGib) * 1.30
$provisionedStorageGib = [Math]::Ceiling([Math]::Max(100, $projectedUsedGib) / 25.0) * 25

if ($StudentCount -le 200000) {
    $sqlVcpu = 2
    $sqlRamGib = 7.5
    $sqlShape = 'db-custom-2-7680 (starting recommendation; validate under load)'
} else {
    $sqlVcpu = 4
    $sqlRamGib = 15
    $sqlShape = 'db-custom-4-15360 (planning recommendation; 2-vCPU is allowed only if load tests pass)'
}

$hoursPerMonth = 730
$sqlComputeZonal = ($sqlVcpu * $sqlZonalVcpuHourInr + $sqlRamGib * $sqlZonalRamGibHourInr) * $hoursPerMonth
$sqlStorageZonal = $provisionedStorageGib * $sqlZonalStorageGibMonthInr
$sqlBackup = $projectedUsedGib * $sqlZonalStorageGibMonthInr
$devAndSqlOperations = 600

$browserActions = [long]$SchoolCount * $StaffPerSchool * $ApiActionsPerStaffDay * $BusinessDaysPerMonth
$containerRequests = [long]($browserActions * 2.2)
$activeSeconds = $containerRequests * 0.30
$modeledCloudRun = ($activeSeconds * ($cloudRunVcpuSecondInr + 0.75 * $cloudRunGibSecondInr)) +
    (($containerRequests / 1000000.0) * $cloudRunMillionRequestsInr)
# Preserve the observed current monthly run-rate and allow for cold starts, reports and jobs.
$cloudRunPlanning = [Math]::Max(4000, $modeledCloudRun * 1.4)
$otherPlatform = 2000 # Build, Artifact Registry, secrets, logs, storage and ordinary network usage.

$zonalTotal = $sqlComputeZonal + $sqlStorageZonal + $sqlBackup + $devAndSqlOperations + $cloudRunPlanning + $otherPlatform
$haTotal = ($sqlComputeZonal * 2) + ($sqlStorageZonal * 2) + $sqlBackup +
    $devAndSqlOperations + $cloudRunPlanning + $otherPlatform

[pscustomobject]@{
    Schools = $SchoolCount
    Students = $StudentCount
    PeakAverageStudentsPerSchool = [Math]::Round($StudentCount / [double]$SchoolCount)
    AttendanceRowsPerYear = $attendanceRowsPerYear
    AttendanceGiBPerYearEstimate = [Math]::Round($attendanceGibPerYear, 1)
    ThreeYearUsedGiBEstimate = [Math]::Round($projectedUsedGib, 1)
    RecommendedProvisionedGiB = $provisionedStorageGib
    PlannedSqlShape = $sqlShape
    MonthlyContainerRequests = $containerRequests
    CloudRunMonthlyPlanningInr = [Math]::Round($cloudRunPlanning)
    ZonalSqlComputeInr = [Math]::Round($sqlComputeZonal)
    ZonalSqlStorageAndBackupInr = [Math]::Round($sqlStorageZonal + $sqlBackup)
    ZonalPlatformMonthlyInr = [Math]::Round($zonalTotal)
    ZonalPlanningRangeInr = ('{0:N0} - {1:N0}' -f ($zonalTotal * 0.8), ($zonalTotal * 1.25))
    RegionalHaPlatformMonthlyInr = [Math]::Round($haTotal)
    RegionalHaPlanningRangeInr = ('{0:N0} - {1:N0}' -f ($haTotal * 0.8), ($haTotal * 1.25))
    Excludes = 'SMS/WhatsApp provider, taxes, support, domains, staff, extraordinary egress and promotional credits'
} | Format-List

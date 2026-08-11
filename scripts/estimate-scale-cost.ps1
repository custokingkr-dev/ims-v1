param(
    [ValidateRange(1, 1000)] [int] $SchoolCount = 100,
    [ValidateRange(1, 10000000)] [int] $StudentCount = 200000,
    [ValidateRange(1, 100000)] [int] $LargeSchoolStudents = 10000,
    [ValidateRange(1, 365)] [int] $SchoolDaysPerYear = 220,
    [ValidateRange(1, 10)] [int] $AttendanceRetentionYears = 3,
    [ValidateRange(1, 500)] [int] $StaffPerSchool = 25,
    [ValidateRange(1, 500)] [int] $ApiActionsPerStaffDay = 40,
    [ValidateRange(1, 31)] [int] $BusinessDaysPerMonth = 22,
    [ValidateRange(0, 100)] [decimal] $PhotoCoveragePercent = 100,
    [ValidateRange(0, 10240)] [decimal] $AveragePhotoKiB = 100,
    [ValidateRange(1, 20)] [int] $StoredPhotoVersions = 1,
    [ValidateRange(1, 1000)] [decimal] $ExchangeRateInrPerUsd = 95.646,
    [ValidateRange(0, 1000)] [decimal] $SqlZonalVcpuHourInr = 4.744053999,
    [ValidateRange(0, 1000)] [decimal] $SqlZonalRamGibHourInr = 0.803428499,
    [ValidateRange(0, 1000)] [decimal] $SqlZonalStorageGibMonthInr = 19.511834999,
    [ValidateRange(1, 96)] [int] $SqlVcpu = 4,
    [ValidateRange(3.75, 624)] [decimal] $SqlRamGib = 7.5,
    # Delhi request-based Cloud Run Tier 2 SKUs from the billing pricing export
    # checked 2026-08-11. Override and refresh before budget commitments.
    [ValidateRange(0, 1)] [decimal] $CloudRunVcpuSecondUsd = 0.0000336,
    [ValidateRange(0, 1)] [decimal] $CloudRunGibSecondUsd = 0.0000035,
    [ValidateRange(0, 100)] [decimal] $CloudRunMillionRequestsUsd = 0.40,
    # Delhi Standard Storage SKU B219-7161-A1AF was USD 0.023/GiB-month in the
    # billing pricing export checked 2026-08-11. This remains overrideable and
    # must be refreshed before a budget or commercial decision.
    [ValidateRange(0, 100)] [decimal] $GcsStandardGibMonthUsd = 0.023,
    [ValidateRange(0, 1000000)] [decimal] $CloudRunPlanningFloorInr = 4000,
    [ValidateRange(0, 1000000)] [decimal] $DevAndSqlOperationsInr = 600,
    [ValidateRange(0, 1000000)] [decimal] $OtherPlatformInr = 2000
)

$ErrorActionPreference = 'Stop'

if ($LargeSchoolStudents -gt $StudentCount) {
    throw 'LargeSchoolStudents cannot exceed StudentCount.'
}
if ($SqlVcpu -ne 1 -and ($SqlVcpu -lt 2 -or $SqlVcpu % 2 -ne 0)) {
    throw 'Cloud SQL Enterprise custom vCPUs must be 1 or an even number from 2 through 96.'
}
$sqlMemoryMib = [decimal]$SqlRamGib * 1024
$sqlMemoryPerVcpuGib = [decimal]$SqlRamGib / $SqlVcpu
if ($sqlMemoryMib % 256 -ne 0 -or
    $sqlMemoryPerVcpuGib -lt 0.9 -or
    $sqlMemoryPerVcpuGib -gt 6.5) {
    throw 'Cloud SQL Enterprise custom memory must be a 256 MiB multiple and 0.9-6.5 GiB per vCPU.'
}

# Price snapshot: billing-account pricing export for Delhi (asia-south2), 2026-08-10.
# Re-run the export query documented in docs/SCALE-READINESS-AND-COST-PLAN-2026-08-10.md
# before using this model for a commercial commitment. Cloud Run request-based
# Tier 2 rates and Delhi classification were checked against the billing pricing
# export and official page on 2026-08-11. All rates are overrideable because
# billing-currency SKUs govern.
$cloudRunVcpuSecondInr = $CloudRunVcpuSecondUsd * $ExchangeRateInrPerUsd
$cloudRunGibSecondInr = $CloudRunGibSecondUsd * $ExchangeRateInrPerUsd
$cloudRunMillionRequestsInr = $CloudRunMillionRequestsUsd * $ExchangeRateInrPerUsd

$attendanceRowsPerYear = [long]$StudentCount * $SchoolDaysPerYear
# Planning allowance includes heap tuple, TOAST/alignment, several indexes and ordinary bloat.
$attendanceGibPerYear = ($attendanceRowsPerYear * 550.0) / 1GB
$coreDataGib = ($StudentCount * 4096.0) / 1GB
$projectedUsedGib = (($attendanceGibPerYear * $AttendanceRetentionYears) + $coreDataGib) * 1.30
$provisionedStorageGib = [Math]::Ceiling([Math]::Max(100, $projectedUsedGib) / 25.0) * 25

if ($SqlVcpu -eq 4 -and $sqlMemoryMib -eq 7680) {
    $sqlShapeEvidence = 'Cheapest measured full-soak pass and planning default: the corrective 4h10m/300-VU dev profile passed; MixedMorning and live 10k remain pending.'
    $capacityEvidenceScope = 'The measured soak used the 100-school/300k-row synthetic fixture and informs only the stated 100-150-school/200k-300k planning range. VUs are not student records; this is not production purchase, HA/SLA, failover, live-10k or business approval.'
} elseif ($SqlVcpu -eq 2 -and $sqlMemoryMib -eq 7680) {
    $sqlShapeEvidence = 'Low-end comparison only: separate 200-VU proxy stayed within DB guards, with 2 HTTP failures and null exit capture; the same shape failed the 300-VU CPU guard.'
    $capacityEvidenceScope = 'Synthetic lower-concurrency comparison only: VUs are not student records. This shape is rejected for the 300-VU target and is not a production SLO.'
} else {
    $sqlShapeEvidence = 'Caller-supplied supported custom shape; this script infers no workload pass.'
    $capacityEvidenceScope = 'No capacity evidence is attached to this caller-supplied shape; VUs and student-record totals are different dimensions.'
}
$sqlShape = 'db-custom-{0}-{1}' -f $SqlVcpu, [int]$sqlMemoryMib

$hoursPerMonth = 730
$sqlComputeZonal = ($SqlVcpu * $sqlZonalVcpuHourInr + $SqlRamGib * $sqlZonalRamGibHourInr) * $hoursPerMonth
$rejectedTwoVcpuSqlComputeZonal = (2 * $sqlZonalVcpuHourInr + 7.5 * $sqlZonalRamGibHourInr) * $hoursPerMonth
$previousUntestedSqlComputeZonal = (4 * $sqlZonalVcpuHourInr + 15 * $sqlZonalRamGibHourInr) * $hoursPerMonth
$sqlStorageZonal = $provisionedStorageGib * $sqlZonalStorageGibMonthInr
$sqlBackup = $projectedUsedGib * $sqlZonalStorageGibMonthInr
$photoGib = ([decimal]$StudentCount * $AveragePhotoKiB * 1KB * ($PhotoCoveragePercent / 100) * $StoredPhotoVersions) / 1GB
$photoStorageInr = $photoGib * $GcsStandardGibMonthUsd * $ExchangeRateInrPerUsd

$browserActions = [long]$SchoolCount * $StaffPerSchool * $ApiActionsPerStaffDay * $BusinessDaysPerMonth
$containerRequests = [long]($browserActions * 2.2)
$activeSeconds = $containerRequests * 0.30
$modeledCloudRun = ($activeSeconds * ($cloudRunVcpuSecondInr + 0.75 * $cloudRunGibSecondInr)) +
    (($containerRequests / 1000000.0) * $cloudRunMillionRequestsInr)
# Preserve the observed current monthly run-rate and allow for cold starts, reports and jobs.
$cloudRunPlanning = [Math]::Max($CloudRunPlanningFloorInr, $modeledCloudRun * 1.4)

$zonalFixed = $sqlComputeZonal + $DevAndSqlOperationsInr + $cloudRunPlanning + $OtherPlatformInr
$zonalVariable = $sqlStorageZonal + $sqlBackup + $photoStorageInr
$zonalTotal = $zonalFixed + $zonalVariable
$haTotal = ($sqlComputeZonal * 2) + ($sqlStorageZonal * 2) + $sqlBackup +
    $DevAndSqlOperationsInr + $cloudRunPlanning + $OtherPlatformInr + $photoStorageInr
$zonalLargeSchoolAllocated = ($zonalFixed / $SchoolCount) +
    (($zonalVariable / $StudentCount) * $LargeSchoolStudents)
$haFixed = ($sqlComputeZonal * 2) + $DevAndSqlOperationsInr + $cloudRunPlanning + $OtherPlatformInr
$haVariable = ($sqlStorageZonal * 2) + $sqlBackup + $photoStorageInr
$haLargeSchoolAllocated = ($haFixed / $SchoolCount) +
    (($haVariable / $StudentCount) * $LargeSchoolStudents)

[pscustomobject]@{
    RateSnapshotDate = '2026-08-11'
    ExchangeRateInrPerUsd = $ExchangeRateInrPerUsd
    CloudRunVcpuSecondUsd = $CloudRunVcpuSecondUsd
    CloudRunGibSecondUsd = $CloudRunGibSecondUsd
    CloudRunMillionRequestsUsd = $CloudRunMillionRequestsUsd
    GcsStandardGibMonthUsd = $GcsStandardGibMonthUsd
    Schools = $SchoolCount
    Students = $StudentCount
    LargeSchoolStudents = $LargeSchoolStudents
    PeakAverageStudentsPerSchool = [Math]::Round($StudentCount / [double]$SchoolCount)
    AttendanceRowsPerYear = $attendanceRowsPerYear
    AttendanceGiBPerYearEstimate = [Math]::Round($attendanceGibPerYear, 1)
    ThreeYearUsedGiBEstimate = [Math]::Round($projectedUsedGib, 1)
    RecommendedProvisionedGiB = $provisionedStorageGib
    PlannedSqlShape = $sqlShape
    PlannedSqlVcpu = $SqlVcpu
    PlannedSqlRamGib = $SqlRamGib
    SqlShapeEvidence = $sqlShapeEvidence
    CapacityEvidenceScope = $capacityEvidenceScope
    RejectedTwoVcpuShape = 'db-custom-2-7680 (failed the 300-VU CPU guard at 83.52%; separate 200-VU comparison is not target certification)'
    RejectedTwoVcpuZonalSqlComputeInr = [Math]::Round($rejectedTwoVcpuSqlComputeZonal, 2)
    ZonalSqlComputeIncrementVsRejectedTwoVcpuInr = [Math]::Round($sqlComputeZonal - $rejectedTwoVcpuSqlComputeZonal, 2)
    RegionalHaSqlComputeIncrementVsRejectedTwoVcpuInr = [Math]::Round(2 * ($sqlComputeZonal - $rejectedTwoVcpuSqlComputeZonal), 2)
    PreviousUntestedFourVcpu15GibZonalSqlComputeInr = [Math]::Round($previousUntestedSqlComputeZonal, 2)
    ZonalSqlComputeSavingsVsPreviousUntestedFourVcpu15GibInr = [Math]::Round($previousUntestedSqlComputeZonal - $sqlComputeZonal, 2)
    RegionalHaSqlComputeSavingsVsPreviousUntestedFourVcpu15GibInr = [Math]::Round(2 * ($previousUntestedSqlComputeZonal - $sqlComputeZonal), 2)
    MonthlyContainerRequests = $containerRequests
    EstimatedPhotoGiB = [Math]::Round($photoGib, 2)
    EstimatedPhotoStorageInr = [Math]::Round($photoStorageInr)
    PhotoStorageRateAssumption = ('USD {0}/GiB-month (Delhi planning input; overrideable, verify current SKU)' -f $GcsStandardGibMonthUsd)
    StoredPhotoVersions = $StoredPhotoVersions
    CloudRunMonthlyPlanningInr = [Math]::Round($cloudRunPlanning)
    ZonalSqlComputeInr = [Math]::Round($sqlComputeZonal)
    ZonalSqlStorageAndBackupInr = [Math]::Round($sqlStorageZonal + $sqlBackup)
    ZonalPlatformMonthlyInr = [Math]::Round($zonalTotal)
    ZonalAveragePerSchoolMonthlyInr = [Math]::Round($zonalTotal / $SchoolCount, 2)
    ZonalPerStudentMonthlyInr = [Math]::Round($zonalTotal / $StudentCount, 4)
    ZonalLargeSchoolAllocatedMonthlyInr = [Math]::Round($zonalLargeSchoolAllocated, 2)
    ZonalPlanningRangeInr = ('{0:N0} - {1:N0}' -f ($zonalTotal * 0.8), ($zonalTotal * 1.25))
    RegionalHaPlatformMonthlyInr = [Math]::Round($haTotal)
    RegionalHaAveragePerSchoolMonthlyInr = [Math]::Round($haTotal / $SchoolCount, 2)
    RegionalHaPerStudentMonthlyInr = [Math]::Round($haTotal / $StudentCount, 4)
    RegionalHaLargeSchoolAllocatedMonthlyInr = [Math]::Round($haLargeSchoolAllocated, 2)
    RegionalHaPlanningRangeInr = ('{0:N0} - {1:N0}' -f ($haTotal * 0.8), ($haTotal * 1.25))
    AllocationMethod = 'Shared compute/operations allocated equally per school; SQL data/backup and modeled photo storage allocated per student.'
    Sources = 'Billing-export SKU snapshot (including Delhi GCS SKU B219-7161-A1AF) plus https://cloud.google.com/sql/pricing, https://docs.cloud.google.com/sql/docs/postgres/machine-series-overview, https://cloud.google.com/run/pricing, https://cloud.google.com/storage/pricing'
    Excludes = 'SMS/WhatsApp provider, taxes, support, domains, staff, photo operations/egress, extraordinary egress, free tiers and promotional credits'
} | Format-List

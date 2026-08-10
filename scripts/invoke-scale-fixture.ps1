param(
    [ValidateSet("Status", "Diagnostics", "QueryPlans", "Seed", "Cleanup")]
    [string]$Action = "Status",
    [ValidateSet("dev")]
    [string]$Environment = "dev",
    [string]$Project = "custoking",
    [string]$Region = "asia-south2",
    [string]$InstanceName = "custoking-db-dev",
    [string]$Database = "custoking_dev",
    [string]$DbUser = "appuser",
    [string]$PasswordSecret = "db-password-dev",
    [string]$Network = "default",
    [string]$Subnet = "default",
    [long]$BaseSchoolId = 900000000,
    [ValidateRange(1, 500)]
    [int]$SchoolCount = 100,
    [ValidateRange(1, 1000000)]
    [int]$TotalStudents = 300000,
    [ValidateRange(0, 1000000)]
    [int]$LargeSchoolStudents = 10000,
    [string]$AcademicYearId = "2026-27",
    [switch]$AllowScaleWrites,
    [string]$OutputJson = "artifacts/scale-fixture-result.json",
    [string]$Gcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
)

$ErrorActionPreference = "Stop"
$jobName = "ims-scale-fixture-$Environment"
$startedAt = Get-Date

if ($Action -in @("Seed", "Cleanup") -and -not $AllowScaleWrites) {
    throw "-$Action modifies the dev database. Pass -AllowScaleWrites after reviewing the reserved scale tenant range."
}
if ($BaseSchoolId -lt 900000000) {
    throw "BaseSchoolId must remain inside the reserved synthetic range (>= 900000000)."
}
if ($LargeSchoolStudents -gt $TotalStudents) {
    throw "LargeSchoolStudents cannot exceed TotalStudents."
}
if ($SchoolCount -eq 1 -and $LargeSchoolStudents -ne $TotalStudents) {
    throw "A single-school fixture requires LargeSchoolStudents to equal TotalStudents."
}
if ($AcademicYearId -notmatch '^[A-Za-z0-9._-]{1,64}$') {
    throw "AcademicYearId contains unsupported characters."
}

function Ensure-ScaleJob {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Gcloud run jobs describe $jobName --project=$Project --region=$Region *> $null
        $exists = $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    $verb = if ($exists) { "update" } else { "create" }
    & $Gcloud run jobs $verb $jobName `
            --project=$Project `
            --region=$Region `
            --image=postgres:16-alpine `
            --command=sh `
            --set-env-vars=PGSSLMODE=disable `
            --set-secrets=PGPASSWORD="${PasswordSecret}:latest" `
            --network=$Network `
            --subnet=$Subnet `
            --vpc-egress=private-ranges-only `
            --max-retries=0 `
            --task-timeout=3600 `
            --tasks=1 | Write-Output
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create Cloud Run job $jobName."
    }
}

$instance = ((& $Gcloud sql instances describe $InstanceName `
    --project=$Project `
    --format=json) -join "`n") | ConvertFrom-Json
$privateIp = @($instance.ipAddresses | Where-Object { $_.type -eq "PRIVATE" }) |
    Select-Object -First 1
$hostAddress = [string]$privateIp.ipAddress
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($hostAddress)) {
    throw "Could not resolve the private address for $InstanceName."
}

switch ($Action) {
    "Seed" {
        $sqlPath = Join-Path $PSScriptRoot "..\load-tests\sql\seed-scale-fleet.sql"
        $sql = Get-Content $sqlPath -Raw
        $psqlVariables = "-v base_school_id=$BaseSchoolId -v school_count=$SchoolCount " +
            "-v total_students=$TotalStudents -v large_school_students=$LargeSchoolStudents " +
            "-v academic_year_id=$AcademicYearId"
    }
    "Cleanup" {
        $sqlPath = Join-Path $PSScriptRoot "..\load-tests\sql\cleanup-scale-fleet.sql"
        $sql = Get-Content $sqlPath -Raw
        $psqlVariables = "-v base_school_id=$BaseSchoolId"
    }
    "Diagnostics" {
        $sql = @"
SELECT 'IMS_SCALE_DIAGNOSTICS|' || json_build_object(
    'capturedAt', now(),
    'activity', COALESCE(json_agg(row_to_json(activity_rows)), '[]'::json)
)::text
FROM (
    SELECT pid,
           application_name,
           state,
           wait_event_type,
           wait_event,
           round(extract(epoch FROM (clock_timestamp() - query_start))::numeric, 3) AS query_age_seconds,
           pg_blocking_pids(pid) AS blocking_pids,
           left(regexp_replace(query, '[[:space:]]+', ' ', 'g'), 180) AS query_shape
    FROM pg_stat_activity
    WHERE datname = current_database()
      AND pid <> pg_backend_pid()
      AND state <> 'idle'
    ORDER BY query_start
    LIMIT 100
) activity_rows;

SELECT 'IMS_SCALE_WAITS|' || COALESCE(json_agg(row_to_json(wait_rows)), '[]'::json)::text
FROM (
    SELECT COALESCE(wait_event_type, 'CPU') AS wait_event_type,
           COALESCE(wait_event, 'CPU') AS wait_event,
           count(*) AS backends,
           round(max(extract(epoch FROM (clock_timestamp() - query_start)))::numeric, 3) AS oldest_query_seconds
    FROM pg_stat_activity
    WHERE datname = current_database()
      AND pid <> pg_backend_pid()
      AND state <> 'idle'
    GROUP BY wait_event_type, wait_event
    ORDER BY backends DESC, wait_event_type, wait_event
) wait_rows;
"@
        $psqlVariables = ""
    }
    "QueryPlans" {
        $sqlPath = Join-Path $PSScriptRoot "..\load-tests\sql\capture-long-history-query-plans.sql"
        $sql = Get-Content $sqlPath -Raw
        $psqlVariables = "-v base_school_id=$BaseSchoolId -v academic_year_id=$AcademicYearId"
    }
    default {
        $sql = @"
SELECT 'IMS_SCALE_STATUS|' || json_build_object(
    'schools', (SELECT count(*) FROM tenant_school.schools WHERE id >= $BaseSchoolId AND id < $BaseSchoolId + 10000 AND short_code LIKE 'SCALE-%'),
    'students', (SELECT count(*) FROM student.students WHERE school_id >= $BaseSchoolId AND school_id < $BaseSchoolId + 10000),
    'sections', (SELECT count(*) FROM tenant_school.school_sections WHERE school_id >= $BaseSchoolId AND school_id < $BaseSchoolId + 10000),
    'attendanceRecords', (SELECT count(*) FROM attendance.attendance_student_records WHERE school_id >= $BaseSchoolId AND school_id < $BaseSchoolId + 10000)
)::text;
"@
        $psqlVariables = ""
    }
}

Ensure-ScaleJob
$marker = "IMS_SCALE_JOB_" + ((New-Guid).ToString("n").Substring(0, 10))
$encodedSql = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
$shellScript = "set -euo pipefail; printf '%s' '$encodedSql' | base64 -d > /tmp/scale.sql; " +
    "psql -q -t -A -v ON_ERROR_STOP=1 $psqlVariables -h $hostAddress -U $DbUser -d $Database -f /tmp/scale.sql " +
    "| sed 's/^/$marker|/'"

$accessToken = ((& $Gcloud auth print-access-token) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($accessToken)) {
    throw "Could not obtain a gcloud access token."
}
$headers = @{ Authorization = "Bearer $accessToken" }
$runUri = "https://run.googleapis.com/v2/projects/$Project/locations/$Region/jobs/${jobName}:run"
$body = @{
    overrides = @{
        containerOverrides = @(@{ args = @("-c", $shellScript) })
    }
} | ConvertTo-Json -Depth 8
$operation = Invoke-RestMethod -Uri $runUri -Method Post -Headers $headers `
    -ContentType "application/json" -Body $body -TimeoutSec 60
if ([string]::IsNullOrWhiteSpace([string]$operation.name)) {
    throw "Scale job did not return an operation name."
}

$operationUri = "https://run.googleapis.com/v2/$($operation.name)"
$deadline = (Get-Date).AddMinutes(60)
do {
    Start-Sleep -Seconds 5
    $operation = Invoke-RestMethod -Uri $operationUri -Headers $headers -TimeoutSec 60
} while (-not $operation.done -and (Get-Date) -lt $deadline)
if (-not $operation.done) {
    throw "Timed out waiting for $Action scale fixture job."
}
if ($operation.error) {
    throw "Scale fixture job failed: $($operation.error.message)"
}

$filter = "resource.type=cloud_run_job AND resource.labels.job_name=$jobName AND textPayload:$marker"
$lines = @()
for ($attempt = 1; $attempt -le 15; $attempt++) {
    $lines = @(& $Gcloud logging read $filter --project=$Project --freshness=2h `
        --order=asc --limit=100 --format="value(textPayload)")
    if (@($lines | Where-Object { $_ -like "$marker|*" }).Count -gt 0) { break }
    Start-Sleep -Seconds 2
}
$resultLines = @($lines | Where-Object { $_ -like "$marker|*" } |
    ForEach-Object { $_.Substring(("$marker|").Length) })
if ($resultLines.Count -eq 0) {
    throw "Scale fixture job completed without a result marker."
}

$result = [ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    environment = $Environment
    action = $Action
    instance = $InstanceName
    database = $Database
    baseSchoolId = $BaseSchoolId
    schoolCount = $SchoolCount
    totalStudents = $TotalStudents
    largeSchoolStudents = $LargeSchoolStudents
    durationSeconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 2)
    output = $resultLines
}
$outputDirectory = Split-Path -Parent $OutputJson
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}
$result | ConvertTo-Json -Depth 5 | Set-Content $OutputJson -Encoding UTF8
$result | ConvertTo-Json -Depth 5

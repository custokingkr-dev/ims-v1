param(
    [ValidateSet("Status", "Diagnostics", "QueryPlans", "MixedQueryPlans", "AsyncSeed", "AsyncStatus", "AsyncCleanup", "ScaleBacklogCleanup", "Seed", "LongHistorySeed", "Cleanup")]
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
    [string]$CertificationId = "",
    [switch]$AllowScaleWrites,
    [string]$OutputJson = "artifacts/scale-fixture-result.json",
    [string]$Gcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
)

$ErrorActionPreference = "Stop"
$jobName = "ims-scale-fixture-$Environment"
$startedAt = Get-Date

if ($Action -in @("Seed", "LongHistorySeed", "AsyncSeed", "AsyncCleanup", "ScaleBacklogCleanup", "Cleanup") -and -not $AllowScaleWrites) {
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
if ($Action -in @("AsyncSeed", "AsyncStatus", "AsyncCleanup") -and
    $CertificationId -notmatch '^[A-Za-z0-9._-]{8,80}$') {
    throw "Async certification requires -CertificationId with 8-80 safe characters."
}

function Ensure-ScaleJob {
    $describeOutput = @()
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $describeOutput = @(& $Gcloud run jobs describe $jobName `
            --project=$Project `
            --region=$Region `
            --format=json 2> $null)
        $exists = $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exists) {
        $existingJob = ($describeOutput -join "`n") | ConvertFrom-Json
        $container = $existingJob.spec.template.spec.template.spec.containers[0]
        if ($null -eq $container) {
            $container = $existingJob.spec.template.template.containers[0]
        }
        $currentSslMode = [string](@($container.env | Where-Object {
                    $_.name -eq "PGSSLMODE"
                } | Select-Object -First 1)[0].value)
        if ($currentSslMode.ToLowerInvariant() -ne "require") {
            Write-Host "Reconciling PGSSLMODE=require on existing Cloud Run job $jobName"
            & $Gcloud run jobs update $jobName `
                --project=$Project `
                --region=$Region `
                --update-env-vars=PGSSLMODE=require | Write-Output
            if ($LASTEXITCODE -ne 0) {
                throw "Could not require encrypted PostgreSQL transport on Cloud Run job $jobName."
            }
        }
        return
    }
    if (-not $AllowScaleWrites) {
        throw "Cloud Run job $jobName is missing. Creating infrastructure requires -AllowScaleWrites."
    }
    & $Gcloud run jobs create $jobName `
            --project=$Project `
            --region=$Region `
            --image=postgres:16-alpine `
            --command=sh `
            --set-env-vars=PGSSLMODE=require `
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
        $psqlVariables = "-v base_school_id=$BaseSchoolId -v school_count=$SchoolCount"
    }
    "LongHistorySeed" {
        $sqlPath = Join-Path $PSScriptRoot "..\load-tests\sql\seed-long-history-attendance.sql"
        $sql = Get-Content $sqlPath -Raw
        $psqlVariables = "-v base_school_id=$BaseSchoolId -v academic_year_id=$AcademicYearId"
    }
    "AsyncSeed" {
        $sqlPath = Join-Path $PSScriptRoot "..\load-tests\sql\seed-async-drain-certification.sql"
        $sql = Get-Content $sqlPath -Raw
        $psqlVariables = "-v base_school_id=$BaseSchoolId -v certification_id=$CertificationId"
    }
    "AsyncStatus" {
        $sqlPath = Join-Path $PSScriptRoot "..\load-tests\sql\status-async-drain-certification.sql"
        $sql = Get-Content $sqlPath -Raw
        $psqlVariables = "-v certification_id=$CertificationId"
    }
    "AsyncCleanup" {
        $sqlPath = Join-Path $PSScriptRoot "..\load-tests\sql\cleanup-async-drain-certification.sql"
        $sql = Get-Content $sqlPath -Raw
        $psqlVariables = "-v certification_id=$CertificationId"
    }
    "ScaleBacklogCleanup" {
        $sqlPath = Join-Path $PSScriptRoot "..\load-tests\sql\cleanup-scale-backlog.sql"
        $sql = Get-Content $sqlPath -Raw
        $psqlVariables = "-v base_school_id=$BaseSchoolId -v school_count=$SchoolCount"
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

SELECT 'IMS_SCALE_DATABASE_STATS|' || json_build_object(
    'capturedAt', now(),
    'database', (
        SELECT row_to_json(database_stats)
        FROM (
            SELECT xact_commit, xact_rollback, blks_read, blks_hit,
                   tup_returned, tup_fetched, tup_inserted, tup_updated, tup_deleted,
                   temp_files, temp_bytes, deadlocks, blk_read_time, blk_write_time,
                   sessions, sessions_abandoned, sessions_fatal, sessions_killed,
                   stats_reset
            FROM pg_stat_database
            WHERE datname = current_database()
        ) database_stats
    ),
    'bgwriter', (SELECT row_to_json(bgwriter_stats) FROM pg_stat_bgwriter bgwriter_stats),
    'wal', (SELECT row_to_json(wal_stats) FROM pg_stat_wal wal_stats),
    'tables', COALESCE((
        SELECT json_agg(row_to_json(table_stats) ORDER BY schemaname, relname)
        FROM (
            SELECT schemaname, relname, seq_scan, seq_tup_read, idx_scan, idx_tup_fetch,
                   n_tup_ins, n_tup_upd, n_tup_del, n_tup_hot_upd,
                   n_live_tup, n_dead_tup, n_mod_since_analyze,
                   last_vacuum, last_autovacuum, last_analyze, last_autoanalyze,
                   vacuum_count, autovacuum_count, analyze_count, autoanalyze_count,
                   pg_total_relation_size(relid) AS total_bytes,
                   pg_relation_size(relid) AS heap_bytes,
                   pg_indexes_size(relid) AS index_bytes
            FROM pg_stat_user_tables
            WHERE (schemaname, relname) IN (
                ('attendance', 'attendance_daily'),
                ('attendance', 'attendance_student_records'),
                ('tenant_school', 'outbox_events'),
                ('reporting', 'fact_attendance_daily'),
                ('reporting', 'reporting_event_inbox')
            )
        ) table_stats
    ), '[]'::json),
    'indexes', COALESCE((
        SELECT json_agg(row_to_json(index_stats) ORDER BY schemaname, relname, indexrelname)
        FROM (
            SELECT schemaname, relname, indexrelname, idx_scan, idx_tup_read, idx_tup_fetch,
                   pg_relation_size(indexrelid) AS index_bytes
            FROM pg_stat_user_indexes
            WHERE (schemaname, relname) IN (
                ('attendance', 'attendance_daily'),
                ('attendance', 'attendance_student_records'),
                ('tenant_school', 'outbox_events'),
                ('reporting', 'fact_attendance_daily'),
                ('reporting', 'reporting_event_inbox')
            )
        ) index_stats
    ), '[]'::json),
    'outbox', (
        SELECT json_build_object(
            'rows', count(*),
            'published', count(*) FILTER (WHERE published_at IS NOT NULL),
            'pending', count(*) FILTER (WHERE published_at IS NULL AND dead_lettered_at IS NULL),
            'deadLettered', count(*) FILTER (WHERE dead_lettered_at IS NOT NULL),
            'attendanceEvents', count(*) FILTER (WHERE event_type = 'attendance-daily.upserted.v1'),
            'oldestPendingAt', min(occurred_at) FILTER (
                WHERE published_at IS NULL AND dead_lettered_at IS NULL),
            'newestOccurredAt', max(occurred_at)
        )
        FROM tenant_school.outbox_events
    ),
    'outboxByType', COALESCE((
        SELECT json_agg(row_to_json(type_stats) ORDER BY rows DESC, event_type)
        FROM (
            SELECT event_type,
                   count(*) AS rows,
                   count(*) FILTER (WHERE published_at IS NOT NULL) AS published,
                   count(*) FILTER (
                       WHERE published_at IS NULL AND dead_lettered_at IS NULL) AS pending,
                   count(*) FILTER (WHERE dead_lettered_at IS NOT NULL) AS dead_lettered,
                   min(occurred_at) FILTER (
                       WHERE published_at IS NULL AND dead_lettered_at IS NULL) AS oldest_pending_at,
                   max(occurred_at) AS newest_occurred_at
            FROM tenant_school.outbox_events
            GROUP BY event_type
        ) type_stats
    ), '[]'::json),
    'scaleScope', (
        WITH scale_schools AS (
            SELECT id
            FROM tenant_school.schools
            WHERE id >= $BaseSchoolId
              AND id < $BaseSchoolId + 10000
              AND short_code LIKE 'SCALE-%'
        )
        SELECT json_build_object(
            'schools', (SELECT count(*) FROM scale_schools),
            'outboxRows', (SELECT count(*) FROM tenant_school.outbox_events
                WHERE school_id IN (SELECT id FROM scale_schools)),
            'outboxPending', (SELECT count(*) FROM tenant_school.outbox_events
                WHERE school_id IN (SELECT id FROM scale_schools)
                  AND published_at IS NULL AND dead_lettered_at IS NULL),
            'inboxRows', (SELECT count(*) FROM reporting.reporting_event_inbox
                WHERE school_id IN (SELECT id FROM scale_schools)),
            'inboxByStatus', COALESCE((
                SELECT json_object_agg(status, rows)
                FROM (
                    SELECT status, count(*) AS rows
                    FROM reporting.reporting_event_inbox
                    WHERE school_id IN (SELECT id FROM scale_schools)
                    GROUP BY status
                ) inbox_status
            ), '{}'::json),
            'attendanceFacts', (SELECT count(*) FROM reporting.fact_attendance_daily
                WHERE school_id IN (SELECT id FROM scale_schools))
        )
    )
)::text;

CREATE TEMP TABLE ims_outbox_relay_plan(plan jsonb);
DO `$`$
DECLARE
    plan_row record;
BEGIN
    FOR plan_row IN EXECUTE `$plan`$
        EXPLAIN (COSTS, VERBOSE, FORMAT JSON)
        SELECT o.id::text AS id, o.event_key, o.event_type, o.aggregate_type, o.aggregate_id,
               o.school_id, o.occurred_at, o.payload::text AS payload, o.trace_parent, o.trace_state
        FROM tenant_school.outbox_events o
        WHERE o.published_at IS NULL
          AND o.dead_lettered_at IS NULL
          AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= now())
        ORDER BY o.id
        LIMIT 100
        FOR UPDATE SKIP LOCKED
    `$plan`$
    LOOP
        INSERT INTO ims_outbox_relay_plan(plan) VALUES (plan_row."QUERY PLAN"::jsonb);
    END LOOP;
END `$`$;

SELECT 'IMS_SCALE_OUTBOX_RELAY_PLAN|' || plan::text FROM ims_outbox_relay_plan;
"@
        $psqlVariables = ""
    }
    "QueryPlans" {
        $sqlPath = Join-Path $PSScriptRoot "..\load-tests\sql\capture-long-history-query-plans.sql"
        $sql = Get-Content $sqlPath -Raw
        $psqlVariables = "-v base_school_id=$BaseSchoolId -v academic_year_id=$AcademicYearId"
    }
    "MixedQueryPlans" {
        $sqlPath = Join-Path $PSScriptRoot "..\load-tests\sql\capture-mixed-morning-query-plans.sql"
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

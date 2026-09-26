param([string]$OutputJson)
# Read-only cloud inventory and SELECT queries. Never starts jobs, resizes services,
# writes Monitoring/BQ data, or starts a workload. Local evidence is no-overwrite.
$ErrorActionPreference = 'Stop'
$projectId = 'custoking-dev'
$region = 'asia-south2'
$instanceName = 'custoking-db-dev'
$repoRoot = Split-Path -Parent $PSScriptRoot
if ($OutputJson -and (Test-Path -LiteralPath $OutputJson)) { throw 'Evidence output already exists.' }

function Read-GcloudJson([string[]]$Arguments) {
    $raw = & gcloud.cmd @Arguments "--project=$projectId" --format=json
    if ($LASTEXITCODE -ne 0) { throw 'Read-only gcloud inventory failed; readiness remains unknown.' }
    return (($raw -join "`n") | ConvertFrom-Json) | ForEach-Object { $_ }
}
$instance = Read-GcloudJson @('sql','instances','describe',$instanceName)
$services = @(Read-GcloudJson @('run','services','list',"--region=$region"))
$billing = Read-GcloudJson @('billing','projects','describe',$projectId)
$account = ([string]$billing.billingAccountName) -replace '^billingAccounts/', ''
if (-not $account) { throw 'No dev billing account was returned.' }
$budget = @(Read-GcloudJson @('billing','budgets','list',"--billing-account=$account") | Where-Object displayName -eq 'custoking-dev-monthly')
if ($budget.Count -ne 1 -or $budget[0].amount.specifiedAmount.currencyCode -ne 'INR') { throw 'Exactly one INR dev budget is required.' }
$budgetInr = [double]$budget[0].amount.specifiedAmount.units + ([double]$budget[0].amount.specifiedAmount.nanos / 1000000000)
$sql = ((Get-Content -LiteralPath (Join-Path $PSScriptRoot 'sql/dev-capacity-cost-preflight.sql') |
    Where-Object { -not $_.TrimStart().StartsWith('--') }) -join ' ').Trim()
if (-not $sql.StartsWith('SELECT', [StringComparison]::OrdinalIgnoreCase)) { throw 'Capacity cost probe must remain a read-only SELECT.' }
$costRaw = & bq.cmd query --project_id=custoking-dev --use_legacy_sql=false --format=json --maximum_bytes_billed=104857600 $sql
if ($LASTEXITCODE -ne 0) { throw 'Read-only billing export query failed; no workload may start.' }
$cost = @((($costRaw -join "`n") | ConvertFrom-Json) | ForEach-Object { $_ })
if ($cost.Count -ne 1 -or $cost[0].currency -ne 'INR' -or [long]$cost[0].matching_rows -le 0) { throw 'Require nonempty dev-only INR invoice rows.' }

$readOnlyToken = ((& gcloud.cmd auth print-access-token --project=custoking-dev) -join '').Trim()
if ($LASTEXITCODE -ne 0 -or -not $readOnlyToken) { throw 'Monitoring read authentication unavailable.' }
$observedAt = [datetime]::UtcNow
function Read-Metric([string]$Metric, [datetime]$Start, [string]$ExtraFilter = '', [switch]$Raw) {
    $filter = 'metric.type="' + $Metric + '"' + $ExtraFilter
    $series = @(); $page = ''; $pageCount = 0
    do {
        $pageCount++
        if ($pageCount -gt 10) { throw 'Monitoring pagination exceeded bounded inventory; readiness unknown.' }
        $uri = "https://monitoring.googleapis.com/v3/projects/$projectId/timeSeries?filter=" + [uri]::EscapeDataString($filter) +
            '&interval.startTime=' + [uri]::EscapeDataString($Start.ToString('o')) +
            '&interval.endTime=' + [uri]::EscapeDataString($observedAt.ToString('o')) + '&view=FULL&pageSize=1000'
        if ($Metric -eq 'logging.googleapis.com/billing/monthly_bytes_ingested' -and -not $Raw) {
            # Cumulative month gauge: daily maxima retain the latest month total per
            # resource type while bounding a month's otherwise redundant hourly points.
            $uri += '&aggregation.alignmentPeriod=86400s&aggregation.perSeriesAligner=ALIGN_MAX'
        }
        if ($page) { $uri += '&pageToken=' + [uri]::EscapeDataString($page) }
        $response = Invoke-RestMethod -Uri $uri -Headers @{ Authorization = "Bearer $readOnlyToken" } -TimeoutSec 30
        $series += @($response.timeSeries)
        $page = [string]$response.nextPageToken
    } while ($page)
    return @($series | Where-Object { $null -ne $_ } | ForEach-Object {
        $point = @($_.points | Sort-Object { [datetime]$_.interval.endTime } -Descending | Select-Object -First 1)[0]
        if ($null -eq $point) { throw 'Monitoring series has no value.' }
        [pscustomobject]@{ at=$point.interval.endTime; value=$(if($null -ne $point.value.doubleValue){[double]$point.value.doubleValue}else{[double]$point.value.int64Value}); labels=$_.metric.labels }
    })
}
$databaseFilter = ' AND resource.labels.database_id="custoking-dev:custoking-db-dev"'
$cpu = @(Read-Metric 'cloudsql.googleapis.com/database/cpu/utilization' $observedAt.AddMinutes(-10) $databaseFilter)
$memory = @(Read-Metric 'cloudsql.googleapis.com/database/memory/components' $observedAt.AddMinutes(-10) ($databaseFilter + ' AND metric.labels.component="Usage"'))
$connections = @(Read-Metric 'cloudsql.googleapis.com/database/postgresql/num_backends' $observedAt.AddMinutes(-10) $databaseFilter)
if (-not $cpu.Count -or -not $memory.Count -or -not $connections.Count) { throw 'All database metrics are required; no workload may start.' }
$logging = @(Read-Metric 'logging.googleapis.com/billing/monthly_bytes_ingested' ([datetime]::new($observedAt.Year,$observedAt.Month,1,0,0,0,[datetimekind]::Utc)))
if (-not $logging.Count) { throw 'Logging ingestion metrics are required; no workload may start.' }
# ALIGN_MAX uses bucket boundaries as timestamps, which are not raw sample freshness.
# Inspect a separate bounded two-hour window without alignment; query time is never evidence time.
$loggingRecent = @(Read-Metric 'logging.googleapis.com/billing/monthly_bytes_ingested' $observedAt.AddHours(-2) -Raw)
if (-not $loggingRecent.Count) { throw 'Recent raw Logging samples are required; no workload may start.' }
$loggingSampleAt = @($loggingRecent | Sort-Object { [datetime]$_.at } | Select-Object -First 1)[0].at
$loggingSampleUtc = ([datetimeoffset]$loggingSampleAt).UtcDateTime
if ($loggingSampleUtc -lt $observedAt.AddHours(-2) -or $loggingSampleUtc -gt $observedAt) {
    throw 'Raw Logging sample timestamp is stale or in the future; no workload may start.'
}
function Billing-Utc([string]$Value) {
    return [datetime]::SpecifyKind([datetime]::Parse($Value,[Globalization.CultureInfo]::InvariantCulture),[datetimekind]::Utc).ToString('o')
}
$result = [ordered]@{
    mode='READ_ONLY_NO_LOAD'; capturedAt=$observedAt.ToString('o'); project=$projectId; instance=$instanceName
    billing=@{ source='detailed-billing-export'; sourceTable='custoking-prod.billing_export.gcp_billing_export_resource_v1_014C0A_C6B9AF_5FABC0'; scopeProject=$projectId; currency='INR'; grossMonthInr=[double]$cost[0].gross_cost_inr; budgetInr=$budgetInr; estimatedRunInr=15; latestExportAt=(Billing-Utc $cost[0].latest_export_time); latestUsageAt=(Billing-Utc $cost[0].latest_usage_end) }
    logging=@{ monthGib=[math]::Round([double](($logging | Measure-Object value -Sum).Sum)/[math]::Pow(1024,3),6); estimatedRunGib=0.01; observedAt=$loggingSampleAt; freshnessSource='raw-monthly-bytes-samples-in-last-two-hours'; rawSeriesCount=$loggingRecent.Count }
    database=@{ state=$instance.state; tier=$instance.settings.tier; maxConnections=@($instance.settings.databaseFlags | Where-Object name -eq 'max_connections')[0].value; cpuRatio=($cpu | Measure-Object value -Maximum).Maximum; memoryUsagePercent=($memory | Measure-Object value -Maximum).Maximum; connections=($connections | Measure-Object value -Sum).Sum; observedAt=(@($cpu + $memory + $connections | Sort-Object { [datetime]$_.at } | Select-Object -First 1)[0].at) }
    fixture=@{ schoolId=$null; verifiedSynthetic=$false; reason='Not inspected by this cloud-metadata-only collector; require a read-only reserved-fixture status check.' }
    services=@($services | ForEach-Object { @{ name=$_.metadata.name; revision=$_.status.latestReadyRevisionName; maxInstances=$_.spec.template.metadata.annotations.'autoscaling.knative.dev/maxScale'; minInstances=$_.spec.template.metadata.annotations.'autoscaling.knative.dev/minScale'; concurrency=$_.spec.template.spec.containerConcurrency; resources=$_.spec.template.spec.containers[0].resources.limits } })
    estimatedRunNote='INR15 and0.01GiB are conservative planning allowances for the bounded240-read probe, not measured prices. No cost or logging override is supported.'
    secretsPersisted=$false; workloadStarted=$false
}
$json = $result | ConvertTo-Json -Depth 8
if ($OutputJson) {
    $absolute = [IO.Path]::GetFullPath($OutputJson)
    $parent = Split-Path -Parent $absolute
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent | Out-Null }
    $stream = [IO.File]::Open($absolute,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
    try { $bytes=[Text.Encoding]::UTF8.GetBytes($json); $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
}
$json

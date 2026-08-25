param(
    [Parameter(Mandatory = $true)][string]$ProjectId,
    [string]$Dataset = "billing_export",
    [string]$ScopeProject = $ProjectId,
    [string]$QueryProject = $ProjectId,
    [string]$OutputJson = "artifacts/billing-export-health.json",
    [string]$OutputMarkdown = "artifacts/billing-export-health.md",
    [string]$BqPath = "bq",
    [string]$MockInventoryJson = "",
    [string]$MockFreshnessJson = ""
)

$ErrorActionPreference = "Stop"

foreach ($value in @($ProjectId, $ScopeProject, $QueryProject)) {
    if ($value -notmatch '^[a-z][a-z0-9-]{4,61}[a-z0-9]$') {
        throw "Invalid Google Cloud project id: $value"
    }
}
if ($Dataset -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
    throw "Invalid BigQuery dataset id: $Dataset"
}

function Invoke-BqJson([string]$Sql) {
    # The Windows bq.cmd shim splits a multiline positional query at whitespace. Supplying SQL on stdin
    # preserves it as one query and works identically with the native Unix launcher.
    $output = $Sql | & $BqPath query --project_id=$QueryProject --nouse_legacy_sql --format=json --quiet 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "BigQuery query failed: $(($output -join "`n").Trim())"
    }
    $text = ($output -join "`n").Trim()
    if ([string]::IsNullOrWhiteSpace($text)) { return @() }
    $start = $text.IndexOf('[')
    $end = $text.LastIndexOf(']')
    if ($start -lt 0 -or $end -lt $start) {
        throw "BigQuery returned a response that did not contain a JSON array"
    }
    return @($text.Substring($start, $end - $start + 1) | ConvertFrom-Json)
}

$inventorySql = @"
SELECT
  COUNTIF(STARTS_WITH(table_name, 'gcp_billing_export_v1_')) AS standard_table_count,
  COUNTIF(STARTS_WITH(table_name, 'gcp_billing_export_resource_v1_')) AS detailed_table_count,
  COUNTIF(STARTS_WITH(table_name, 'cloud_pricing_export')) AS pricing_table_count,
  ARRAY_AGG(table_name ORDER BY table_name) AS table_names
FROM ``$ProjectId.$Dataset.INFORMATION_SCHEMA.TABLES``
"@

$inventoryRows = if ($MockInventoryJson) {
    @(Get-Content -Raw -LiteralPath $MockInventoryJson | ConvertFrom-Json)
} else {
    @(Invoke-BqJson $inventorySql)
}
$inventory = @($inventoryRows)[0]
if ($null -eq $inventory) { throw "Billing export inventory query returned no row" }

$standardCount = [int]$inventory.standard_table_count
$detailedCount = [int]$inventory.detailed_table_count
$pricingCount = [int]$inventory.pricing_table_count
$gradeCode = if ($detailedCount -gt 0) { 2 } elseif ($standardCount -gt 0) { 1 } else { 0 }
$grade = @("ESTIMATED_ONLY", "INVOICE_GRADE_STANDARD", "INVOICE_GRADE_DETAILED")[$gradeCode]
$prefix = if ($detailedCount -gt 0) {
    "gcp_billing_export_resource_v1_"
} elseif ($standardCount -gt 0) {
    "gcp_billing_export_v1_"
} else { $null }

$freshness = $null
if ($prefix) {
    $safeScope = $ScopeProject.Replace("'", "''")
    $freshnessSql = @"
SELECT
  COUNT(*) AS row_count,
  MAX(export_time) AS latest_export_time,
  MAX(usage_end_time) AS latest_usage_end,
  ROUND(TIMESTAMP_DIFF(CURRENT_TIMESTAMP(), MAX(export_time), MINUTE) / 60.0, 2) AS export_lag_hours,
  ROUND(TIMESTAMP_DIFF(CURRENT_TIMESTAMP(), MAX(usage_end_time), MINUTE) / 60.0, 2) AS usage_lag_hours,
  ROUND(SUM(IF(DATE(usage_start_time) >= DATE_TRUNC(CURRENT_DATE(), MONTH), cost, 0)), 4) AS gross_mtd,
  ROUND(SUM(IF(DATE(usage_start_time) >= DATE_TRUNC(CURRENT_DATE(), MONTH),
    cost + IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0), 0)), 4) AS net_mtd,
  ARRAY_AGG(DISTINCT currency IGNORE NULLS ORDER BY currency) AS currencies
FROM ``$ProjectId.$Dataset.${prefix}*``
WHERE project.id = '$safeScope'
  AND DATE(usage_start_time) >= DATE_SUB(CURRENT_DATE(), INTERVAL 62 DAY)
"@
    $freshnessRows = if ($MockFreshnessJson) {
        @(Get-Content -Raw -LiteralPath $MockFreshnessJson | ConvertFrom-Json)
    } else {
        @(Invoke-BqJson $freshnessSql)
    }
    $freshness = @($freshnessRows)[0]
}

$rowCount = if ($freshness) { [long]$freshness.row_count } else { 0L }
$exportLag = if ($freshness -and $null -ne $freshness.export_lag_hours) {
    [double]$freshness.export_lag_hours
} else { $null }
$freshnessStatus = if ($gradeCode -eq 0) {
    "UNAVAILABLE"
} elseif ($rowCount -eq 0) {
    "NO_MATCHING_PROJECT_ROWS"
} elseif ($exportLag -le 24) {
    "FRESH"
} else {
    "DELAYED"
}

$report = [ordered]@{
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
    readOnly = $true
    projectId = $ProjectId
    dataset = $Dataset
    scopeProject = $ScopeProject
    grade = $grade
    gradeCode = $gradeCode
    invoiceGrade = ($gradeCode -gt 0)
    resourceLevelDetail = ($gradeCode -eq 2)
    freshnessStatus = $freshnessStatus
    inventory = [ordered]@{
        standardTableCount = $standardCount
        detailedTableCount = $detailedCount
        pricingTableCount = $pricingCount
        tableNames = @($inventory.table_names)
    }
    freshness = if ($freshness) { [ordered]@{
        rowCount = $rowCount
        latestExportTime = $freshness.latest_export_time
        latestUsageEnd = $freshness.latest_usage_end
        exportLagHours = $exportLag
        usageLagHours = if ($null -ne $freshness.usage_lag_hours) { [double]$freshness.usage_lag_hours } else { $null }
        grossMonthToDate = if ($null -ne $freshness.gross_mtd) { [double]$freshness.gross_mtd } else { $null }
        netMonthToDate = if ($null -ne $freshness.net_mtd) { [double]$freshness.net_mtd } else { $null }
        currencies = @($freshness.currencies)
    } } else { $null }
    interpretation = if ($gradeCode -eq 0) {
        "Only estimator/pricing evidence is available. It is not invoice-grade and must not be presented as billed cost."
    } elseif ($gradeCode -eq 1) {
        "Standard usage export is invoice-grade for project/service/SKU cost reporting, subject to export freshness."
    } else {
        "Detailed usage export is invoice-grade and includes resource-level attribution, subject to export freshness."
    }
    externalActionRequired = ($gradeCode -eq 0)
    externalAction = if ($gradeCode -eq 0) {
        "A Billing Account Administrator or Billing Account Costs Manager must enable standard and detailed BigQuery usage exports. New exports do not backfill earlier usage."
    } else { $null }
}

$jsonParent = Split-Path -Parent $OutputJson
$markdownParent = Split-Path -Parent $OutputMarkdown
if ($jsonParent) { New-Item -ItemType Directory -Force -Path $jsonParent | Out-Null }
if ($markdownParent) { New-Item -ItemType Directory -Force -Path $markdownParent | Out-Null }
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputJson -Encoding utf8

$freshnessText = if ($freshness) {
    "Rows for scope: $rowCount; export lag: $($report.freshness.exportLagHours)h; usage lag: $($report.freshness.usageLagHours)h."
} else { "No invoice-grade usage table is available." }
$markdown = @"
# Billing export health

Generated: $($report.generatedAtUtc)

| Field | Value |
| --- | --- |
| Read project/dataset | ``$ProjectId.$Dataset`` |
| Scope project | ``$ScopeProject`` |
| Evidence grade | **$grade** ($gradeCode) |
| Freshness | **$freshnessStatus** |
| Standard usage tables | $standardCount |
| Detailed usage tables | $detailedCount |
| Pricing tables | $pricingCount |

$freshnessText

$($report.interpretation)

This report is generated by a read-only query. Grade `0` estimates are operational run-rate indicators,
not billed amounts. Grades `1` and `2` come from Cloud Billing usage export and remain subject to delivery lag.
"@
$markdown | Set-Content -LiteralPath $OutputMarkdown -Encoding utf8

Write-Host "Billing export health: grade=$grade freshness=$freshnessStatus standard=$standardCount detailed=$detailedCount pricing=$pricingCount"
Write-Host "JSON: $OutputJson"
Write-Host "Markdown: $OutputMarkdown"

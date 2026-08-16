<#
.SYNOPSIS
Reports Artifact Registry egress from the detailed Cloud Billing export.

.DESCRIPTION
Pulling an image into Cloud Run in the same region is free. Pulling it to a GitHub-hosted runner
leaves Google's network and is billed as internet egress, so CI image scanning shows up here rather
than in Cloud Run. This line item was exactly zero until exact-digest Trivy scanning landed on
2026-08-11 and then reached INR 323/day; the digest-keyed verdict cache added on 2026-08-16 is meant
to hold it down. This report exists so a regression is visible on the daily cost-control run instead
of being found weeks later in an invoice.

Reports gross cost. Net is meaningless while promotional credits are active.
#>
param(
  [string]$ProjectId = "custoking",
  [string]$BillingTable = "custoking.billing_export.gcp_billing_export_v1_018AC9_E669C1_2FC9B8",
  [ValidateRange(1, 90)]
  [int]$Days = 14,
  # A single uncached release run pulls all seven images, roughly 2 GB / INR 21. Warn near seven
  # such runs in a day, which indicates the cache stopped being effective rather than a busy day.
  [double]$WarnDailyInr = 150,
  [string]$SummaryPath = $env:GITHUB_STEP_SUMMARY
)

$ErrorActionPreference = "Stop"
$BqCommand = if ($env:OS -eq "Windows_NT") { "bq.cmd" } else { "bq" }

if ($BillingTable -notmatch '^[A-Za-z0-9_\-]+\.[A-Za-z0-9_]+\.[A-Za-z0-9_]+$') {
  throw "BillingTable must be project.dataset.table, got '$BillingTable'."
}

# Kept on one line deliberately. A multi-line query passed as an argument is truncated by the
# bq.cmd batch wrapper on Windows, which fails with "Unexpected end of script".
$query = "SELECT FORMAT_DATE('%Y-%m-%d', DATE(usage_start_time)) AS day, " +
  "ROUND(SUM(usage.amount) / 1e9, 2) AS gb, ROUND(SUM(cost), 2) AS inr " +
  "FROM ``$BillingTable`` " +
  "WHERE service.description = 'Artifact Registry' AND sku.description LIKE '%Egress%' " +
  "AND DATE(usage_start_time) >= LEAST(DATE_SUB(CURRENT_DATE(), INTERVAL $Days DAY), " +
  "DATE_TRUNC(CURRENT_DATE(), MONTH)) GROUP BY day ORDER BY day DESC"

# --headless suppresses the interactive first-run setup on a fresh runner. Without it bq prints a
# "Welcome to BigQuery!" banner to stdout ahead of the payload.
$raw = & $BqCommand --headless query "--project_id=$ProjectId" --use_legacy_sql=false --format=json --quiet $query
if ($LASTEXITCODE -ne 0) {
  throw "BigQuery query failed. Confirm the cost-control identity has roles/bigquery.jobUser on '$ProjectId' and read access to the billing export dataset."
}

# bq can still emit banners or warnings on stdout ahead of the payload, which is not valid JSON.
# Belt and braces alongside --headless: drop everything before the first line that opens the array
# or object. Do not trust the whole stream to be JSON just because the command succeeded.
$jsonLines = @($raw)
$startIndex = -1
for ($i = 0; $i -lt $jsonLines.Count; $i++) {
  $trimmed = ([string]$jsonLines[$i]).TrimStart()
  if ($trimmed.StartsWith("[") -or $trimmed.StartsWith("{")) { $startIndex = $i; break }
}
if ($startIndex -gt 0) {
  Write-Host "Discarded $startIndex non-JSON line(s) from bq output before parsing."
}

$rows = @()
if ($startIndex -ge 0) {
  $payload = ($jsonLines[$startIndex..($jsonLines.Count - 1)]) -join "`n"
  if (-not [string]::IsNullOrWhiteSpace($payload)) {
    # Windows PowerShell 5.1 emits a JSON array as one object rather than enumerating it, so a bare
    # @(ConvertFrom-Json) yields an array containing an array. Piping normalises both hosts.
    $parsed = $payload | ConvertFrom-Json
    $rows = @($parsed | ForEach-Object { $_ })
  }
}

$monthStart = (Get-Date).ToUniversalTime().ToString("yyyy-MM-01")
$recent = @($rows | Where-Object { $_.day -ge (Get-Date).ToUniversalTime().AddDays(-$Days).ToString("yyyy-MM-dd") })
$monthToDate = @($rows | Where-Object { $_.day -ge $monthStart } | ForEach-Object { [double]$_.inr } | Measure-Object -Sum).Sum
if ($null -eq $monthToDate) { $monthToDate = 0 }

$lines = @()
$lines += "## Artifact Registry egress"
$lines += ""
if ($recent.Count -eq 0) {
  $lines += "No egress in the last $Days days. Egress only appears on days that ran a release, so this is not by itself evidence the cache is working."
} else {
  $lines += "| Day | GB | INR |"
  $lines += "| --- | ---: | ---: |"
  foreach ($row in $recent) {
    $lines += "| $($row.day) | $($row.gb) | $($row.inr) |"
  }
}
$lines += ""
$lines += "Month to date: **INR $([math]::Round($monthToDate, 2))**."
$lines += ""
$lines += "A single uncached release pulls all seven images, about 2 GB / INR 21. Judge this per release run, not per day: a quiet day is cheap regardless of whether the cache works."

$breaches = @($recent | Where-Object { [double]$_.inr -gt $WarnDailyInr })
foreach ($breach in $breaches) {
  Write-Host "::warning title=Artifact Registry egress::$($breach.day) cost INR $($breach.inr) ($($breach.gb) GB), above the INR $WarnDailyInr guardrail. Check whether the Trivy verdict cache is still being reused; see release-evidence/trivy/exact-digest-scan.json cachedCount."
}
if ($breaches.Count -gt 0) {
  $lines += ""
  # Backticks are doubled so they survive PowerShell string escaping and reach the summary as
  # markdown code spans. A single backtick before r or n would emit a control character instead.
  $lines += "> **$($breaches.Count) day(s) above the INR $WarnDailyInr guardrail.** Check ``cachedCount`` in a recent run's ``release-evidence/trivy/exact-digest-scan.json``; a zero there means verdicts are not being reused."
}

$report = $lines -join "`n"
Write-Host $report

if (-not [string]::IsNullOrWhiteSpace($SummaryPath)) {
  Add-Content -LiteralPath $SummaryPath -Value $report
}

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "../..")
$script = Join-Path $root "scripts/report-billing-export-health.ps1"
$fixtures = Join-Path $PSScriptRoot "fixtures"
$output = Join-Path ([System.IO.Path]::GetTempPath()) ("billing-export-health-test-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $output | Out-Null
try {
    & $script -ProjectId "custoking-prod" `
        -MockInventoryJson (Join-Path $fixtures "billing-export-pricing-only.json") `
        -OutputJson (Join-Path $output "pricing.json") `
        -OutputMarkdown (Join-Path $output "pricing.md")
    $pricing = Get-Content -Raw (Join-Path $output "pricing.json") | ConvertFrom-Json
    if ($pricing.grade -ne "ESTIMATED_ONLY" -or $pricing.invoiceGrade -or -not $pricing.externalActionRequired) {
        throw "Pricing-only dataset was not classified as estimated-only"
    }

    & $script -ProjectId "custoking-prod" `
        -MockInventoryJson (Join-Path $fixtures "billing-export-detailed.json") `
        -MockFreshnessJson (Join-Path $fixtures "billing-export-fresh.json") `
        -OutputJson (Join-Path $output "detailed.json") `
        -OutputMarkdown (Join-Path $output "detailed.md")
    $detailed = Get-Content -Raw (Join-Path $output "detailed.json") | ConvertFrom-Json
    if ($detailed.grade -ne "INVOICE_GRADE_DETAILED" -or -not $detailed.invoiceGrade `
        -or -not $detailed.resourceLevelDetail -or $detailed.freshnessStatus -ne "FRESH") {
        throw "Detailed export was not classified as fresh invoice-grade data"
    }
    if ($detailed.freshness.grossMonthToDate -ne 123.45 -or $detailed.freshness.exportLagHours -ne 2.5) {
        throw "Detailed export freshness or cost fields were not preserved"
    }

    & $script -ProjectId "custoking-prod" `
        -MockInventoryJson (Join-Path $fixtures "billing-export-detailed.json") `
        -MockDetailedFreshnessJson (Join-Path $fixtures "billing-export-empty.json") `
        -MockStandardFreshnessJson (Join-Path $fixtures "billing-export-fresh.json") `
        -OutputJson (Join-Path $output "standard-fallback.json") `
        -OutputMarkdown (Join-Path $output "standard-fallback.md")
    $fallback = Get-Content -Raw (Join-Path $output "standard-fallback.json") | ConvertFrom-Json
    if ($fallback.grade -ne "INVOICE_GRADE_STANDARD" -or $fallback.gradeCode -ne 1 `
        -or $fallback.capabilityGradeCode -ne 2 -or $fallback.resourceLevelDetail `
        -or $fallback.freshnessStatus -ne "FRESH" `
        -or $fallback.selectedUsageTablePrefix -ne "gcp_billing_export_v1_") {
        throw "Empty detailed export did not fall back to populated standard invoice-grade data"
    }

    & $script -ProjectId "custoking-prod" `
        -MockInventoryJson (Join-Path $fixtures "billing-export-detailed.json") `
        -MockDetailedFreshnessJson (Join-Path $fixtures "billing-export-stale.json") `
        -MockStandardFreshnessJson (Join-Path $fixtures "billing-export-fresh.json") `
        -OutputJson (Join-Path $output "fresh-standard.json") `
        -OutputMarkdown (Join-Path $output "fresh-standard.md")
    $freshStandard = Get-Content -Raw (Join-Path $output "fresh-standard.json") | ConvertFrom-Json
    if ($freshStandard.gradeCode -ne 1 -or $freshStandard.freshness.exportLagHours -ne 2.5 `
        -or $freshStandard.selectedUsageTablePrefix -ne "gcp_billing_export_v1_") {
        throw "Stale detailed backfill replaced fresher standard billing data"
    }

    & $script -ProjectId "custoking-prod" `
        -MockInventoryJson (Join-Path $fixtures "billing-export-standard.json") `
        -MockFreshnessJson (Join-Path $fixtures "billing-export-fresh-delivery-stale-usage.json") `
        -OutputJson (Join-Path $output "stale-usage.json") `
        -OutputMarkdown (Join-Path $output "stale-usage.md")
    $staleUsage = Get-Content -Raw (Join-Path $output "stale-usage.json") | ConvertFrom-Json
    if ($staleUsage.freshnessStatus -ne "DELAYED" -or $staleUsage.freshness.exportLagHours -ne 2 `
        -or $staleUsage.freshness.usageLagHours -ne 50) {
        throw "Fresh delivery incorrectly hid stale billing usage coverage"
    }

    $mockIsolationWorked = $false
    try {
        & $script -ProjectId "custoking-prod" `
            -MockInventoryJson (Join-Path $fixtures "billing-export-detailed.json") `
            -MockDetailedFreshnessJson (Join-Path $fixtures "billing-export-empty.json") `
            -OutputJson (Join-Path $output "invalid-mock.json") `
            -OutputMarkdown (Join-Path $output "invalid-mock.md") 6>&1 | Out-Null
    } catch {
        $mockIsolationWorked = $_.Exception.Message -eq `
            "Candidate-specific mock mode requires a freshness fixture for every configured export table."
    }
    if (-not $mockIsolationWorked) {
        throw "Candidate-specific mock mode silently mixed fixtures with live BigQuery"
    }
    Write-Host "billing export health report tests passed"
} finally {
    Remove-Item -LiteralPath $output -Recurse -Force -ErrorAction SilentlyContinue
}

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
    Write-Host "billing export health report tests passed"
} finally {
    Remove-Item -LiteralPath $output -Recurse -Force -ErrorAction SilentlyContinue
}

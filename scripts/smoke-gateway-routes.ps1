param(
    [string]$GatewayBaseUrl = "http://localhost",
    [int]$TimeoutSeconds = 15
)

$ErrorActionPreference = "Stop"

$routes = @(
    "/gateway-health",
    "/notification-api/v1/notifications/sender-profiles/default",
    "/identity-api/v1/rbac/roles",
    "/identity-api/v1/rbac/permissions",
    "/tenant-api/v1/schools",
    "/tenant-api/v1/schools/1",
    "/tenant-api/v1/schools/1/sections",
    "/tenant-api/v1/classes",
    "/tenant-api/v1/sections",
    "/tenant-api/v1/academic-years",
    "/tenant-api/v1/superadmin/schools/stats",
    "/student-api/v1/students?schoolId=1&limit=5",
    "/student-api/v1/students/1",
    "/student-api/v1/students/workspace?schoolId=1&page=0&size=5",
    "/student-api/v1/students/imports/batches?limit=5",
    "/attendance-api/v1/attendance/daily-summary?schoolId=1&date=2026-02-02",
    "/fee-api/v1/fees/bands?schoolId=1",
    "/fee-api/v1/fees/items?schoolId=1",
    "/fee-api/v1/fees/structure?schoolId=1",
    "/fee-api/v1/fees/dashboard/module?schoolId=1",
    "/catalog-api/v1/catalog/orders?schoolId=1&limit=1",
    "/workflow-api/v1/workflows/definitions",
    "/workflow-api/v1/workflows/instances?schoolId=1&limit=1",
    "/firefighting-api/v1/ff/requests?schoolId=1&limit=1",
    "/billing-api/v1/billing/sa/invoices?limit=1",
    "/reporting-api/v1/reporting/summary",
    "/reporting-api/v1/reporting/fee-defaulters?schoolId=1&page=0&size=5",
    "/reporting-api/v1/reporting/low-attendance/sections?schoolId=1&date=2026-02-02",
    "/reporting-api/v1/reporting/vendor-dues?schoolId=1",
    "/audit-api/v1/audit/events?limit=1"
)

$failures = @()

foreach ($route in $routes) {
    $uri = "$GatewayBaseUrl$route"
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec $TimeoutSeconds
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
            $failures += "$route -> $($response.StatusCode)"
        }
    } catch {
        $statusCode = $null
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        $failures += "$route -> $statusCode $($_.Exception.Message)"
    }
}

Write-Host "Gateway route smoke total=$($routes.Count) failures=$($failures.Count)"

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "  $_" }
    exit 1
}

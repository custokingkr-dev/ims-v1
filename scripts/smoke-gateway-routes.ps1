param(
    [string]$GatewayBaseUrl = "http://localhost",
    [int]$TimeoutSeconds = 15
)

$ErrorActionPreference = "Stop"

$routes = @(
    @{ Path = "/gateway-health"; Statuses = @(200) },
    @{ Path = "/notification-api/v1/notifications/sender-profiles/default"; Statuses = @(200) },
    @{ Path = "/identity-api/v1/rbac/roles"; Statuses = @(200) },
    @{ Path = "/identity-api/v1/rbac/permissions"; Statuses = @(200) },
    @{ Path = "/tenant-api/v1/schools"; Statuses = @(200) },
    @{ Path = "/tenant-api/v1/schools/1"; Statuses = @(200, 404) },
    @{ Path = "/tenant-api/v1/schools/1/sections"; Statuses = @(200) },
    @{ Path = "/tenant-api/v1/classes"; Statuses = @(200) },
    @{ Path = "/tenant-api/v1/sections"; Statuses = @(200) },
    @{ Path = "/tenant-api/v1/academic-years"; Statuses = @(200) },
    @{ Path = "/tenant-api/v1/superadmin/schools/stats"; Statuses = @(200) },
    @{ Path = "/student-api/v1/students?schoolId=1&limit=5"; Statuses = @(200) },
    @{ Path = "/student-api/v1/students/1"; Statuses = @(200, 404) },
    @{ Path = "/student-api/v1/students/workspace?schoolId=1&page=0&size=5"; Statuses = @(200) },
    @{ Path = "/student-api/v1/students/imports/batches?limit=5"; Statuses = @(200) },
    @{ Path = "/attendance-api/v1/attendance/daily?limit=1"; Statuses = @(200) },
    @{ Path = "/attendance-api/v1/attendance/records?limit=1"; Statuses = @(200) },
    @{ Path = "/fee-api/v1/fees/bands"; Statuses = @(200) },
    @{ Path = "/fee-api/v1/fees/items"; Statuses = @(200) },
    @{ Path = "/fee-api/v1/fees/assignments?limit=1"; Statuses = @(200) },
    @{ Path = "/fee-api/v1/fees/payments?limit=1"; Statuses = @(200) },
    @{ Path = "/catalog-api/v1/catalog/orders?schoolId=1&limit=1"; Statuses = @(200) },
    @{ Path = "/workflow-api/v1/workflows/definitions"; Statuses = @(200) },
    @{ Path = "/workflow-api/v1/workflows/instances?schoolId=1&limit=1"; Statuses = @(200) },
    @{ Path = "/firefighting-api/v1/ff/requests?schoolId=1&limit=1"; Statuses = @(200) },
    @{ Path = "/billing-api/v1/billing/sa/invoices?limit=1"; Statuses = @(200) },
    @{ Path = "/reporting-api/v1/reporting/summary"; Statuses = @(200) },
    @{ Path = "/reporting-api/v1/reporting/fee-defaulters?schoolId=1&page=0&size=5"; Statuses = @(200) },
    @{ Path = "/reporting-api/v1/reporting/low-attendance/sections?schoolId=1&date=2026-02-02"; Statuses = @(200) },
    @{ Path = "/reporting-api/v1/reporting/vendor-dues?schoolId=1"; Statuses = @(200) },
    @{ Path = "/audit-api/v1/audit/events?limit=1"; Statuses = @(200) }
)

$failures = @()

foreach ($route in $routes) {
    $path = $route.Path
    $expectedStatuses = $route.Statuses
    $uri = "$GatewayBaseUrl$path"
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec $TimeoutSeconds
        if ([int]$response.StatusCode -notin $expectedStatuses) {
            $failures += "$path -> $($response.StatusCode), expected one of $($expectedStatuses -join ',')"
        }
    } catch {
        $statusCode = $null
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        if ($statusCode -notin $expectedStatuses) {
            $failures += "$path -> $statusCode $($_.Exception.Message)"
        }
    }
}

Write-Host "Gateway route smoke total=$($routes.Count) failures=$($failures.Count)"

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "  $_" }
    exit 1
}

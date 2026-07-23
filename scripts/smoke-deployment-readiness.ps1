param(
    [string]$GatewayBaseUrl = "http://localhost",
    [string]$SuperadminToken = $env:IMS_SMOKE_SUPERADMIN_TOKEN,
    [string]$AdminToken = $env:IMS_SMOKE_ADMIN_TOKEN,
    [string]$SuperadminEmail = $env:IMS_SMOKE_SUPERADMIN_EMAIL,
    [string]$SuperadminPassword = $env:IMS_SMOKE_SUPERADMIN_PASSWORD,
    [string]$AdminEmail = $env:IMS_SMOKE_ADMIN_EMAIL,
    [string]$AdminPassword = $env:IMS_SMOKE_ADMIN_PASSWORD,
    [long]$SchoolId = 1,
    [long]$StudentId = 1,
    [long]$AdminUserId = 900002,
    [string]$ClassId = "1",
    [string]$SectionId = "1A",
    [string]$AttendanceDate = "2026-02-02",
    [string]$OutputJson,
    [int]$TimeoutSeconds = 20,
    [switch]$RunPhotoUploadSmoke,
    [switch]$SkipHealth
)

$ErrorActionPreference = "Stop"

$results = New-Object System.Collections.Generic.List[object]

function Join-Url {
    param([string]$Base, [string]$Path)
    $Base.TrimEnd("/") + $Path
}

function Login {
    param(
        [string]$Email,
        [string]$Password,
        [string]$Actor
    )

    if ([string]::IsNullOrWhiteSpace($Email) -or [string]::IsNullOrWhiteSpace($Password)) {
        throw "$Actor token not supplied and $Actor login credentials are incomplete."
    }

    $body = @{ email = $Email; password = $Password } | ConvertTo-Json
    $response = Invoke-RestMethod -Uri (Join-Url $GatewayBaseUrl "/api/v1/auth/login") -Method Post `
        -ContentType "application/json" -Body $body -TimeoutSec $TimeoutSeconds
    if (-not $response.accessToken) {
        throw "$Actor login response did not include accessToken."
    }
    $response.accessToken
}

function Resolve-Token {
    param(
        [string]$Token,
        [string]$Email,
        [string]$Password,
        [string]$Actor
    )

    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        return $Token
    }
    Login $Email $Password $Actor
}

function Invoke-SmokeCheck {
    param(
        [string]$Feature,
        [string]$Path,
        [string]$Token,
        [int[]]$ExpectedStatus = @(200)
    )

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers.Authorization = "Bearer $Token"
    }

    $uri = Join-Url $GatewayBaseUrl $Path
    $status = $null
    $errorMessage = $null

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -Method Get -Headers $headers -TimeoutSec $TimeoutSeconds
        $status = [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $status = [int]$_.Exception.Response.StatusCode
        }
        $errorMessage = $_.Exception.Message
    }

    $passed = $ExpectedStatus -contains $status
    $results.Add([pscustomobject]@{
        Feature = $Feature
        Method = "GET"
        Path = $Path
        Status = $status
        Passed = $passed
        Error = $errorMessage
    }) | Out-Null

    if (-not $passed) {
        Write-Host "FAILED $Feature GET $Path -> $status $errorMessage"
    }
}

function Invoke-SmokePhotoUpload {
    param(
        [long]$TargetStudentId,
        [string]$Token
    )

    $path = "/api/v1/students/${TargetStudentId}/photo"
    $client = [System.Net.Http.HttpClient]::new()
    $form = [System.Net.Http.MultipartFormDataContent]::new()
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
        if (-not [string]::IsNullOrWhiteSpace($Token)) {
            $client.DefaultRequestHeaders.Authorization =
                [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
        }
        $bytes = [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=")
        $content = [System.Net.Http.ByteArrayContent]::new($bytes)
        $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("image/png")
        $form.Add($content, "file", "deployment-smoke-photo.png")

        $response = $client.PostAsync((Join-Url $GatewayBaseUrl $path), $form).GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $passed = $status -eq 200
        $errorMessage = if ($passed) { "" } else { $body }

        if ($passed) {
            try {
                $json = $body | ConvertFrom-Json
                if ([string]::IsNullOrWhiteSpace([string]$json.photoUrl)) {
                    $passed = $false
                    $errorMessage = "response missing photoUrl"
                }
            } catch {
                $passed = $false
                $errorMessage = "response was not JSON"
            }
        }

        $results.Add([pscustomobject]@{
            Feature = "student:photo-upload"
            Method = "POST"
            Path = $path
            Status = $status
            Passed = $passed
            Error = $errorMessage
        }) | Out-Null

        if (-not $passed) {
            Write-Host "FAILED student:photo-upload POST $path -> $status $errorMessage"
        }
    } finally {
        $form.Dispose()
        $client.Dispose()
    }
}

$superToken = Resolve-Token $SuperadminToken $SuperadminEmail $SuperadminPassword "superadmin"
$schoolAdminToken = Resolve-Token $AdminToken $AdminEmail $AdminPassword "admin"

if (-not $SkipHealth) {
    Invoke-SmokeCheck "gateway:health" "/gateway-health" "" @(200)
}

$checks = @(
    @("tenant-school:list-schools", "/api/v1/schools", "super"),
    @("tenant-school:school-admin", "/api/v1/schools/${SchoolId}/admin", "super"),
    @("tenant-school:modules", "/api/v1/schools/${SchoolId}/modules", "super"),
    @("zone:list", "/api/v1/zones", "super"),
    @("identity:roles", "/api/v1/rbac/roles", "super"),
    @("identity:permissions", "/api/v1/rbac/permissions", "super"),
    @("identity:user-roles", "/api/v1/rbac/users/${AdminUserId}/roles", "super"),
    @("workspace:dashboard", "/api/v1/dashboard?schoolId=$SchoolId", "admin"),
    @("student:list", "/api/v1/students?schoolId=${SchoolId}&page=0&size=5", "admin"),
    @("student:detail", "/api/v1/students/${StudentId}?schoolId=${SchoolId}", "admin"),
    @("student:import-template", "/api/v1/students/import/template", "admin"),
    @("attendance:daily-summary", "/api/v1/attendance/daily-summary?schoolId=${SchoolId}&date=${AttendanceDate}", "admin"),
    @("attendance:section-info", "/api/v1/attendance/section-info?schoolId=${SchoolId}&date=${AttendanceDate}&classId=${ClassId}&sectionId=${SectionId}", "admin"),
    @("fee:structure", "/api/v1/fee-structure", "admin"),
    @("fee:classes", "/api/v1/classes?schoolId=${SchoolId}", "admin"),
    @("fee:class-sections", "/api/v1/classes/${ClassId}/sections?schoolId=${SchoolId}", "admin"),
    @("fee:roster", "/api/v1/classes/${ClassId}/sections/${SectionId}/students?schoolId=${SchoolId}", "admin"),
    @("fee:report", "/api/v1/fees/report?schoolId=${SchoolId}&classId=${ClassId}&sectionId=${SectionId}", "admin"),
    @("catalog:categories", "/api/v1/supply/catalog-categories", "admin"),
    @("catalog:orders", "/api/v1/supply/orders?schoolId=$SchoolId", "admin"),
    @("catalog:annual-plan", "/api/v1/supply/annual-plan?schoolId=$SchoolId", "admin"),
    @("workflow:pending", "/api/v1/workflows/pending?schoolId=$SchoolId", "admin"),
    @("firefighting:requests", "/api/v1/ff/requests?schoolId=$SchoolId", "admin"),
    @("firefighting:stats", "/api/v1/ff/requests/stats?schoolId=$SchoolId", "admin"),
    @("reporting:command-center", "/api/v1/dashboard/command-center", "admin"),
    @("reporting:summary", "/api/v1/command-centre/summary", "admin"),
    @("reporting:actions", "/api/v1/command-centre/actions", "admin"),
    @("reporting:feed", "/api/v1/command-centre/feed?limit=5", "admin"),
    @("reporting:low-attendance", "/api/v1/dashboard/attendance/low-sections?schoolId=${SchoolId}&date=${AttendanceDate}", "admin"),
    @("reporting:fee-defaulters", "/api/v1/dashboard/finance/fee-defaulters?schoolId=${SchoolId}&page=0&size=5", "admin"),
    @("reporting:vendor-dues", "/api/v1/dashboard/vendor-dues?schoolId=$SchoolId", "admin"),
    @("reporting:reorder-signals", "/api/v1/dashboard/reorder-signals?schoolId=$SchoolId", "admin"),
    @("notification:broadcasts", "/api/v1/notifications/broadcasts?schoolId=$SchoolId", "admin"),
    @("audit:logs", "/api/v1/audit-logs?limit=5", "super"),
    @("billing:invoices", "/api/v1/sa/invoices", "super"),
    @("billing:invoice-stats", "/api/v1/sa/invoices/stats", "super"),
    @("superadmin:orders", "/api/v1/sa/orders", "super"),
    @("superadmin:schools", "/api/v1/sa/schools", "super")
)

foreach ($check in $checks) {
    $token = if ($check[2] -eq "super") { $superToken } else { $schoolAdminToken }
    Invoke-SmokeCheck $check[0] $check[1] $token @(200)
}

if ($RunPhotoUploadSmoke) {
    Invoke-SmokePhotoUpload $StudentId $schoolAdminToken
}

$failures = @($results | Where-Object { -not $_.Passed })
Write-Host "Deployment readiness smoke total=$($results.Count) passed=$($results.Count - $failures.Count) failures=$($failures.Count)"

if ($OutputJson) {
    [ordered]@{
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        gatewayBaseUrl = $GatewayBaseUrl
        total = $results.Count
        passed = $results.Count - $failures.Count
        failures = $failures.Count
        checks = $results
    } | ConvertTo-Json -Depth 5 | Set-Content -Path $OutputJson -Encoding UTF8
}

if ($failures.Count -gt 0) {
    $failures | Format-Table Feature, Method, Path, Status, Error -AutoSize
    exit 1
}

$results | Format-Table Feature, Method, Path, Status -AutoSize

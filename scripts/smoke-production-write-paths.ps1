param(
    [string]$GatewayBaseUrl = "https://custoking-api-gateway-xkv7oenbna-em.a.run.app",
    [string]$SuperadminEmail = $env:IMS_SMOKE_SUPERADMIN_EMAIL,
    [string]$SuperadminPassword = $env:IMS_SMOKE_SUPERADMIN_PASSWORD,
    [string]$AdminEmail = $env:IMS_SMOKE_ADMIN_EMAIL,
    [string]$AdminPassword = $env:IMS_SMOKE_ADMIN_PASSWORD,
    [long]$SchoolId = 4,
    [string]$OutputJson = "production-write-smoke.json",
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"

$results = New-Object System.Collections.Generic.List[object]
$runId = (Get-Date -Format "yyyyMMddHHmmss")

function Join-Url {
    param([string]$Base, [string]$Path)
    $Base.TrimEnd("/") + $Path
}

function Add-Result {
    param(
        [string]$Feature,
        [string]$Method,
        [string]$Path,
        [string]$Actor,
        [bool]$Passed,
        [string]$Detail
    )

    $results.Add([pscustomobject]@{
        feature = $Feature
        method = $Method
        path = $Path
        actor = $Actor
        passed = $Passed
        detail = $Detail
    }) | Out-Null
}

function Login {
    param([string]$Email, [string]$Password, [string]$Actor)
    if ([string]::IsNullOrWhiteSpace($Email) -or [string]::IsNullOrWhiteSpace($Password)) {
        throw "$Actor credentials are required."
    }
    $body = @{ email = $Email; password = $Password } | ConvertTo-Json
    $response = Invoke-RestMethod -Uri (Join-Url $GatewayBaseUrl "/api/v1/auth/login") `
        -Method Post `
        -ContentType "application/json" `
        -Body $body `
        -TimeoutSec $TimeoutSeconds
    if (-not $response.accessToken) {
        throw "$Actor login response did not include accessToken."
    }
    return $response.accessToken
}

function Invoke-Check {
    param(
        [string]$Feature,
        [string]$Method,
        [string]$Path,
        [string]$Token,
        [string]$Actor,
        [object]$Body = $null,
        [int[]]$ExpectedStatus = @(200)
    )

    $parameters = @{
        Uri = Join-Url $GatewayBaseUrl $Path
        Method = $Method
        Headers = @{ Authorization = "Bearer $Token" }
        TimeoutSec = $TimeoutSeconds
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 20
    }

    try {
        $response = Invoke-WebRequest @parameters
        $status = [int]$response.StatusCode
        $passed = $ExpectedStatus -contains $status
        Add-Result $Feature $Method $Path $Actor $passed "HTTP $status"
        return $response
    } catch {
        $status = $null
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $status = [int]$_.Exception.Response.StatusCode
        }
        Add-Result $Feature $Method $Path $Actor $false "HTTP $status $($_.Exception.Message)"
        return $null
    }
}

function ConvertFrom-ResponseJson {
    param($Response)
    if ($null -eq $Response -or [string]::IsNullOrWhiteSpace($Response.Content)) {
        return $null
    }
    return $Response.Content | ConvertFrom-Json
}

$superToken = Login $SuperadminEmail $SuperadminPassword "superadmin"
$adminToken = Login $AdminEmail $AdminPassword "admin"

$moduleCode = "REPORTS"
Invoke-Check "tenant-school:module-upsert" "PUT" "/api/v1/schools/$SchoolId/modules/$moduleCode" $superToken "superadmin" @{
    enabled = $true
    plan = "E2E"
    notes = "production write smoke $runId"
} | Out-Null
Invoke-Check "tenant-school:module-disable" "DELETE" "/api/v1/schools/$SchoolId/modules/$moduleCode" $superToken "superadmin" $null @(204) | Out-Null
Invoke-Check "tenant-school:module-restore" "PUT" "/api/v1/schools/$SchoolId/modules/$moduleCode" $superToken "superadmin" @{
    enabled = $true
    plan = "E2E"
    notes = "production write smoke restore $runId"
} | Out-Null

$roleName = "E2E_PROD_WRITE_$runId"
$roleResponse = Invoke-Check "identity:role-create" "POST" "/api/v1/rbac/roles" $superToken "superadmin" @{
    name = $roleName
    description = "Production write smoke $runId"
    permissionCodes = @("student:read")
} @(201)
$role = ConvertFrom-ResponseJson $roleResponse
if ($role -and $role.id) {
    Invoke-Check "identity:role-update" "PUT" "/api/v1/rbac/roles/$($role.id)" $superToken "superadmin" @{
        name = $roleName
        description = "Production write smoke updated $runId"
        permissionCodes = @("student:read", "fee:read")
    } | Out-Null
}

$broadcastResponse = Invoke-Check "notification:broadcast-create" "POST" "/api/v1/notifications/broadcasts" $adminToken "admin" @{
    schoolId = $SchoolId
    title = "E2E Production Smoke $runId"
    message = "Production write-path smoke draft"
    channel = "SMS"
    audience = "ALL"
} @(200, 201)
$broadcast = ConvertFrom-ResponseJson $broadcastResponse
if ($broadcast -and $broadcast.id) {
    Invoke-Check "notification:broadcast-status" "GET" "/api/v1/notifications/broadcasts/$($broadcast.id)/delivery-status" $adminToken "admin" | Out-Null
}

Invoke-Check "audit:write-visible" "GET" "/api/v1/audit-logs?limit=5" $superToken "superadmin" | Out-Null

$failures = @($results | Where-Object { -not $_.passed })
[ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    gatewayBaseUrl = $GatewayBaseUrl
    schoolId = $SchoolId
    total = $results.Count
    passed = $results.Count - $failures.Count
    failures = $failures.Count
    checks = $results
} | ConvertTo-Json -Depth 6 | Set-Content -Path $OutputJson -Encoding UTF8

Write-Host "Production write smoke total=$($results.Count) passed=$($results.Count - $failures.Count) failures=$($failures.Count)"
if ($failures.Count -gt 0) {
    $failures | Format-Table feature, method, path, actor, detail -AutoSize
    exit 1
}

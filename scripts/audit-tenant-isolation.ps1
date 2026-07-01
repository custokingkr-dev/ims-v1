param(
    [string]$GatewayBaseUrl = "http://localhost",
    [string]$AdminAEmail = "local-admin@custoking.local",
    [string]$AdminBEmail = "local-admin2@custoking.local",
    [string]$AdminPassword = "password",
    [string]$SuperEmail = "local-superadmin@custoking.local",
    [string]$SuperPassword = "password",
    [int]$TimeoutSeconds = 30
)
$ErrorActionPreference = "Stop"

# Fixture ids - MUST match scripts/ensure-local-dev-users.ps1
$SchoolA = 1; $SchoolB = 2
$StudentA = 9000001; $StudentB = 9000002
$OrderA = 'ord-bola-a'; $OrderB = 'ord-bola-b'
$FfA = 'ff-bola-a'; $FfB = 'ff-bola-b'

$loginIp = "127.0.0.$([int](Get-Date -Format 'ss') + 20)"
$failures = New-Object System.Collections.Generic.List[string]
$probeCount = 0

function Login {
    param([string]$Email, [string]$Password)
    $body = @{ email = $Email; password = $Password } | ConvertTo-Json
    try {
        $r = Invoke-RestMethod -Uri "$GatewayBaseUrl/api/v1/auth/login" -Method Post `
            -Headers @{ "X-Forwarded-For" = $loginIp } -ContentType "application/json" `
            -Body $body -TimeoutSec $TimeoutSeconds
    } catch {
        Write-Host "SETUP ERROR: login failed for $Email - is the stack up and seeded? $($_.Exception.Message)" -ForegroundColor Red
        exit 2
    }
    if (-not $r.accessToken) { Write-Host "SETUP ERROR: no accessToken for $Email" -ForegroundColor Red; exit 2 }
    return $r.accessToken
}

# Returns @{ Status = <int>; Body = <object|$null> }. Never throws on an HTTP error status.
function Invoke-Api {
    param([string]$Method, [string]$Path, [string]$Token)
    $headers = @{ Authorization = "Bearer $Token" }
    try {
        $resp = Invoke-WebRequest -Uri "$GatewayBaseUrl$Path" -Method $Method -Headers $headers `
            -TimeoutSec $TimeoutSeconds -UseBasicParsing
        $body = $null
        if ($resp.Content) { try { $body = $resp.Content | ConvertFrom-Json } catch { $body = $resp.Content } }
        return @{ Status = [int]$resp.StatusCode; Body = $body }
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        return @{ Status = $status; Body = $null }
    }
}

# True if the JSON body (any shape) contains the given id literal.
function Body-ContainsId {
    param([object]$Body, [object]$Id)
    if ($null -eq $Body) { return $false }
    return ($Body | ConvertTo-Json -Depth 12 -Compress) -match [regex]::Escape([string]$Id)
}

function Add-Failure { param([string]$Msg) $script:failures.Add($Msg); Write-Host "  LEAK: $Msg" -ForegroundColor Red }
function Pass { param([string]$Msg) Write-Host "  ok: $Msg" -ForegroundColor DarkGray }

Write-Host "BOLA tenant-isolation gate -> $GatewayBaseUrl" -ForegroundColor Cyan
$tokenA = Login $AdminAEmail $AdminPassword
$tokenB = Login $AdminBEmail $AdminPassword
$tokenS = Login $SuperEmail $SuperPassword

Write-Host "Baseline (probes have teeth):"
# Use ${var} syntax to avoid PS 5.1 treating '?' as part of variable name
$a = Invoke-Api GET "/api/v1/students/${StudentA}?schoolId=${SchoolA}" $tokenA
if ($a.Status -ne 200 -or -not (Body-ContainsId $a.Body $StudentA)) { Add-Failure "baseline: admin-A cannot see its own student $StudentA (status $($a.Status))" } else { Pass "admin-A sees student $StudentA" }
$b = Invoke-Api GET "/api/v1/students/${StudentB}?schoolId=${SchoolB}" $tokenB
if ($b.Status -ne 200 -or -not (Body-ContainsId $b.Body $StudentB)) { Add-Failure "baseline: admin-B cannot see its own student $StudentB (status $($b.Status))" } else { Pass "admin-B sees student $StudentB" }
if ($StudentA -eq $StudentB) { Add-Failure "baseline: StudentA and StudentB ids are not distinct" }
$sa = Invoke-Api GET "/api/v1/students/${StudentA}?schoolId=${SchoolA}" $tokenS
$sb = Invoke-Api GET "/api/v1/students/${StudentB}?schoolId=${SchoolB}" $tokenS
if ($sa.Status -ne 200 -or $sb.Status -ne 200) { Add-Failure "baseline: superadmin cannot see both students (A $($sa.Status) / B $($sb.Status))" } else { Pass "superadmin sees both" }
if ($failures.Count -gt 0) {
    Write-Host "Baseline failed - fixture or scoping is broken; aborting before probes." -ForegroundColor Red
    exit 1
}

Write-Host "Detail-by-id probes (admin-A requests a school-B object):"
$detailProbes = @(
    @{ Key = 'student:detail'; Path = "/api/v1/students/${StudentB}?schoolId=${SchoolB}"; Marker = $StudentB },
    @{ Key = 'catalog:order-detail'; Path = "/api/v1/supply/orders/${OrderB}?schoolId=${SchoolB}"; Marker = $OrderB },
    @{ Key = 'firefighting:request-detail'; Path = "/api/v1/ff/requests/${FfB}?schoolId=${SchoolB}"; Marker = $FfB }
)
foreach ($p in $detailProbes) {
    $script:probeCount++
    $r = Invoke-Api GET $p.Path $tokenA
    if ($r.Status -eq 403 -or $r.Status -eq 404) { Pass "$($p.Key) denied ($($r.Status))" }
    elseif ($r.Status -eq 200 -and (Body-ContainsId $r.Body $p.Marker)) { Add-Failure "$($p.Key): admin-A read school-B object $($p.Marker) (HTTP 200)" }
    else { Pass "$($p.Key) no B data (status $($r.Status))" }
}

if ($failures.Count) { exit 1 } else { Write-Host 'detail probes isolated'; exit 0 }

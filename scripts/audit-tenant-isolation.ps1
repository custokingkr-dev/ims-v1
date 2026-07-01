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
$WfA = 'wf-bola-a'; $WfB = 'wf-bola-b'

$loginIp = "127.0.0.$([int](Get-Date -Format 'ss') + 20)"
$failures = New-Object System.Collections.Generic.List[string]
# Two honest counts: probes with real teeth (detail-by-id + marker-list) vs advisory
# own-scope equivalence probes on aggregate endpoints (no teeth without differential seed data).
$teethProbes = 0
$advisoryProbes = 0
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
    $script:teethProbes++
    $r = Invoke-Api GET $p.Path $tokenA
    if ($r.Status -eq 403 -or $r.Status -eq 404) { Pass "$($p.Key) denied ($($r.Status))" }
    elseif ($r.Status -eq 200 -and (Body-ContainsId $r.Body $p.Marker)) { Add-Failure "$($p.Key): admin-A read school-B object $($p.Marker) (HTTP 200)" }
    elseif ($r.Status -eq 0 -or $r.Status -ge 500) { Add-Failure "$($p.Key): probe inconclusive - unexpected status $($r.Status) (connection error or server fault)" }
    else { Pass "$($p.Key) no B data (status $($r.Status))" }
}


Write-Host "List probes (admin-A passes ?schoolId=$SchoolB):"
# marker-backed: a seeded school-B row must NOT appear in admin-A's cross-tenant list
$listMarker = @(
    @{ Key = 'student:list';            X = "/api/v1/students?schoolId=${SchoolB}&page=0&size=50";  Marker = $StudentB },
    @{ Key = 'catalog:orders';          X = "/api/v1/supply/orders?schoolId=${SchoolB}";            Marker = $OrderB },
    @{ Key = 'firefighting:requests';   X = "/api/v1/ff/requests?schoolId=${SchoolB}";             Marker = $FfB },
    @{ Key = 'workflow:pending';        X = "/api/v1/workflows/pending?schoolId=${SchoolB}";        Marker = $WfB }
)
foreach ($p in $listMarker) {
    $script:teethProbes++
    $r = Invoke-Api GET $p.X $tokenA
    if ($r.Status -eq 0 -or $r.Status -ge 500) { Add-Failure "$($p.Key): probe inconclusive - unexpected status $($r.Status)" }
    elseif ($r.Status -eq 403) { Pass "$($p.Key) denied (403)" }
    elseif (Body-ContainsId $r.Body $p.Marker) { Add-Failure "$($p.Key): admin-A's ?schoolId=${SchoolB} list contained school-B marker $($p.Marker)" }
    else { Pass "$($p.Key) no B marker" }
}

# own-scope-equivalence (ADVISORY): cross-tenant param must return same data as own-scope
# (server must ignore client schoolId). These aggregate endpoints have no per-school marker
# rows seeded, so they cannot prove isolation by themselves - they are equivalence checks only.
$listOwnScope = @(
    @{ Key = 'attendance:daily-summary'; A = "/api/v1/attendance/daily-summary?schoolId=${SchoolA}&date=2026-02-02";   X = "/api/v1/attendance/daily-summary?schoolId=${SchoolB}&date=2026-02-02" },
    @{ Key = 'fee:report';               A = "/api/v1/fees/report?schoolId=${SchoolA}&classId=1&sectionId=1A";         X = "/api/v1/fees/report?schoolId=${SchoolB}&classId=1&sectionId=1A" },
    @{ Key = 'catalog:annual-plan';      A = "/api/v1/supply/annual-plan?schoolId=${SchoolA}";                         X = "/api/v1/supply/annual-plan?schoolId=${SchoolB}" },
    @{ Key = 'firefighting:stats';       A = "/api/v1/ff/requests/stats?schoolId=${SchoolA}";                          X = "/api/v1/ff/requests/stats?schoolId=${SchoolB}" },
    @{ Key = 'workspace:dashboard';      A = "/api/v1/dashboard?schoolId=${SchoolA}";                                  X = "/api/v1/dashboard?schoolId=${SchoolB}" }
)
foreach ($p in $listOwnScope) {
    $script:advisoryProbes++
    $rx = Invoke-Api GET $p.X $tokenA
    if ($rx.Status -eq 0 -or $rx.Status -ge 500) { Add-Failure "$($p.Key): probe inconclusive - unexpected status $($rx.Status)"; continue }
    # Any deny/not-found/bad-request on the cross-tenant call means B data was NOT served -> Pass.
    if ($rx.Status -eq 403 -or $rx.Status -eq 404 -or $rx.Status -eq 400 -or $rx.Status -eq 401) { Pass "$($p.Key) denied ($($rx.Status))"; continue }
    $ra = Invoke-Api GET $p.A $tokenA
    if ($ra.Status -eq 0 -or $ra.Status -ge 500) { Add-Failure "$($p.Key): own-scope reference call inconclusive - status $($ra.Status)"; continue }
    $jx = if ($rx.Body) { $rx.Body | ConvertTo-Json -Depth 12 -Compress } else { '' }
    $ja = if ($ra.Body) { $ra.Body | ConvertTo-Json -Depth 12 -Compress } else { '' }
    # Only flag when X returns 200 carrying a B marker, or a genuinely differing (non-error) body.
    if ((Body-ContainsId $rx.Body $StudentB) -or (Body-ContainsId $rx.Body $OrderB) -or (Body-ContainsId $rx.Body $FfB) -or (Body-ContainsId $rx.Body $WfB)) {
        Add-Failure "$($p.Key): admin-A's ?schoolId=${SchoolB} response carried a school-B marker"
    } elseif ($jx -ne $ja) {
        Add-Failure "$($p.Key): ?schoolId=${SchoolB} differs from own ?schoolId=${SchoolA} (client param widened scope)"
    } else { Pass "$($p.Key) == own-scope" }
}

$excluded = @('classes (global - no school_id)', 'supply/catalog-categories (catalog-wide)', 'fee-structure (catalog-wide)', 'students/import/template (static)', 'rbac/* zones/* schools/* (superadmin-only)')
$probeCount = $teethProbes + $advisoryProbes
Write-Host ""
Write-Host "Coverage: $teethProbes teeth-backed probes (detail + marker-list) + $advisoryProbes advisory equivalence probes (aggregate endpoints, no teeth without differential seed data)." -ForegroundColor Cyan
Write-Host "Excluded (non-tenant-scoped, by design): $($excluded -join '; ')" -ForegroundColor DarkGray
if ($failures.Count -gt 0) {
    Write-Host "BOLA gate FAILED - $($failures.Count) cross-tenant leak(s):" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "BOLA gate PASSED - no cross-tenant leaks across $probeCount probes." -ForegroundColor Green
exit 0

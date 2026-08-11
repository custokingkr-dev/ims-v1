param(
    [string] $GatewayBaseUrl,
    [long] $SchoolId = 0,
    [string] $FirstFileToken = $env:IMS_DEV_IMPORT_GUARD_FIRST_TOKEN,
    [string] $SecondFileToken = $env:IMS_DEV_IMPORT_GUARD_SECOND_TOKEN,
    [string] $ExpectedDevHost,
    [string] $AccessTokenEnvironmentVariable = "IMS_DEV_IMPORT_GUARD_TOKEN",
    [ValidateRange(30, 600)] [int] $TimeoutSeconds = 180,
    [string] $EvidencePath,
    [switch] $AllowRemoteDevWrites,
    [switch] $Execute
)

$ErrorActionPreference = "Stop"

function Show-Plan {
    [pscustomobject]@{
        Mode = "PLAN_ONLY_NO_NETWORK"
        Purpose = "Prove one same-school confirmation succeeds while one receives the admission guard's retryable HTTP 429."
        RequiredEnvironment = "localhost or the exact deployed dev gateway host; never production"
        RequiredActor = "short-lived school-admin token for the dedicated synthetic school, with student:import permission"
        RequiredBatches = "two distinct PREVIEWED tokens for the same synthetic school; exactly 500 unique valid rows per token"
        WritesWhenExecuted = "one token normally commits up to 500 synthetic students; the rejected token remains PREVIEWED"
        Cleanup = "operator must reconcile the successful batch and remove the dedicated synthetic fixture under the approved dev cleanup procedure"
        Secrets = "access and preview tokens should be read from environment variables; never written to evidence"
        ExecuteGate = "pass -Execute; remote dev also requires -AllowRemoteDevWrites and -ExpectedDevHost equal to the URI host"
    } | Format-List
}

if (-not $Execute) {
    Show-Plan
    exit 0
}

if ([string]::IsNullOrWhiteSpace($GatewayBaseUrl)) {
    throw "GatewayBaseUrl is required with -Execute."
}
if ($SchoolId -le 0) {
    throw "SchoolId must identify the dedicated synthetic dev school."
}
if ([string]::IsNullOrWhiteSpace($FirstFileToken) -or
    [string]::IsNullOrWhiteSpace($SecondFileToken) -or
    $FirstFileToken -eq $SecondFileToken) {
    throw "Provide two distinct PREVIEWED file tokens for the same synthetic dev school."
}

$baseUri = $null
if (-not [Uri]::TryCreate($GatewayBaseUrl, [UriKind]::Absolute, [ref]$baseUri)) {
    throw "GatewayBaseUrl must be an absolute URI."
}
if ($baseUri.Scheme -notin @("http", "https") -or
    -not [string]::IsNullOrWhiteSpace($baseUri.UserInfo) -or
    $baseUri.AbsolutePath -ne "/" -or
    -not [string]::IsNullOrWhiteSpace($baseUri.Query) -or
    -not [string]::IsNullOrWhiteSpace($baseUri.Fragment)) {
    throw "GatewayBaseUrl must contain only an HTTP(S) scheme and host (plus an optional port)."
}
$isLocal = $baseUri.IsLoopback
if (-not $isLocal) {
    if ($baseUri.Scheme -ne "https") {
        throw "Remote dev verification requires HTTPS."
    }
    if (-not $AllowRemoteDevWrites) {
        throw "This verification confirms an import. Pass -AllowRemoteDevWrites for the remote dev gateway."
    }
    if ($baseUri.DnsSafeHost -notmatch "(^|[.-])dev([.-]|$)") {
        throw "Remote verification is restricted to a host whose DNS name contains a distinct dev label."
    }
    if ([string]::IsNullOrWhiteSpace($ExpectedDevHost) -or
        $baseUri.DnsSafeHost.ToLowerInvariant() -ne $ExpectedDevHost.Trim().ToLowerInvariant()) {
        throw "ExpectedDevHost must exactly match the remote dev gateway host."
    }
}

$accessToken = [Environment]::GetEnvironmentVariable($AccessTokenEnvironmentVariable)
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    throw "Environment variable $AccessTokenEnvironmentVariable must contain a short-lived school-admin access token."
}

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
    $stamp = [datetime]::UtcNow.ToString("yyyyMMddHHmmss")
    $EvidencePath = Join-Path $repoRoot "artifacts/onboarding-certification/import-admission-$stamp.json"
}
if (Test-Path -LiteralPath $EvidencePath) {
    throw "EvidencePath already exists; choose a new path so prior evidence is never overwritten."
}
$evidenceParent = Split-Path -Parent $EvidencePath
if (-not [string]::IsNullOrWhiteSpace($evidenceParent)) {
    New-Item -ItemType Directory -Force -Path $evidenceParent | Out-Null
}

Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$firstRequest = $null
$secondRequest = $null
$batchResponse = $null
$firstResponse = $null
$secondResponse = $null
try {
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    $client.DefaultRequestHeaders.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $accessToken)

    $base = $GatewayBaseUrl.TrimEnd("/")
    $batchResponse = $client.GetAsync("$base/api/v1/students/imports/batches?limit=500").GetAwaiter().GetResult()
    $batchBody = $batchResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if ([int]$batchResponse.StatusCode -ne 200) {
        throw "Read-only batch preflight failed with HTTP $([int]$batchResponse.StatusCode)."
    }
    try {
        $batches = @($batchBody | ConvertFrom-Json)
    } catch {
        throw "Read-only batch preflight did not return JSON."
    }

    foreach ($token in @($FirstFileToken, $SecondFileToken)) {
        $matches = @($batches | Where-Object { [string]$_.fileToken -eq $token })
        if ($matches.Count -ne 1) {
            throw "Each supplied token must appear exactly once in the authenticated tenant's import ledger."
        }
        if ([string]$matches[0].status -ne "PREVIEWED") {
            throw "Both supplied tokens must still be PREVIEWED."
        }
        if ([int]$matches[0].totalRows -ne 500 -or [int]$matches[0].validCount -ne 500) {
            throw "Each batch must contain exactly 500 valid synthetic rows so the concurrency probe has meaningful overlap."
        }
    }

    $path = "$base/api/v1/students/imports/confirm"
    $firstJson = @{ schoolId = $SchoolId; fileToken = $FirstFileToken } | ConvertTo-Json -Compress
    $secondJson = @{ schoolId = $SchoolId; fileToken = $SecondFileToken } | ConvertTo-Json -Compress
    $firstRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $path)
    $secondRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $path)
    $firstRequest.Content = [System.Net.Http.StringContent]::new($firstJson, [Text.Encoding]::UTF8, "application/json")
    $secondRequest.Content = [System.Net.Http.StringContent]::new($secondJson, [Text.Encoding]::UTF8, "application/json")

    $startedAt = [datetime]::UtcNow
    $clock = [Diagnostics.Stopwatch]::StartNew()
    # SendAsync starts both requests before either result is awaited.
    $firstTask = $client.SendAsync($firstRequest)
    $secondTask = $client.SendAsync($secondRequest)
    $firstResponse = $firstTask.GetAwaiter().GetResult()
    $secondResponse = $secondTask.GetAwaiter().GetResult()
    $clock.Stop()

    $observations = @()
    foreach ($response in @($firstResponse, $secondResponse)) {
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $json = $null
        if (-not [string]::IsNullOrWhiteSpace($body)) {
            try { $json = $body | ConvertFrom-Json } catch { $json = $null }
        }
        $retryAfter = $null
        try { $retryAfter = [int](($response.Headers.GetValues("Retry-After") | Select-Object -First 1)) } catch { }
        $observations += [pscustomobject]@{
            status = [int]$response.StatusCode
            code = if ($null -ne $json) { [string]$json.code } else { "" }
            retryAfterHeaderSeconds = $retryAfter
            retryAfterBodySeconds = if ($null -ne $json -and $null -ne $json.retryAfterSeconds) {
                [int]$json.retryAfterSeconds
            } else { $null }
            inserted = if ($null -ne $json -and $null -ne $json.inserted) { [int]$json.inserted } else { $null }
            skipped = if ($null -ne $json -and $null -ne $json.skipped) { [int]$json.skipped } else { $null }
        }
    }

    $successes = @($observations | Where-Object { $_.status -ge 200 -and $_.status -lt 300 })
    $rejections = @($observations | Where-Object {
        $_.status -eq 429 -and
        $_.code -eq "school_import_active" -and
        $_.retryAfterHeaderSeconds -eq 5 -and
        $_.retryAfterBodySeconds -eq 5
    })
    $passed = $successes.Count -eq 1 -and $rejections.Count -eq 1
    $evidence = [ordered]@{
        generatedAtUtc = [datetime]::UtcNow.ToString("o")
        startedAtUtc = $startedAt.ToString("o")
        environment = if ($isLocal) { "local" } else { "dev" }
        gatewayHost = $baseUri.DnsSafeHost
        syntheticSchoolId = $SchoolId
        batchRowsEach = 500
        requestCount = 2
        durationMs = $clock.ElapsedMilliseconds
        passed = $passed
        expected = "exactly one 2xx and one 429 school_import_active with Retry-After 5"
        observations = $observations
        containsTokensOrStudentPii = $false
        cleanupRequired = $true
    }

    $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $EvidencePath -Encoding UTF8
    Write-Output "IMS_DEV_IMPORT_ADMISSION_RESULT|passed=$($passed.ToString().ToLowerInvariant())|durationMs=$($clock.ElapsedMilliseconds)|evidence=$EvidencePath"

    if (-not $passed) {
        throw "Admission verification was inconclusive or failed; inspect the PII-free evidence artifact."
    }
} finally {
    if ($null -ne $batchResponse) { $batchResponse.Dispose() }
    if ($null -ne $firstResponse) { $firstResponse.Dispose() }
    if ($null -ne $secondResponse) { $secondResponse.Dispose() }
    if ($null -ne $firstRequest) { $firstRequest.Dispose() }
    if ($null -ne $secondRequest) { $secondRequest.Dispose() }
    $client.Dispose()
    $accessToken = $null
}

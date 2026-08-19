param(
    [string] $GatewayBaseUrl,
    [string] $ExpectedDevHost,
    [long] $SchoolId = 0,
    [string] $ExpectedRevision,
    [string] $ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
    [string] $Region = "asia-south2",
    [string] $ServiceName = "custoking-school-core-service-dev",
    [string] $LoginEmailEnvironmentVariable = "IMS_DEV_ONBOARDING_EMAIL",
    [string] $LoginPasswordEnvironmentVariable = "IMS_DEV_ONBOARDING_PASSWORD",
    [string] $EvidencePath,
    [switch] $AllowRemoteDevWrites,
    [switch] $Execute
)

$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if (-not $Execute) {
    [pscustomobject]@{
        mode = "PLAN_ONLY_NO_NETWORK"
        purpose = "Prove the PostgreSQL import admission guard across two distinct dev Cloud Run instances."
        workload = "Two simultaneous confirmations, each for a fresh 500-row valid preview in one reserved synthetic school."
        requiredServiceShape = "exact expected revision, min instances >= 2, concurrency = 1, startup CPU boost disabled"
        expectedResult = "exactly one HTTP 200 and one HTTP 429 school_import_active; request logs identify two distinct instance IDs"
        evidenceSafety = "instance IDs are SHA-256 hashed; tokens, credentials, response bodies and student rows are not persisted"
        cleanup = "one batch completes and one remains PREVIEWED; both are synthetic and require scoped cleanup"
    } | Format-List
    exit 0
}

if (-not $AllowRemoteDevWrites) { throw "Pass -AllowRemoteDevWrites for this dev-only write proof." }
if ($SchoolId -lt 900000000) { throw "SchoolId must be in the reserved synthetic range." }
$baseUri = [Uri]$GatewayBaseUrl
if ($baseUri.Scheme -ne "https" -or $baseUri.AbsolutePath -ne "/" -or
    $baseUri.DnsSafeHost -notmatch "(^|[.-])dev([.-]|$)" -or
    $baseUri.DnsSafeHost -ne $ExpectedDevHost) {
    throw "GatewayBaseUrl and ExpectedDevHost must identify the exact HTTPS dev gateway root."
}
if ([string]::IsNullOrWhiteSpace($ExpectedRevision)) { throw "ExpectedRevision is required." }

$service = (& $gcloud run services describe $ServiceName "--project=$ProjectId" "--region=$Region" --format=json) |
    ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw "Could not describe $ServiceName." }
$annotations = $service.spec.template.metadata.annotations
if ([string]$service.status.latestReadyRevisionName -ne $ExpectedRevision -or
    [string]$service.status.latestCreatedRevisionName -ne $ExpectedRevision -or
    [int]$service.spec.template.spec.containerConcurrency -ne 1 -or
    [int]$annotations.'autoscaling.knative.dev/minScale' -lt 2 -or
    [string]$annotations.'run.googleapis.com/startup-cpu-boost' -ne "false" -or
    [int]@($service.status.traffic | Where-Object { $_.revisionName -eq $ExpectedRevision -and $_.percent -eq 100 }).Count -ne 1) {
    throw "The service must be Ready at 100% on the expected revision with min>=2, concurrency=1 and startup boost disabled."
}

$loginEmail = [Environment]::GetEnvironmentVariable($LoginEmailEnvironmentVariable)
$loginPassword = [Environment]::GetEnvironmentVariable($LoginPasswordEnvironmentVariable)
if ([string]::IsNullOrWhiteSpace($loginEmail) -or [string]::IsNullOrWhiteSpace($loginPassword)) {
    throw "Provide dev login credentials only through the named environment variables."
}
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
    $stamp = [datetime]::UtcNow.ToString("yyyyMMddHHmmssfff")
    $EvidencePath = Join-Path $repoRoot "artifacts/onboarding-certification/import-distinct-instances-$stamp.json"
}
if (Test-Path -LiteralPath $EvidencePath) { throw "EvidencePath already exists." }

Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromMinutes(5)
$accessToken = $null
$fileTokens = [System.Collections.Generic.List[string]]::new()
try {
    $base = $GatewayBaseUrl.TrimEnd("/")
    $loginBody = @{ email = $loginEmail; password = $loginPassword } | ConvertTo-Json -Compress
    $loginContent = [System.Net.Http.StringContent]::new($loginBody, [Text.Encoding]::UTF8, "application/json")
    $loginResponse = $client.PostAsync("$base/api/v1/auth/login", $loginContent).GetAwaiter().GetResult()
    $loginJson = $loginResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if ([int]$loginResponse.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace([string]$loginJson.accessToken)) {
        throw "Dev login failed without persisting its response."
    }
    $accessToken = [string]$loginJson.accessToken
    $client.DefaultRequestHeaders.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $accessToken)

    $runId = [datetime]::UtcNow.ToString("yyyyMMddHHmmss") + "-" + [Guid]::NewGuid().ToString("N").Substring(0, 6)
    for ($batch = 1; $batch -le 2; $batch++) {
        $rows = [System.Collections.Generic.List[object]]::new(500)
        for ($i = 1; $i -le 500; $i++) {
            $global = (($batch - 1) * 500) + $i
            $section = [int][Math]::Ceiling($global / 40.0)
            $class = (($section - 1) % 12) + 1
            $rows.Add([ordered]@{
                __rowNumber = $i + 1
                Name = "Synthetic Admission Student $runId-$($global.ToString('0000'))"
                Class = "Scale Class $class"
                Section = "Scale $($section.ToString('0000'))"
                AdmissionNo = "ADM2-$runId-$($global.ToString('0000'))"
                DateOfBirth = "2012-01-01"
                Gender = if ($global % 2 -eq 0) { "FEMALE" } else { "MALE" }
                FatherName = "Synthetic Guardian"
                Phone = "9000000000"
                Address = "Synthetic Address"
            })
        }
        $previewBody = @{ schoolId = $SchoolId; rows = $rows } | ConvertTo-Json -Depth 6 -Compress
        $previewContent = [System.Net.Http.StringContent]::new($previewBody, [Text.Encoding]::UTF8, "application/json")
        $previewResponse = $client.PostAsync("$base/api/v1/students/imports/preview", $previewContent).GetAwaiter().GetResult()
        $previewText = $previewResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $previewJson = $previewText | ConvertFrom-Json
        if ([int]$previewResponse.StatusCode -ne 200 -or [int]$previewJson.validCount -ne 500 -or
            [int]$previewJson.errorCount -ne 0 -or [string]::IsNullOrWhiteSpace([string]$previewJson.fileToken)) {
            throw "Fresh preview $batch did not reconcile to exactly 500 valid rows."
        }
        $fileTokens.Add([string]$previewJson.fileToken)
        $rows = $null
        $previewText = $null
        $previewJson = $null
    }

    $confirmUri = "$base/api/v1/students/imports/confirm"
    $requests = @()
    foreach ($token in $fileTokens) {
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $confirmUri)
        $body = @{ schoolId = $SchoolId; fileToken = $token } | ConvertTo-Json -Compress
        $request.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, "application/json")
        $requests += $request
    }
    $startedAt = [datetime]::UtcNow
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $tasks = @($requests | ForEach-Object { $client.SendAsync($_) })
    $responses = @($tasks | ForEach-Object { $_.GetAwaiter().GetResult() })
    $clock.Stop()
    $completedAt = [datetime]::UtcNow

    $observations = @()
    foreach ($response in $responses) {
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $json = try { $body | ConvertFrom-Json } catch { $null }
        $retryAfter = try { [int](@($response.Headers.GetValues("Retry-After"))[0]) } catch { $null }
        $observations += [pscustomobject]@{
            status = [int]$response.StatusCode
            code = if ($null -ne $json) { [string]$json.code } else { "" }
            retryAfterSeconds = $retryAfter
            inserted = if ($null -ne $json -and $null -ne $json.inserted) { [int]$json.inserted } else { $null }
        }
    }
    $contractPassed = @($observations | Where-Object { $_.status -eq 200 -and $_.inserted -eq 500 }).Count -eq 1 -and
        @($observations | Where-Object { $_.status -eq 429 -and $_.code -eq "school_import_active" -and $_.retryAfterSeconds -eq 5 }).Count -eq 1

    $logEntries = @()
    $logFilter = 'resource.type="cloud_run_revision" AND ' +
        "resource.labels.service_name=`"$ServiceName`" AND " +
        "resource.labels.revision_name=`"$ExpectedRevision`" AND " +
        'httpRequest.requestMethod="POST" AND httpRequest.requestUrl:"/api/v1/students/imports/confirm" AND ' +
        "timestamp>=`"$($startedAt.AddSeconds(-2).ToString('o'))`" AND timestamp<=`"$($completedAt.AddSeconds(10).ToString('o'))`""
    for ($attempt = 1; $attempt -le 6 -and $logEntries.Count -lt 2; $attempt++) {
        if ($attempt -gt 1) { Start-Sleep -Seconds 10 }
        $loggingToken = ((& $gcloud auth print-access-token) -join "").Trim()
        $loggingBody = @{ resourceNames = @("projects/$ProjectId"); filter = $logFilter; orderBy = "timestamp asc"; pageSize = 100 } |
            ConvertTo-Json -Compress
        $logging = Invoke-RestMethod -Method Post -Uri "https://logging.googleapis.com/v2/entries:list" `
            -Headers @{ Authorization = "Bearer $loggingToken" } -ContentType "application/json" -Body $loggingBody
        $logEntries = @($logging.entries | Where-Object { $_.httpRequest.status -in @(200, 429) })
        $loggingToken = $null
    }

    $sha = [Security.Cryptography.SHA256]::Create()
    $requestLogs = @($logEntries | ForEach-Object {
        $instanceId = [string]$_.labels.instanceId
        $hash = if ([string]::IsNullOrWhiteSpace($instanceId)) { "" } else {
            ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($instanceId))) -replace '-', '').ToLowerInvariant().Substring(0, 16)
        }
        [pscustomobject]@{
            timestamp = $_.timestamp
            status = [int]$_.httpRequest.status
            latency = [string]$_.httpRequest.latency
            revision = [string]$_.resource.labels.revision_name
            instanceIdSha256Prefix = $hash
        }
    })
    $distinctHashes = @($requestLogs.instanceIdSha256Prefix | Where-Object { $_ } | Sort-Object -Unique)
    $instancePassed = $requestLogs.Count -eq 2 -and $distinctHashes.Count -eq 2 -and
        @($requestLogs.status | Sort-Object) -join ',' -eq '200,429'
    $passed = $contractPassed -and $instancePassed

    $evidence = [ordered]@{
        generatedAtUtc = [datetime]::UtcNow.ToString("o")
        environment = "dev"
        gatewayHost = $baseUri.DnsSafeHost
        syntheticSchoolId = $SchoolId
        service = $ServiceName
        revision = $ExpectedRevision
        serviceShape = @{ minInstances = [int]$annotations.'autoscaling.knative.dev/minScale'; concurrency = 1; startupCpuBoost = $false }
        startedAtUtc = $startedAt.ToString("o")
        completedAtUtc = $completedAt.ToString("o")
        durationMs = $clock.ElapsedMilliseconds
        passed = $passed
        admissionContractPassed = $contractPassed
        distinctInstanceProofPassed = $instancePassed
        observations = $observations
        requestLogs = $requestLogs
        distinctInstanceCount = $distinctHashes.Count
        rowsPerPreview = 500
        cleanupRequired = $true
        containsTokensCredentialsOrStudentPii = $false
    }
    $parent = Split-Path -Parent $EvidencePath
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $EvidencePath -Encoding UTF8 -NoNewline
    Write-Output "IMS_DEV_IMPORT_DISTINCT_INSTANCES|passed=$($passed.ToString().ToLowerInvariant())|instances=$($distinctHashes.Count)|evidence=$EvidencePath"
    if (-not $passed) { throw "Distinct-instance admission proof failed; inspect the PII-free evidence." }
} finally {
    $accessToken = $null
    $loginEmail = $null
    $loginPassword = $null
    $fileTokens.Clear()
    $client.Dispose()
}

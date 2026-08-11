param(
    [string] $GatewayBaseUrl,
    [long] $SchoolId = 0,
    [string] $ExpectedDevHost,
    [string] $AccessTokenEnvironmentVariable = "IMS_DEV_ONBOARDING_TOKEN",
    [string] $LoginEmailEnvironmentVariable = "IMS_DEV_ONBOARDING_EMAIL",
    [string] $LoginPasswordEnvironmentVariable = "IMS_DEV_ONBOARDING_PASSWORD",
    [ValidateRange(30, 900)] [int] $TimeoutSeconds = 180,
    [string] $EvidencePath,
    [switch] $AllowRemoteDevWrites,
    [switch] $Execute
)

$ErrorActionPreference = "Stop"

$batchCount = 20
$rowsPerBatch = 500
$totalRows = $batchCount * $rowsPerBatch
$usageLookbackDays = 2
$batchLedgerLimit = 500

function Show-Plan {
    [pscustomobject]@{
        Mode = "PLAN_ONLY_NO_NETWORK"
        Purpose = "Certify the bounded 10,000-student JSON preview/confirm path through the dev gateway."
        ExactWorkload = "20 sequential batches x 500 unique synthetic rows = 10,000 rows; concurrency is exactly one."
        ExistingFixtureNames = "Scale Class 1..12 and Scale 0001..0250 for the supplied reserved synthetic school."
        RequiredEnvironment = "localhost or an exact HTTPS host containing a distinct dev label; never production"
        RequiredSchool = "an existing dedicated synthetic school id >= 900000000 with the SCALE class/section fixture"
        Authentication = "either a short-lived token or login email/password read only from named environment variables"
        Reconciliation = "20 DONE ledger rows plus exact two-day /imports/usage deltas: 20 previews/completions and 10,000 attempted/inserted"
        RetryProof = "repeat the completed confirmation for batch 20 and require the same job/batch result with no duplicate insert"
        WritesWhenExecuted = "20 preview ledgers, 10,000 students and their normal enrollment/outbox/projection effects"
        Cleanup = "no automatic cleanup; operator must reconcile and remove only the run-scoped synthetic data"
        Evidence = "PII/token-free aggregate timings, reconciliation and a non-secret cleanup run label, created with no-overwrite semantics; cleanupRequired=true"
        Limits = "no photos, no multi-school concurrency, no multi-instance proof, no production sizing or provider validation"
        ExecuteGate = "pass -Execute; remote dev also requires -AllowRemoteDevWrites and -ExpectedDevHost exactly equal to the URI host"
    } | Format-List
}

function Get-Value {
    param(
        [object] $InputObject,
        [string] $Name,
        [object] $Default = $null
    )
    if ($null -eq $InputObject) { return $Default }
    $property = $InputObject.PSObject.Properties |
        Where-Object { $_.Name -ieq $Name } |
        Select-Object -First 1
    if ($null -eq $property -or $null -eq $property.Value) { return $Default }
    return $property.Value
}

function Get-JsonItems {
    param([object] $Value)
    if ($null -eq $Value) { return @() }
    return @($Value | ForEach-Object { $_ })
}

function Require-Integer {
    param(
        [object] $InputObject,
        [string] $Name,
        [long] $Expected,
        [string] $Context
    )
    $actual = [long](Get-Value $InputObject $Name ([long]::MinValue))
    if ($actual -ne $Expected) {
        throw "$Context expected $Name=$Expected but received $actual."
    }
}

function Invoke-JsonRequest {
    param(
        [System.Net.Http.HttpClient] $Client,
        [string] $Method,
        [string] $Uri,
        [object] $Body = $null,
        [int] $ExpectedStatus = 200,
        [string] $Context = "request"
    )

    $request = $null
    $response = $null
    try {
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::new($Method.ToUpperInvariant()),
            $Uri)
        if ($null -ne $Body) {
            $jsonBody = $Body | ConvertTo-Json -Depth 12 -Compress
            $request.Content = [System.Net.Http.StringContent]::new(
                $jsonBody,
                [Text.Encoding]::UTF8,
                "application/json")
        }

        $clock = [Diagnostics.Stopwatch]::StartNew()
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $clock.Stop()
        $status = [int]$response.StatusCode
        if ($status -ne $ExpectedStatus) {
            throw "$Context failed with HTTP $status; response content is intentionally not logged."
        }

        $parsed = $null
        if (-not [string]::IsNullOrWhiteSpace($content)) {
            try {
                $parsed = $content | ConvertFrom-Json
            } catch {
                throw "$Context returned non-JSON content."
            }
        }
        return [pscustomobject]@{
            status = $status
            durationMs = $clock.ElapsedMilliseconds
            json = $parsed
        }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        if ($null -ne $request) { $request.Dispose() }
    }
}

function Get-UsageSnapshot {
    param(
        [System.Net.Http.HttpClient] $Client,
        [string] $Base,
        [long] $TargetSchoolId
    )

    $result = Invoke-JsonRequest -Client $Client -Method "GET" `
        -Uri "$Base/api/v1/students/imports/usage?days=$usageLookbackDays&limit=20000" `
        -Context "import usage reconciliation"
    $rows = @(Get-JsonItems $result.json | Where-Object {
        [long](Get-Value $_ "schoolId" -1) -eq $TargetSchoolId
    })
    $snapshot = [ordered]@{
        previewedBatches = [long]0
        completedBatches = [long]0
        unfinishedBatches = [long]0
        attemptedRows = [long]0
        insertedRows = [long]0
        skippedRows = [long]0
        sourceBytes = [long]0
    }
    foreach ($row in $rows) {
        foreach ($name in @($snapshot.Keys)) {
            $snapshot[$name] += [long](Get-Value $row $name 0)
        }
    }
    return [pscustomobject]$snapshot
}

function Get-FixturePreflight {
    param(
        [System.Net.Http.HttpClient] $Client,
        [string] $Base,
        [long] $TargetSchoolId
    )

    $classesResponse = Invoke-JsonRequest -Client $Client -Method "GET" `
        -Uri "$Base/api/v1/classes?schoolId=$TargetSchoolId" `
        -Context "fixture class preflight"
    $sectionsResponse = Invoke-JsonRequest -Client $Client -Method "GET" `
        -Uri "$Base/api/v1/schools/$TargetSchoolId/sections?active=true" `
        -Context "fixture section preflight"
    $classes = @(Get-JsonItems $classesResponse.json)
    $sections = @(Get-JsonItems $sectionsResponse.json)

    $requiredClasses = @(1..12 | ForEach-Object { "Scale Class $_" })
    $requiredSections = @(1..250 | ForEach-Object { "Scale $($_.ToString('0000'))" })
    $classNames = @($classes | ForEach-Object { [string](Get-Value $_ "name" "") })
    $sectionNames = @($sections | ForEach-Object { [string](Get-Value $_ "name" "") })
    $missingClasses = @($requiredClasses | Where-Object { $_ -notin $classNames })
    $missingSections = @($requiredSections | Where-Object { $_ -notin $sectionNames })
    $duplicateRequiredSections = @($sectionNames | Where-Object { $_ -in $requiredSections } |
        Group-Object | Where-Object { $_.Count -ne 1 } | ForEach-Object { $_.Name })
    if ($missingClasses.Count -gt 0 -or $missingSections.Count -gt 0 -or
        $duplicateRequiredSections.Count -gt 0) {
        throw "Fixture preflight failed before any import write: required SCALE classes/sections are missing or duplicated."
    }

    return [pscustomobject]@{
        classesReturned = $classes.Count
        sectionsReturned = $sections.Count
        requiredClassesMatched = $requiredClasses.Count
        requiredSectionsMatched = $requiredSections.Count
        duplicateRequiredSections = 0
        writesPerformed = $false
        classRequestDurationMs = $classesResponse.durationMs
        sectionRequestDurationMs = $sectionsResponse.durationMs
    }
}

function New-SyntheticRows {
    param(
        [int] $BatchNumber,
        [string] $RunId
    )

    $rows = [System.Collections.Generic.List[object]]::new($rowsPerBatch)
    for ($rowIndex = 1; $rowIndex -le $rowsPerBatch; $rowIndex++) {
        $globalIndex = (($BatchNumber - 1) * $rowsPerBatch) + $rowIndex
        $sectionNumber = [int][Math]::Ceiling($globalIndex / 40.0)
        $classNumber = (($sectionNumber - 1) % 12) + 1
        $rows.Add([ordered]@{
            __rowNumber = $rowIndex + 1
            Name = "Synthetic Onboarding Student $RunId-$($globalIndex.ToString('00000'))"
            Class = "Scale Class $classNumber"
            Section = "Scale $($sectionNumber.ToString('0000'))"
            AdmissionNo = "ONB-$RunId-$($globalIndex.ToString('00000'))"
            DateOfBirth = "2012-01-01"
            Gender = if ($globalIndex % 2 -eq 0) { "FEMALE" } else { "MALE" }
            FatherName = "Synthetic Guardian"
            Phone = "9000000000"
            Address = "Synthetic Address"
        })
    }
    return $rows
}

function Write-ImmutableEvidence {
    param(
        [string] $Path,
        [object] $Evidence
    )

    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $json = $Evidence | ConvertTo-Json -Depth 12
    $utf8 = [Text.UTF8Encoding]::new($false)
    $stream = $null
    $writer = $null
    try {
        $stream = [System.IO.FileStream]::new(
            $Path,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::None)
        $writer = [System.IO.StreamWriter]::new($stream, $utf8)
        $writer.Write($json)
        $writer.Write([Environment]::NewLine)
        $writer.Flush()
    } finally {
        if ($null -ne $writer) { $writer.Dispose() }
        elseif ($null -ne $stream) { $stream.Dispose() }
    }
}

if (-not $Execute) {
    Show-Plan
    exit 0
}

if ([string]::IsNullOrWhiteSpace($GatewayBaseUrl)) {
    throw "GatewayBaseUrl is required with -Execute."
}
if ($SchoolId -lt 900000000) {
    throw "SchoolId must be in the reserved synthetic range (>= 900000000)."
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
        throw "Remote dev onboarding writes require -AllowRemoteDevWrites."
    }
    if ($baseUri.DnsSafeHost -match "(^|[.-])prod([.-]|$)") {
        throw "Production-labelled hosts are forbidden for this dev-only verifier."
    }
    if ($baseUri.DnsSafeHost -notmatch "(^|[.-])dev([.-]|$)") {
        throw "Remote verification is restricted to a host whose DNS name contains a distinct dev label."
    }
    if ([string]::IsNullOrWhiteSpace($ExpectedDevHost) -or
        $baseUri.DnsSafeHost.ToLowerInvariant() -ne $ExpectedDevHost.Trim().ToLowerInvariant()) {
        throw "ExpectedDevHost must exactly match the remote dev gateway host."
    }
}

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
    $stamp = [datetime]::UtcNow.ToString("yyyyMMddHHmmssfff")
    $EvidencePath = Join-Path $repoRoot "artifacts/onboarding-certification/onboarding-10000-$stamp.json"
}
if (Test-Path -LiteralPath $EvidencePath) {
    throw "EvidencePath already exists; prior evidence is immutable and will not be overwritten."
}

$accessToken = [Environment]::GetEnvironmentVariable($AccessTokenEnvironmentVariable)
$loginEmail = [Environment]::GetEnvironmentVariable($LoginEmailEnvironmentVariable)
$loginPassword = [Environment]::GetEnvironmentVariable($LoginPasswordEnvironmentVariable)
$hasToken = -not [string]::IsNullOrWhiteSpace($accessToken)
$hasAnyLogin = -not [string]::IsNullOrWhiteSpace($loginEmail) -or
    -not [string]::IsNullOrWhiteSpace($loginPassword)
$hasCompleteLogin = -not [string]::IsNullOrWhiteSpace($loginEmail) -and
    -not [string]::IsNullOrWhiteSpace($loginPassword)
if ($hasToken -and $hasAnyLogin) {
    throw "Provide either a short-lived token or login credentials through environment variables, not both."
}
if (-not $hasToken -and -not $hasCompleteLogin) {
    throw "Provide a short-lived token or both login credential environment variables."
}

Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$previewedBatchCount = 0
$completedBatchCount = 0
$evidenceWritten = $false
try {
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    $base = $GatewayBaseUrl.TrimEnd("/")
    $authMode = "short-lived-token-env"
    if (-not $hasToken) {
        $login = Invoke-JsonRequest -Client $client -Method "POST" -Uri "$base/api/v1/auth/login" `
            -Body @{ email = $loginEmail; password = $loginPassword } -Context "dev login"
        $accessToken = [string](Get-Value $login.json "accessToken" "")
        if ([string]::IsNullOrWhiteSpace($accessToken)) {
            throw "Dev login response did not include an access token."
        }
        $authMode = "login-credentials-env"
    }
    $client.DefaultRequestHeaders.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $accessToken)

    $runId = [datetime]::UtcNow.ToString("yyyyMMddHHmmss") + "-" +
        [Guid]::NewGuid().ToString("N").Substring(0, 6)
    $syntheticRunLabel = "ONB-$runId"
    $startedAt = [datetime]::UtcNow
    $totalClock = [Diagnostics.Stopwatch]::StartNew()
    # Fail before the first preview if this is not the one reserved fixture school
    # that owns all 12 SCALE classes and 250 SCALE sections used below.
    $fixturePreflight = Get-FixturePreflight -Client $client -Base $base -TargetSchoolId $SchoolId
    $usageBefore = Get-UsageSnapshot -Client $client -Base $base -TargetSchoolId $SchoolId
    $beforeLedger = Invoke-JsonRequest -Client $client -Method "GET" `
        -Uri "$base/api/v1/students/imports/batches?limit=$batchLedgerLimit" `
        -Context "initial batch ledger preflight"
    $beforeBatchCount = @(Get-JsonItems $beforeLedger.json).Count

    $fileTokens = [System.Collections.Generic.List[string]]::new($batchCount)
    $batchEvidence = [System.Collections.Generic.List[object]]::new($batchCount)
    $finalBatchId = ""
    $finalJobId = ""
    $workloadClock = [Diagnostics.Stopwatch]::StartNew()
    for ($batchNumber = 1; $batchNumber -le $batchCount; $batchNumber++) {
        $rows = New-SyntheticRows -BatchNumber $batchNumber -RunId $runId
        if ($rows.Count -ne $rowsPerBatch) {
            throw "Batch $batchNumber generation did not produce exactly $rowsPerBatch rows."
        }

        $preview = Invoke-JsonRequest -Client $client -Method "POST" `
            -Uri "$base/api/v1/students/imports/preview" `
            -Body @{ schoolId = $SchoolId; rows = $rows } `
            -Context "batch $batchNumber preview"
        $previewedBatchCount++
        Require-Integer $preview.json "validCount" $rowsPerBatch "batch $batchNumber preview"
        Require-Integer $preview.json "errorCount" 0 "batch $batchNumber preview"
        Require-Integer $preview.json "warningCount" 0 "batch $batchNumber preview"
        if (@(Get-JsonItems (Get-Value $preview.json "rows" @())).Count -ne $rowsPerBatch) {
            throw "Batch $batchNumber preview did not return exactly $rowsPerBatch row results."
        }
        $fileToken = [string](Get-Value $preview.json "fileToken" "")
        $batchId = [string](Get-Value $preview.json "batchId" "")
        if ([string]::IsNullOrWhiteSpace($fileToken) -or [string]::IsNullOrWhiteSpace($batchId)) {
            throw "Batch $batchNumber preview did not return its opaque confirmation identifiers."
        }
        if ($fileTokens.Contains($fileToken)) {
            throw "Batch $batchNumber reused a file token; refusing to continue."
        }
        $fileTokens.Add($fileToken)

        $confirm = Invoke-JsonRequest -Client $client -Method "POST" `
            -Uri "$base/api/v1/students/imports/confirm" `
            -Body @{ schoolId = $SchoolId; fileToken = $fileToken } `
            -Context "batch $batchNumber confirm"
        Require-Integer $confirm.json "totalRows" $rowsPerBatch "batch $batchNumber confirm"
        Require-Integer $confirm.json "inserted" $rowsPerBatch "batch $batchNumber confirm"
        Require-Integer $confirm.json "skipped" 0 "batch $batchNumber confirm"
        if (-not [bool](Get-Value $confirm.json "done" $false)) {
            throw "Batch $batchNumber confirmation did not report done=true."
        }
        if (@(Get-JsonItems (Get-Value $confirm.json "insertedStudents" @())).Count -ne $rowsPerBatch) {
            throw "Batch $batchNumber confirmation did not return exactly $rowsPerBatch inserted mappings."
        }
        $confirmedBatchId = [string](Get-Value $confirm.json "batchId" "")
        $jobId = [string](Get-Value $confirm.json "jobId" "")
        if ($confirmedBatchId -ne $batchId -or [string]::IsNullOrWhiteSpace($jobId)) {
            throw "Batch $batchNumber confirmation identifiers did not reconcile with preview."
        }
        $completedBatchCount++

        $retryDurationMs = $null
        $retryPreservedResult = $null
        if ($batchNumber -eq $batchCount) {
            $retry = Invoke-JsonRequest -Client $client -Method "POST" `
                -Uri "$base/api/v1/students/imports/confirm" `
                -Body @{ schoolId = $SchoolId; fileToken = $fileToken } `
                -Context "completed final-batch retry"
            Require-Integer $retry.json "totalRows" $rowsPerBatch "completed final-batch retry"
            Require-Integer $retry.json "inserted" $rowsPerBatch "completed final-batch retry"
            Require-Integer $retry.json "skipped" 0 "completed final-batch retry"
            $retryBatchId = [string](Get-Value $retry.json "batchId" "")
            $retryJobId = [string](Get-Value $retry.json "jobId" "")
            $retryPreservedResult = $retryBatchId -eq $confirmedBatchId -and $retryJobId -eq $jobId
            if (-not $retryPreservedResult) {
                throw "Completed final-batch retry changed the batch or job identifier."
            }
            if (@(Get-JsonItems (Get-Value $retry.json "insertedStudents" @())).Count -ne $rowsPerBatch) {
                throw "Completed final-batch retry did not replay exactly $rowsPerBatch stored mappings."
            }
            $retryDurationMs = $retry.durationMs
            $finalBatchId = $confirmedBatchId
            $finalJobId = $jobId
        }

        $batchEvidence.Add([ordered]@{
            batchNumber = $batchNumber
            rows = $rowsPerBatch
            valid = $rowsPerBatch
            inserted = $rowsPerBatch
            skipped = 0
            previewDurationMs = $preview.durationMs
            confirmDurationMs = $confirm.durationMs
            completedRetryExecuted = $batchNumber -eq $batchCount
            completedRetryDurationMs = $retryDurationMs
            completedRetryPreservedResult = $retryPreservedResult
        })
        $rows = $null
    }
    $workloadClock.Stop()

    $afterLedger = Invoke-JsonRequest -Client $client -Method "GET" `
        -Uri "$base/api/v1/students/imports/batches?limit=$batchLedgerLimit" `
        -Context "final batch ledger reconciliation"
    $afterBatches = @(Get-JsonItems $afterLedger.json)
    $matchedBatches = [System.Collections.Generic.List[object]]::new($batchCount)
    foreach ($token in $fileTokens) {
        $matches = @($afterBatches | Where-Object { [string](Get-Value $_ "fileToken" "") -eq $token })
        if ($matches.Count -ne 1) {
            throw "Each generated batch token must appear exactly once in the final ledger."
        }
        $batch = $matches[0]
        if ([string](Get-Value $batch "status" "") -ne "DONE") {
            throw "A generated batch is not DONE in the final ledger."
        }
        Require-Integer $batch "totalRows" $rowsPerBatch "final batch ledger"
        Require-Integer $batch "validCount" $rowsPerBatch "final batch ledger"
        Require-Integer $batch "errorCount" 0 "final batch ledger"
        Require-Integer $batch "inserted" $rowsPerBatch "final batch ledger"
        Require-Integer $batch "skipped" 0 "final batch ledger"
        $matchedBatches.Add($batch)
    }
    if ($matchedBatches.Count -ne $batchCount) {
        throw "Final batch ledger did not reconcile exactly $batchCount generated batches."
    }
    $matchedBatchCount = $matchedBatches.Count

    $usageAfter = Get-UsageSnapshot -Client $client -Base $base -TargetSchoolId $SchoolId
    $usageDelta = [ordered]@{}
    foreach ($name in @("previewedBatches", "completedBatches", "unfinishedBatches", "attemptedRows",
            "insertedRows", "skippedRows", "sourceBytes")) {
        $usageDelta[$name] = [long](Get-Value $usageAfter $name 0) - [long](Get-Value $usageBefore $name 0)
    }
    $expectedDelta = [ordered]@{
        previewedBatches = [long]$batchCount
        completedBatches = [long]$batchCount
        unfinishedBatches = [long]0
        attemptedRows = [long]$totalRows
        insertedRows = [long]$totalRows
        skippedRows = [long]0
        sourceBytes = [long]0
    }
    foreach ($name in @($expectedDelta.Keys)) {
        if ([long]$usageDelta[$name] -ne [long]$expectedDelta[$name]) {
            throw "Import usage delta $name=$($usageDelta[$name]); expected $($expectedDelta[$name])."
        }
    }
    $totalClock.Stop()

    if ($completedBatchCount -ne $batchCount -or $previewedBatchCount -ne $batchCount -or
        [string]::IsNullOrWhiteSpace($finalBatchId) -or [string]::IsNullOrWhiteSpace($finalJobId)) {
        throw "The exact 20-batch workload or final retry proof did not complete."
    }

    # Drop token/row-bearing response objects before the evidence object is constructed.
    $fileTokens.Clear()
    $beforeLedger = $null
    $afterLedger = $null
    $afterBatches = $null
    $matchedBatches = $null
    $preview = $null
    $confirm = $null
    $retry = $null
    $runId = $null
    $finalBatchId = $null
    $finalJobId = $null

    $evidence = [ordered]@{
        generatedAtUtc = [datetime]::UtcNow.ToString("o")
        startedAtUtc = $startedAt.ToString("o")
        environment = if ($isLocal) { "local" } else { "dev" }
        gatewayHost = $baseUri.DnsSafeHost
        syntheticSchoolId = $SchoolId
        passed = $true
        workload = [ordered]@{
            batches = $batchCount
            rowsPerBatch = $rowsPerBatch
            totalRows = $totalRows
            sequential = $true
            maxConcurrentBatches = 1
            fixtureClassPattern = "Scale Class 1..12"
            fixtureSectionPattern = "Scale 0001..0250"
            syntheticRunLabel = $syntheticRunLabel
            uniqueSyntheticAdmissionStrategy = "syntheticRunLabel plus a five-digit sequence; individual values omitted"
        }
        authentication = [ordered]@{
            mode = $authMode
            sourcedFromEnvironment = $true
            tokenOrCredentialsPersisted = $false
        }
        fixturePreflight = $fixturePreflight
        timings = [ordered]@{
            workloadDurationMs = $workloadClock.ElapsedMilliseconds
            totalDurationMs = $totalClock.ElapsedMilliseconds
            batches = $batchEvidence
        }
        completedRetry = [ordered]@{
            batchNumber = $batchCount
            executed = $true
            sameBatchAndJobResult = $true
            inserted = $rowsPerBatch
            skipped = 0
        }
        batchLedgerReconciliation = [ordered]@{
            endpointLimit = $batchLedgerLimit
            initialVisibleBatchCount = $beforeBatchCount
            generatedBatchesMatchedExactlyOnce = $matchedBatchCount
            done = $matchedBatchCount
            rows = $totalRows
            inserted = $totalRows
            skipped = 0
            tokensOrOpaqueIdsPersisted = $false
        }
        usageReconciliation = [ordered]@{
            lookbackDays = $usageLookbackDays
            before = $usageBefore
            after = $usageAfter
            delta = $usageDelta
            expectedDelta = $expectedDelta
            exactMatch = $true
        }
        evidenceWriteMode = "CREATE_NEW_NO_OVERWRITE"
        containsTokensOrStudentPii = $false
        cleanupRequired = $true
        cleanupScope = "the 20 batches and 10,000 students selected by syntheticRunLabel, plus their approved dependent synthetic effects"
        limits = @(
            "JSON preview/confirm only; no photo attachment or source-file storage proof",
            "Sequential single-school execution; no multi-school contention or distinct-instance proof",
            "Adds 10,000 students to an existing reserved fixture school; it does not certify empty-school setup",
            "Not a capacity, soak, failover, provider, privacy-policy or production certification",
            "No automatic cleanup is performed; operator reconciliation and approved deletion are mandatory",
            "Authentication must remain valid for the complete run"
        )
    }

    Write-ImmutableEvidence -Path $EvidencePath -Evidence $evidence
    $evidenceWritten = $true
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $EvidencePath).Hash.ToLowerInvariant()
    Write-Output "IMS_DEV_ONBOARDING_10000_RESULT|passed=true|batches=$batchCount|rows=$totalRows|durationMs=$($totalClock.ElapsedMilliseconds)|sha256=$hash|evidence=$EvidencePath"
} finally {
    if (-not $evidenceWritten -and $previewedBatchCount -gt 0) {
        Write-Warning "Execution stopped after creating $previewedBatchCount preview batch(es) and completing $completedBatchCount for synthetic run $syntheticRunLabel; cleanup/reconciliation may be required. No token or individual row value is logged."
    }
    $client.Dispose()
    $accessToken = $null
    $login = $null
    $loginEmail = $null
    $loginPassword = $null
}

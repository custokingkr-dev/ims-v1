[CmdletBinding()]
param(
    [string]$GatewayBaseUrl = "http://localhost",
    [string]$PostgresContainer = "custoking-postgres",
    [string]$Database = "postgres",
    [string]$DbUser = "postgres",
    [string]$OutputJson = "artifacts/logical-e2e-result.json",
    [int]$TimeoutSeconds = 30,
    [switch]$StartStack,
    [switch]$SkipBaselineSeed,
    [switch]$SkipEnforceGateway,
    [switch]$IncludeRouteSmoke,
    [switch]$IncludeFeatureSmoke,
    [switch]$AllowRemoteWrites
)

$ErrorActionPreference = "Stop"

try { Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue } catch {}

$script:results = New-Object System.Collections.Generic.List[object]
$script:phase = "bootstrap"
$script:state = [ordered]@{}
$script:canUseLocalDb = $false
$script:photoStorageConfigured = $true
$script:runId = (Get-Date -Format "yyyyMMddHHmmss")
$script:tempFiles = New-Object System.Collections.Generic.List[string]

function Join-Url {
    param([string]$Base, [string]$Path)
    $Base.TrimEnd("/") + $Path
}

function Encode-QueryValue {
    param([object]$Value)
    [System.Uri]::EscapeDataString([string]$Value)
}

function Test-LocalGateway {
    try {
        $uri = [Uri]$GatewayBaseUrl
        return @("localhost", "127.0.0.1", "::1") -contains $uri.Host.ToLowerInvariant()
    } catch {
        return $false
    }
}

function Test-ContainerRunning {
    param([string]$Name)
    try {
        $running = docker inspect -f "{{.State.Running}}" $Name 2>$null
        return (($running | Select-Object -Last 1) -eq "true")
    } catch {
        return $false
    }
}

function Get-ContainerEnvValue {
    param([string]$Container, [string]$Name)
    try {
        $value = docker exec $Container sh -c "printenv $Name" 2>$null
        return [string](($value | Select-Object -Last 1).Trim())
    } catch {
        return ""
    }
}

function Test-StudentPhotoStorageConfigured {
    if ($isLocalGateway -and (Test-ContainerRunning "custoking-school-core-service")) {
        return -not [string]::IsNullOrWhiteSpace((Get-ContainerEnvValue "custoking-school-core-service" "STUDENT_PHOTO_BUCKET"))
    }
    return $true
}

function Add-Result {
    param(
        [string]$Name,
        [string]$Method = "",
        [string]$Path = "",
        [string]$Actor = "",
        [bool]$Passed,
        [string]$Detail = "",
        [bool]$Skipped = $false
    )

    $script:results.Add([pscustomobject]@{
        phase = $script:phase
        name = $Name
        method = $Method
        path = $Path
        actor = $Actor
        passed = $Passed
        skipped = $Skipped
        detail = $Detail
    }) | Out-Null
}

function ConvertFrom-JsonSafe {
    param([string]$Content)
    if ([string]::IsNullOrWhiteSpace($Content)) { return $null }
    try {
        return $Content | ConvertFrom-Json
    } catch {
        return $null
    }
}

function Read-ErrorResponseBody {
    param($ErrorRecord)
    $response = $ErrorRecord.Exception.Response
    if ($null -eq $response) { return "" }
    try {
        if ($response -is [System.Net.Http.HttpResponseMessage]) {
            return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
    } catch {}
    try {
        $stream = $response.GetResponseStream()
        if ($null -eq $stream) { return "" }
        $reader = New-Object System.IO.StreamReader($stream)
        return $reader.ReadToEnd()
    } catch {
        return ""
    }
}

function Status-CodeFromError {
    param($ErrorRecord)
    try {
        if ($ErrorRecord.Exception.Response -and $ErrorRecord.Exception.Response.StatusCode) {
            return [int]$ErrorRecord.Exception.Response.StatusCode
        }
    } catch {}
    return $null
}

function Invoke-Api {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Path,
        [string]$Token,
        [string]$Actor,
        [object]$Body = $null,
        [int[]]$ExpectedStatus = @(200),
        [hashtable]$Headers = @{},
        [switch]$NonCritical
    )

    $mergedHeaders = @{}
    foreach ($key in $Headers.Keys) { $mergedHeaders[$key] = $Headers[$key] }
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $mergedHeaders["Authorization"] = "Bearer $Token"
    }

    $parameters = @{
        Uri = Join-Url $GatewayBaseUrl $Path
        Method = $Method
        Headers = $mergedHeaders
        TimeoutSec = $TimeoutSeconds
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 40 }
    }

    try {
        $response = Invoke-WebRequest @parameters
        $status = [int]$response.StatusCode
        $content = [string]$response.Content
        $passed = $ExpectedStatus -contains $status
        Add-Result $Name $Method $Path $Actor $passed "HTTP $status"
        if (-not $passed -and -not $NonCritical) {
            throw "$Name returned HTTP $status, expected $($ExpectedStatus -join ', ')"
        }
        return [pscustomobject]@{
            statusCode = $status
            content = $content
            json = ConvertFrom-JsonSafe $content
            headers = $response.Headers
            passed = $passed
        }
    } catch {
        if ($_.Exception.Message.StartsWith("$Name returned HTTP ")) {
            throw
        }
        $status = Status-CodeFromError $_
        $content = Read-ErrorResponseBody $_
        $passed = $false
        if ($null -ne $status) { $passed = $ExpectedStatus -contains [int]$status }
        $detail = if ($null -eq $status) { $_.Exception.Message } else { "HTTP $status $content" }
        Add-Result $Name $Method $Path $Actor $passed $detail
        if (-not $passed -and -not $NonCritical) { throw }
        return [pscustomobject]@{
            statusCode = $status
            content = $content
            json = ConvertFrom-JsonSafe $content
            headers = $null
            passed = $passed
        }
    }
}

function Invoke-MultipartApi {
    param(
        [string]$Name,
        [string]$Path,
        [string]$Token,
        [string]$Actor,
        [hashtable]$Fields = @{},
        [object[]]$Files = @(),
        [int[]]$ExpectedStatus = @(200),
        [switch]$NonCritical
    )

    $client = [System.Net.Http.HttpClient]::new()
    $form = [System.Net.Http.MultipartFormDataContent]::new()
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
        if (-not [string]::IsNullOrWhiteSpace($Token)) {
            $client.DefaultRequestHeaders.Authorization =
                [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
        }
        foreach ($key in $Fields.Keys) {
            $form.Add([System.Net.Http.StringContent]::new([string]$Fields[$key]), $key)
        }
        foreach ($file in $Files) {
            $bytes = [System.IO.File]::ReadAllBytes($file.Path)
            $content = [System.Net.Http.ByteArrayContent]::new($bytes)
            if ($file.ContentType) {
                $content.Headers.ContentType =
                    [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse([string]$file.ContentType)
            }
            $form.Add($content, [string]$file.Name, [string]$file.FileName)
        }
        $response = $client.PostAsync((Join-Url $GatewayBaseUrl $Path), $form).GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        $contentText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $passed = $ExpectedStatus -contains $status
        Add-Result $Name "POST" $Path $Actor $passed "HTTP $status"
        if (-not $passed -and -not $NonCritical) {
            throw "$Name returned HTTP $status, expected $($ExpectedStatus -join ', '): $contentText"
        }
        return [pscustomobject]@{
            statusCode = $status
            content = $contentText
            json = ConvertFrom-JsonSafe $contentText
            passed = $passed
        }
    } finally {
        $form.Dispose()
        $client.Dispose()
    }
}

function Get-Value {
    param([object]$Object, [string]$Name, [object]$Default = $null)
    if ($null -eq $Object) { return $Default }
    if ($Object -is [System.Collections.IDictionary] -and $Object.Contains($Name)) {
        return $Object[$Name]
    }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -ne $prop) { return $prop.Value }
    return $Default
}

function Get-Array {
    param([object]$Value)
    if ($null -eq $Value) { return @() }
    if ($Value -is [string]) { return @($Value) }
    if ($Value -is [System.Collections.IEnumerable]) { return @($Value) }
    return @($Value)
}

function Assert-E2E {
    param(
        [string]$Name,
        [bool]$Condition,
        [string]$Detail,
        [switch]$NonCritical
    )
    Add-Result $Name "ASSERT" "" "" $Condition $Detail
    if (-not $Condition -and -not $NonCritical) {
        throw "Assertion failed: $Name - $Detail"
    }
}

function Invoke-Phase {
    param([string]$Name, [scriptblock]$Body)
    $previous = $script:phase
    $script:phase = $Name
    try {
        & $Body
    } catch {
        Add-Result "$Name failed" "" "" "" $false $_.Exception.Message
        throw
    } finally {
        $script:phase = $previous
    }
}

function Invoke-Psql {
    param([string]$Sql, [switch]$Scalar)
    if (-not $script:canUseLocalDb) {
        throw "Local Postgres container $PostgresContainer is not available for DB assertion."
    }
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        $prefix = if ($Scalar) { "\pset tuples_only on`n\pset format unaligned`n" } else { "" }
        Set-Content -LiteralPath $tmp -Value ($prefix + $Sql) -NoNewline -Encoding UTF8
        docker cp $tmp "${PostgresContainer}:/tmp/ims-logical-e2e.sql" | Out-Null
        $output = docker exec $PostgresContainer psql -v ON_ERROR_STOP=1 -U $DbUser -d $Database -f /tmp/ims-logical-e2e.sql
        if ($LASTEXITCODE -ne 0) { throw "psql failed with exit $LASTEXITCODE" }
        if ($Scalar) {
            return (($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) | Select-Object -Last 1).Trim()
        }
        return $output
    } finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Sql-Quote {
    param([object]$Value)
    "'" + ([string]$Value).Replace("'", "''") + "'"
}

function Assert-DbCount {
    param([string]$Name, [string]$Sql, [int]$Minimum = 1, [switch]$NonCritical)
    if (-not $script:canUseLocalDb) {
        Add-Result $Name "ASSERT" "" "" $true "skipped: local DB unavailable" $true
        return
    }
    $value = Invoke-Psql -Sql $Sql -Scalar
    $count = [int]$value
    Assert-E2E $Name ($count -ge $Minimum) "count=$count minimum=$Minimum" -NonCritical:$NonCritical
}

function Assert-OutboxEvent {
    param(
        [string]$Schema,
        [string]$EventType,
        [object]$AggregateId,
        [string]$Name = ""
    )
    $checkName = if ([string]::IsNullOrWhiteSpace($Name)) {
        "outbox:{0}:{1}:{2}" -f $Schema, $EventType, $AggregateId
    } else {
        $Name
    }
    $sql = "SELECT COUNT(*) FROM $Schema.outbox_events WHERE event_type = $(Sql-Quote $EventType) AND aggregate_id = $(Sql-Quote $AggregateId);"
    Assert-DbCount $checkName $sql 1
}

function Wait-Gateway {
    param([int]$Seconds = 180)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri (Join-Url $GatewayBaseUrl "/gateway-health") -TimeoutSec 5
            if ([int]$response.StatusCode -eq 200) { return }
        } catch {}
        Start-Sleep -Seconds 3
    }
    throw "Timed out waiting for gateway health at $GatewayBaseUrl."
}

function Login {
    param([string]$Email, [string]$Password, [string]$Actor)
    $response = Invoke-Api "auth:login:$Actor" "POST" "/api/v1/auth/login" "" $Actor @{
        email = $Email
        password = $Password
    } @(200)
    $token = Get-Value $response.json "accessToken" ""
    Assert-E2E "auth token returned for $Actor" (-not [string]::IsNullOrWhiteSpace($token)) $Email
    return [pscustomobject]@{
        token = $token
        userId = Get-Value $response.json "userId"
        role = Get-Value $response.json "role"
        branchId = Get-Value $response.json "branchId"
        operatorSchools = @(Get-Array (Get-Value $response.json "operatorSchools"))
        raw = $response.json
    }
}

function Run-LocalCommand {
    param([string]$Name, [scriptblock]$Body)
    try {
        & $Body
        Add-Result $Name "LOCAL" "" "" $true "ok"
    } catch {
        Add-Result $Name "LOCAL" "" "" $false $_.Exception.Message
        throw
    }
}

function New-TempFilePath {
    param([string]$Extension)
    $basePath = [System.IO.Path]::GetTempFileName()
    Remove-Item -LiteralPath $basePath -Force -ErrorAction SilentlyContinue
    $path = [System.IO.Path]::ChangeExtension($basePath, $Extension)
    $script:tempFiles.Add($path) | Out-Null
    return $path
}

function Finalize-Results {
    $failures = @($script:results | Where-Object { -not $_.passed -and -not $_.skipped })
    $skipped = @($script:results | Where-Object { $_.skipped })
    $outDir = Split-Path -Parent $OutputJson
    if (-not [string]::IsNullOrWhiteSpace($outDir)) {
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    }
    [ordered]@{
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        runId = $script:runId
        gatewayBaseUrl = $GatewayBaseUrl
        localDbAssertions = $script:canUseLocalDb
        total = $script:results.Count
        passed = $script:results.Count - $failures.Count
        failures = $failures.Count
        skipped = $skipped.Count
        state = $script:state
        checks = $script:results
    } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $OutputJson -Encoding UTF8

    Write-Host "Logical E2E total=$($script:results.Count) passed=$($script:results.Count - $failures.Count) failures=$($failures.Count) skipped=$($skipped.Count)"
    Write-Host "Result JSON: $OutputJson"
    if ($failures.Count -gt 0) {
        $failures | Format-Table phase, name, method, path, actor, detail -Wrap
        exit 1
    }
}

$isLocalGateway = Test-LocalGateway
if (-not $isLocalGateway -and -not $AllowRemoteWrites) {
    throw "This logical E2E test writes data. GatewayBaseUrl must be localhost unless -AllowRemoteWrites is explicitly passed."
}

try {
    Invoke-Phase "bootstrap" {
        if ($StartStack) {
            Run-LocalCommand "docker compose full up" {
                docker compose --profile full up -d --build
                if ($LASTEXITCODE -ne 0) { throw "docker compose failed with exit $LASTEXITCODE" }
            }
            Run-LocalCommand "ensure app_rt grants" {
                & "$PSScriptRoot\ensure-app-rt-local.ps1" `
                    -PostgresContainer $PostgresContainer `
                    -Database $Database `
                    -DbUser $DbUser `
                    -TimeoutSeconds 300 `
                    -RequiredSchemas @("identity","tenant_school","student","attendance","fee","catalog","workflow","firefighting","reporting","notification","audit","billing")
                if ($LASTEXITCODE -ne 0) { throw "ensure-app-rt-local failed with exit $LASTEXITCODE" }
            }
        }

        if ($isLocalGateway -and -not $SkipEnforceGateway) {
            Run-LocalCommand "gateway enforce auth mode" {
                docker compose -f docker-compose.yml -f docker-compose.bola.yml up -d --force-recreate api-gateway
                if ($LASTEXITCODE -ne 0) { throw "gateway enforce compose failed with exit $LASTEXITCODE" }
            }
        }

        Wait-Gateway -Seconds 180
        $script:canUseLocalDb = $isLocalGateway -and (Test-ContainerRunning $PostgresContainer)
        Add-Result "local DB assertions available" "CHECK" "" "" $true $script:canUseLocalDb
        $script:photoStorageConfigured = Test-StudentPhotoStorageConfigured
        Add-Result "student photo storage configured" "CHECK" "" "" $true $script:photoStorageConfigured (-not $script:photoStorageConfigured)

        if (-not $SkipBaselineSeed -and $script:canUseLocalDb) {
            Run-LocalCommand "seed local development users" {
                & "$PSScriptRoot\ensure-local-dev-users.ps1" `
                    -PostgresContainer $PostgresContainer `
                    -Database $Database `
                    -DbUser $DbUser `
                    -TimeoutSeconds 180
                if ($LASTEXITCODE -ne 0) { throw "ensure-local-dev-users failed with exit $LASTEXITCODE" }
            }
        }

        if ($IncludeRouteSmoke) {
            Run-LocalCommand "gateway route smoke" {
                & "$PSScriptRoot\smoke-gateway-routes.ps1" -GatewayBaseUrl $GatewayBaseUrl -TimeoutSeconds $TimeoutSeconds
                if ($LASTEXITCODE -ne 0) { throw "smoke-gateway-routes failed with exit $LASTEXITCODE" }
            }
        }

        if ($IncludeFeatureSmoke) {
            Run-LocalCommand "microservice feature smoke" {
                & "$PSScriptRoot\smoke-microservice-features.ps1" `
                    -GatewayBaseUrl $GatewayBaseUrl `
                    -PostgresContainer $PostgresContainer `
                    -Database $Database `
                    -DbUser $DbUser `
                    -TimeoutSeconds $TimeoutSeconds
                if ($LASTEXITCODE -ne 0) { throw "smoke-microservice-features failed with exit $LASTEXITCODE" }
            }
        }
    }

    $script:super = $null
    $script:admin = $null
    $script:operator = $null
    $script:schoolId = $null
    $script:otherSchoolId = $null
    $script:class1 = "1"
    $script:class2 = "2"
    $script:section1A = $null
    $script:section2A = $null
    $script:primaryStudentId = $null
    $script:importedStudentId = $null
    $script:feeAssignmentId = $null
    $script:paymentId = $null
    $script:catalogOrderId = $null
    $script:fireCode = $null

    Invoke-Phase "auth and onboarding" {
        $script:super = Login "local-superadmin@custoking.local" "password" "superadmin"
        Invoke-Api "rbac permissions readable" "GET" "/api/v1/rbac/permissions" $super.token "superadmin" | Out-Null

        Invoke-Api "anonymous protected route is rejected" "GET" "/api/v1/schools" "" "anonymous" $null @(401) | Out-Null

        $short = "LE2E$($script:runId.Substring($script:runId.Length - 8))"
        $schoolResponse = Invoke-Api "school create" "POST" "/api/v1/schools" $super.token "superadmin" @{
            name = "Logical E2E School $script:runId"
            shortCode = $short
            city = "Bengaluru"
            state = "KA"
            contactEmail = "logical-e2e-$script:runId@custoking.local"
            contactPhone = "9999999999"
            classCount = 5
            sectionCount = 2
            academicYearStartMonth = 6
            financialYearStartMonth = 4
        } @(201)
        $script:schoolId = [long](Get-Value $schoolResponse.json "id")
        $script:state.schoolId = $schoolId
        Assert-E2E "school created id" ($schoolId -gt 0) "schoolId=$schoolId"
        Assert-E2E "school academic month" ([int](Get-Value $schoolResponse.json "academicYearStartMonth") -eq 6) "expected 6"
        Assert-E2E "school financial month" ([int](Get-Value $schoolResponse.json "financialYearStartMonth") -eq 4) "expected 4"
        Assert-OutboxEvent "tenant_school" "school.upserted.v1" $schoolId "school create outbox"

        $otherResponse = Invoke-Api "other school create" "POST" "/api/v1/schools" $super.token "superadmin" @{
            name = "Logical E2E Other $script:runId"
            shortCode = "OE2E$($script:runId.Substring($script:runId.Length - 8))"
            city = "Bengaluru"
            state = "KA"
            contactEmail = "logical-e2e-other-$script:runId@custoking.local"
            contactPhone = "9999999998"
            classCount = 1
            sectionCount = 1
            academicYearStartMonth = 4
            financialYearStartMonth = 4
        } @(201)
        $script:otherSchoolId = [long](Get-Value $otherResponse.json "id")
        $script:state.otherSchoolId = $otherSchoolId

        $otherAdminEmail = "logical-disabled-admin-$script:runId@custoking.local"
        Invoke-Api "disabled school admin provision" "POST" "/api/v1/schools/$otherSchoolId/admin" $super.token "superadmin" @{
            fullName = "Logical Disabled Admin $script:runId"
            email = $otherAdminEmail
            temporaryPassword = "password"
        } @(201) | Out-Null
        $otherAdmin = Login $otherAdminEmail "password" "disabled-school-admin"
        Invoke-Api "erp gate rejects disabled school" "GET" "/api/v1/classes?schoolId=$otherSchoolId" $otherAdmin.token "disabled-school-admin" $null @(403) | Out-Null

        foreach ($module in @("STUDENTS","ATTENDANCE","FEES","INVOICES","PAYMENTS","REPORTS","ORDERS","FIREFIGHTING")) {
            Invoke-Api "module enable $module" "PUT" "/api/v1/schools/$schoolId/modules/$module" $super.token "superadmin" @{
                enabled = $true
                plan = "E2E"
                notes = "logical e2e $script:runId"
            } | Out-Null
        }
        $modulesResponse = Invoke-Api "active modules list" "GET" "/api/v1/schools/$schoolId/modules/active" $super.token "superadmin"
        $activeCodes = @(Get-Array $modulesResponse.json | ForEach-Object { Get-Value $_ "moduleCode" (Get-Value $_ "code") })
        foreach ($module in @("STUDENTS","ATTENDANCE","FEES","ORDERS","FIREFIGHTING")) {
            Assert-E2E "module active $module" ($activeCodes -contains $module) ($activeCodes -join ",")
        }

        $adminEmail = "logical-admin-$script:runId@custoking.local"
        $operatorEmail = "logical-operator-$script:runId@custoking.local"
        $adminCreated = Invoke-Api "school admin provision" "POST" "/api/v1/schools/$schoolId/admin" $super.token "superadmin" @{
            fullName = "Logical Admin $script:runId"
            email = $adminEmail
            temporaryPassword = "password"
        } @(201)
        $operatorCreated = Invoke-Api "operator provision" "POST" "/api/v1/schools/$schoolId/operations-user" $super.token "superadmin" @{
            fullName = "Logical Operator $script:runId"
            email = $operatorEmail
            temporaryPassword = "password"
        } @(201)
        $operatorUserId = [long](Get-Value $operatorCreated.json "userId")
        $script:state.operatorUserId = $operatorUserId
        Invoke-Api "operator school assignment" "POST" "/api/v1/rbac/users/$operatorUserId/operator-schools" $super.token "superadmin" @{
            schoolIds = @($schoolId)
        } | Out-Null
        $operatorSchools = Invoke-Api "operator school assignment readback" "GET" "/api/v1/rbac/users/$operatorUserId/operator-schools" $super.token "superadmin"
        $assignedIds = @(Get-Array $operatorSchools.json | ForEach-Object { [long](Get-Value $_ "schoolId" 0) })
        Assert-E2E "operator school assignment persisted" ($assignedIds -contains $schoolId) ($assignedIds -join ",")

        $script:admin = Login $adminEmail "password" "school-admin"
        $script:operator = Login $operatorEmail "password" "operator"
        Assert-E2E "admin scoped to school" ([long](Get-Value $admin "branchId" 0) -eq $schoolId) "branchId=$(Get-Value $admin "branchId")"
        $operatorSchoolIds = @(Get-Value $operator "operatorSchools" | ForEach-Object { [long]$_ })
        Assert-E2E "operator token carries assigned school" ($operatorSchoolIds -contains $schoolId) "operatorSchools=$($operatorSchoolIds -join ',')"

        Invoke-Api "admin cross-school student list rejected" "GET" "/api/v1/students?schoolId=$otherSchoolId&page=0&size=1" $admin.token "school-admin" $null @(403) | Out-Null
    }

    Invoke-Phase "erp setup" {
        $classesResponse = Invoke-Api "classes list" "GET" "/api/v1/classes?schoolId=$schoolId" $admin.token "school-admin"
        $classes = @(Get-Array $classesResponse.json)
        Assert-E2E "configured classes visible" ($classes.Count -ge 3) "count=$($classes.Count)"
        $classIds = @($classes | ForEach-Object { [string](Get-Value $_ "id") })
        Assert-E2E "class 1 visible" ($classIds -contains $class1) ($classIds -join ",")
        Assert-E2E "class 2 visible" ($classIds -contains $class2) ($classIds -join ",")

        $sections1 = Invoke-Api "class 1 sections" "GET" "/api/v1/classes/$class1/sections?schoolId=$schoolId" $admin.token "school-admin"
        $sectionRows1 = @(Get-Array $sections1.json)
        $section1ARow = $sectionRows1 | Where-Object { (Get-Value $_ "name") -eq "A" } | Select-Object -First 1
        $script:section1A = [string](Get-Value $section1ARow "id")
        Assert-E2E "section 1A generated" ($section1A -eq "$schoolId-$class1-A") "sectionId=$section1A"

        $sections2 = Invoke-Api "class 2 sections" "GET" "/api/v1/classes/$class2/sections?schoolId=$schoolId" $admin.token "school-admin"
        $sectionRows2 = @(Get-Array $sections2.json)
        $section2ARow = $sectionRows2 | Where-Object { (Get-Value $_ "name") -eq "A" } | Select-Object -First 1
        $script:section2A = [string](Get-Value $section2ARow "id")
        Assert-E2E "section 2A generated" ($section2A -eq "$schoolId-$class2-A") "sectionId=$section2A"

        $staff = Invoke-Api "staff create" "POST" "/api/v1/workspace/staff" $admin.token "school-admin" @{
            schoolId = $schoolId
            name = "Logical Teacher $script:runId"
            designation = "Teacher"
            department = "Academics"
            monthlySalary = 50000
        }
        $staffId = [long](Get-Value $staff.json "id" 0)
        Assert-E2E "staff created" ($staffId -gt 0) "staffId=$staffId"

        $schedule = Invoke-Api "timetable bell schedule create" "POST" "/api/v1/timetable/bell-schedules" $admin.token "school-admin" @{
            name = "Logical Schedule $script:runId"
        }
        $scheduleId = [long](Get-Value $schedule.json "id" 0)
        $period = Invoke-Api "timetable period create" "POST" "/api/v1/timetable/bell-schedules/$scheduleId/periods" $admin.token "school-admin" @{
            label = "P1"
            start = "09:00"
            end = "09:40"
            isBreak = $false
            sortOrder = 1
        }
        $periodId = [long](Get-Value $period.json "id" 0)
        Invoke-Api "timetable subject create" "POST" "/api/v1/timetable/class-subjects" $admin.token "school-admin" @{
            classId = $class1
            subjectName = "Mathematics"
        } | Out-Null
        Invoke-Api "timetable class schedule assign" "PUT" "/api/v1/timetable/class-schedules/$class1" $admin.token "school-admin" @{
            scheduleId = $scheduleId
        } @(200, 204) | Out-Null
        $entry = Invoke-Api "timetable entry upsert" "PUT" "/api/v1/timetable/entry" $admin.token "school-admin" @{
            sectionId = $section1A
            day = "MONDAY"
            periodId = $periodId
            subjectName = "Mathematics"
            teacherId = $staffId
        }
        Assert-E2E "timetable entry saved" ($null -ne (Get-Value $entry.json "entry")) "entry response present"
        $grid = $null
        $periodCount = 0
        $entryCount = 0
        $deadline = (Get-Date).AddSeconds(15)
        do {
            $grid = Invoke-Api "timetable readback" "GET" "/api/v1/timetable?schoolId=$schoolId&sectionId=$(Encode-QueryValue $section1A)" $admin.token "school-admin"
            $periodCount = @(Get-Array (Get-Value $grid.json "periods")).Count
            $entryCount = @(Get-Array (Get-Value $grid.json "entries")).Count
            if ($periodCount -ge 1 -and $entryCount -ge 1) { break }
            Start-Sleep -Seconds 1
        } while ((Get-Date) -lt $deadline)
        Assert-E2E "timetable readback has periods" ($periodCount -ge 1) "periods=$periodCount entries=$entryCount"
        Assert-E2E "timetable readback has entry" ($entryCount -ge 1) "periods=$periodCount entries=$entryCount"
    }

    Invoke-Phase "students import and lifecycle" {
        $studentCreate = Invoke-Api "student create" "POST" "/api/v1/workspace/students" $admin.token "school-admin" @{
            schoolId = $schoolId
            admissionNumber = "ADM-$script:runId"
            fullName = "Logical Student $script:runId"
            classId = $class1
            sectionId = $section1A
            rollNo = "1"
            dateOfBirth = "2015-05-12"
            gender = "Female"
            fatherName = "Logical Father"
            fatherContactNumber = "9876543210"
            motherName = "Logical Mother"
            phone = "9876543210"
            houseNumber = "42"
            street = "Test Street"
            locality = "Test Locality"
            city = "Bengaluru"
            state = "KA"
            pinCode = "560001"
        }
        $script:primaryStudentId = [long](Get-Value $studentCreate.json "id" 0)
        $script:state.primaryStudentId = $primaryStudentId
        Assert-E2E "student created" ($primaryStudentId -gt 0) "studentId=$primaryStudentId"
        Assert-OutboxEvent "tenant_school" "student.upserted.v1" $primaryStudentId "student create outbox"

        $detail = Invoke-Api "student detail" "GET" "/api/v1/students/$primaryStudentId/workspace" $admin.token "school-admin"
        Assert-E2E "student detail has class" ([string](Get-Value $detail.json "classId") -eq $class1) "classId=$(Get-Value $detail.json "classId")"

        $updated = Invoke-Api "student update" "PUT" "/api/v1/workspace/students/$primaryStudentId" $admin.token "school-admin" @{
            schoolId = $schoolId
            admissionNumber = "ADM-$script:runId"
            fullName = "Logical Student Updated $script:runId"
            classId = $class1
            sectionId = $section1A
            rollNo = "1"
            phone = "9876500000"
            city = "Bengaluru"
            state = "KA"
        }
        Assert-E2E "student update reflected" ([string](Get-Value $updated.json "fullName") -match "Updated") "fullName=$(Get-Value $updated.json "fullName")"

        $photoPath = New-TempFilePath ".png"
        [System.IO.File]::WriteAllBytes($photoPath, [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="))
        if ($script:photoStorageConfigured) {
            $photo = Invoke-MultipartApi "student photo upload" "/api/v1/students/$primaryStudentId/photo" $admin.token "school-admin" @{} @(
                @{ Name = "file"; Path = $photoPath; FileName = "logical-e2e-photo.png"; ContentType = "image/png" }
            )
            Assert-E2E "student photo url stored" (-not [string]::IsNullOrWhiteSpace([string](Get-Value $photo.json "photoUrl"))) "photoUrl=$(Get-Value $photo.json "photoUrl")"
        } else {
            Add-Result "student photo upload skipped" "SKIP" "/api/v1/students/$primaryStudentId/photo" "school-admin" $true "STUDENT_PHOTO_BUCKET is not configured in local compose" $true
        }

        $row = @{
            Name = "Logical Import $script:runId"
            Class = "1"
            Section = "A"
            AdmissionNo = "IMP-$script:runId"
            DateOfBirth = "2016-06-01"
            Gender = "Male"
            FatherName = "Import Father"
            Phone = "9876543211"
            Address = "Import Address"
        }
        $rowsJson = "[" + ($row | ConvertTo-Json -Depth 10 -Compress) + "]"
        $csvPath = New-TempFilePath ".csv"
        Set-Content -LiteralPath $csvPath -Value "Name,Class,Section,AdmissionNo,DateOfBirth,Gender,FatherName,Phone,Address`nLogical Import $script:runId,1,A,IMP-$script:runId,2016-06-01,Male,Import Father,9876543211,Import Address" -Encoding UTF8
        $preview = Invoke-MultipartApi "student bulk import preview" "/api/v1/students/import/upload-preview" $admin.token "school-admin" @{
            rowsJson = $rowsJson
            schoolId = [string]$schoolId
        } @(
            @{ Name = "file"; Path = $csvPath; FileName = "logical-e2e-students-$script:runId.csv"; ContentType = "text/csv" }
        )
        Assert-E2E "bulk import preview valid" ([int](Get-Value $preview.json "validCount" 0) -eq 1) "validCount=$(Get-Value $preview.json "validCount")"
        if ($script:photoStorageConfigured) {
            Assert-E2E "bulk import original file stored" ([bool](Get-Value $preview.json "originalFileStored" $false)) "originalFileStored=$(Get-Value $preview.json "originalFileStored")"
        } else {
            Add-Result "bulk import original object storage skipped" "SKIP" "/api/v1/students/import/upload-preview" "school-admin" $true "STUDENT_PHOTO_BUCKET is not configured in local compose; DB filename/hash/size metadata is still asserted after confirm" $true
        }
        Assert-E2E "bulk import no structure update needed" (-not [bool](Get-Value (Get-Value $preview.json "structure") "requiresStructureUpdate" $true)) "structure ok"

        $fileToken = [string](Get-Value $preview.json "fileToken")
        $confirm = Invoke-Api "student bulk import confirm" "POST" "/api/v1/students/import/confirm" $admin.token "school-admin" @{
            fileToken = $fileToken
            schoolId = $schoolId
        }
        Assert-E2E "bulk import inserted" ([int](Get-Value $confirm.json "inserted" 0) -eq 1) "inserted=$(Get-Value $confirm.json "inserted")"
        $jobId = [string](Get-Value $confirm.json "jobId")
        $status = Invoke-Api "student bulk import status" "GET" "/api/v1/students/import/status/$jobId" $admin.token "school-admin"
        Assert-E2E "bulk import status done" ([bool](Get-Value $status.json "done" $false)) "status done"
        $insertedStudents = @(Get-Array (Get-Value $confirm.json "insertedStudents"))
        $script:importedStudentId = [long](Get-Value ($insertedStudents | Select-Object -First 1) "studentId" 0)
        $script:state.importedStudentId = $importedStudentId
        Assert-E2E "bulk import returned student id" ($importedStudentId -gt 0) "studentId=$importedStudentId"
        Assert-OutboxEvent "tenant_school" "student.upserted.v1" $importedStudentId "imported student outbox"
        Assert-DbCount "bulk import original file metadata persisted" "SELECT COUNT(*) FROM student.import_batches WHERE school_id = $schoolId AND original_file_name = $(Sql-Quote "logical-e2e-students-$script:runId.csv") AND original_file_sha256 IS NOT NULL AND original_file_size > 0;" 1

        $inactiveRow = @{
            Name = "Logical Needs Section $script:runId"
            Class = "1"
            Section = "C"
            AdmissionNo = "NEEDS-$script:runId"
            Phone = "9876543212"
        }
        $inactiveRowsJson = "[" + ($inactiveRow | ConvertTo-Json -Depth 10 -Compress) + "]"
        $inactivePreview = Invoke-MultipartApi "student bulk import setup-analysis" "/api/v1/students/import/upload-preview" $admin.token "school-admin" @{
            rowsJson = $inactiveRowsJson
            schoolId = [string]$schoolId
        } @(
            @{ Name = "file"; Path = $csvPath; FileName = "logical-e2e-setup-analysis-$script:runId.csv"; ContentType = "text/csv" }
        )
        $structure = Get-Value $inactivePreview.json "structure"
        Assert-E2E "bulk import detects missing configured section" ([bool](Get-Value $structure "requiresStructureUpdate" $false)) "structure=$($structure | ConvertTo-Json -Compress)"
        Assert-E2E "bulk import reports required section count" ([int](Get-Value $structure "requiredSectionCount" 0) -ge 3) "requiredSectionCount=$(Get-Value $structure "requiredSectionCount")"
    }

    Invoke-Phase "attendance" {
        $attendanceDate = "2026-02-02"
        $register = Invoke-Api "attendance section register read" "GET" "/api/v1/attendance/section-register?schoolId=$schoolId&date=$attendanceDate&classId=$class1&sectionId=$(Encode-QueryValue $section1A)" $admin.token "school-admin"
        $studentsInRegister = @(Get-Array (Get-Value $register.json "students"))
        Assert-E2E "attendance register includes students" ($studentsInRegister.Count -ge 2) "count=$($studentsInRegister.Count)"
        $records = @()
        $index = 0
        foreach ($student in $studentsInRegister) {
            $records += @{
                studentId = [long](Get-Value $student "studentId")
                status = if ($index -eq 0) { "PRESENT" } else { "ABSENT" }
                remarks = "logical e2e"
            }
            $index++
        }
        $saved = Invoke-Api "attendance section register save" "PUT" "/api/v1/attendance/section-register" $admin.token "school-admin" @{
            schoolId = $schoolId
            classId = $class1
            sectionId = $section1A
            date = $attendanceDate
            records = $records
        }
        Assert-E2E "attendance save counted present" ([int](Get-Value $saved.json "presentCount" 0) -ge 1) "present=$(Get-Value $saved.json "presentCount")"
        $submitted = Invoke-Api "attendance section submit" "POST" "/api/v1/attendance/submit-section" $admin.token "school-admin" @{
            schoolId = $schoolId
            classId = $class1
            sectionId = $section1A
            date = $attendanceDate
        }
        Assert-E2E "attendance locked after submit" ([bool](Get-Value $submitted.json "locked" $false)) "locked=$(Get-Value $submitted.json "locked")"
        $summary = Invoke-Api "attendance daily summary" "GET" "/api/v1/attendance/daily-summary?schoolId=$schoolId&date=$attendanceDate" $admin.token "school-admin"
        Assert-E2E "attendance summary has sections" (@(Get-Array (Get-Value $summary.json "sections")).Count -ge 1) "summary sections"
        Invoke-Api "attendance student report" "GET" "/api/v1/attendance/report/student?schoolId=$schoolId&studentId=$primaryStudentId&from=$attendanceDate&to=$attendanceDate" $admin.token "school-admin" | Out-Null
        Invoke-Api "attendance summary report" "GET" "/api/v1/attendance/report/summary?schoolId=$schoolId&from=$attendanceDate&to=$attendanceDate" $admin.token "school-admin" | Out-Null
        Assert-DbCount "attendance daily outbox" "SELECT COUNT(*) FROM tenant_school.outbox_events WHERE event_type = 'attendance-daily.upserted.v1' AND school_id = $schoolId;" 1
    }

    Invoke-Phase "fees" {
        $band = Invoke-Api "fee band create" "POST" "/api/v1/fee-structure/band" $admin.token "school-admin" @{
            schoolId = $schoolId
            name = "Logical Fee Band $script:runId"
            classFrom = 1
            classTo = 2
            schedules = @("Annual")
            discount = 0
        }
        $bandId = [string](Get-Value $band.json "id")
        Assert-E2E "fee band created" (-not [string]::IsNullOrWhiteSpace($bandId)) "bandId=$bandId"
        $item = Invoke-Api "fee item create" "POST" "/api/v1/fee-structure/item" $admin.token "school-admin" @{
            bandId = $bandId
            name = "Tuition"
            amount = 1200
            frequency = "Annual"
        }
        Assert-E2E "fee item created" (@(Get-Array (Get-Value $item.json "items")).Count -ge 1) "items present"
        $assignment = Invoke-Api "fee assignment create" "POST" "/api/v1/fee-assignments" $admin.token "school-admin" @{
            studentId = $primaryStudentId
            bandId = $bandId
            schedule = "Annual"
            manualDiscount = 0
            surcharge = 0
        }
        $feeAssignment = Get-Value $assignment.json "assignment"
        $script:feeAssignmentId = [string](Get-Value $feeAssignment "id")
        $script:state.feeAssignmentId = $feeAssignmentId
        Assert-E2E "fee assignment created" (-not [string]::IsNullOrWhiteSpace($feeAssignmentId)) "assignmentId=$feeAssignmentId"
        Assert-OutboxEvent "tenant_school" "fee-assignment.upserted.v1" $feeAssignmentId "fee assignment outbox"

        $payment = Invoke-Api "fee payment record" "POST" "/api/v1/workspace/fees/record-payment" $admin.token "school-admin" @{
            studentId = $primaryStudentId
            amountRupees = 500.25
            mode = "UPI"
            notes = "logical e2e"
        }
        $script:paymentId = [string](Get-Value $payment.json "paymentId")
        $script:state.paymentId = $paymentId
        Assert-E2E "fee payment amount paise" ([long](Get-Value $payment.json "amount" 0) -eq 50025) "amount=$(Get-Value $payment.json "amount")"
        Assert-OutboxEvent "tenant_school" "payment.recorded.v1" $paymentId "payment outbox"
        $receipt = Invoke-Api "fee payment receipt" "GET" "/api/v1/fees/payments/$paymentId/receipt" $admin.token "school-admin"
        Assert-E2E "receipt amount paise" ([long](Get-Value $receipt.json "amount" 0) -eq 50025) "receipt amount=$(Get-Value $receipt.json "amount")"
        $feeReport = Invoke-Api "fee report" "GET" "/api/v1/fees/report?schoolId=$schoolId&classId=$class1&sectionId=$(Encode-QueryValue $section1A)" $admin.token "school-admin"
        $feeRows = @(Get-Array $feeReport.json)
        Assert-E2E "fee report includes student" (@($feeRows | Where-Object { [long](Get-Value $_ "studentId" 0) -eq $primaryStudentId }).Count -ge 1) "rows=$($feeRows.Count)"
    }

    Invoke-Phase "supply os and urgent procurement" {
        $order = Invoke-Api "supply order create" "POST" "/api/v1/supply/orders" $admin.token "school-admin" @{
            schoolId = $schoolId
            category = "stationery"
            items = "Logical notebooks"
            subtotal = 10000
            gst = 1800
            totalAmount = 11800
            requiredByDate = "2026-08-15"
            notes = "logical e2e $script:runId"
        }
        $script:catalogOrderId = [string](Get-Value $order.json "id")
        $script:state.catalogOrderId = $catalogOrderId
        Assert-E2E "supply order created" (-not [string]::IsNullOrWhiteSpace($catalogOrderId)) "orderId=$catalogOrderId"
        Invoke-Api "supply order detail" "GET" "/api/v1/supply/orders/$catalogOrderId" $admin.token "school-admin" | Out-Null
        Invoke-Api "supply order place" "POST" "/api/v1/supply/orders/$catalogOrderId/place" $admin.token "school-admin" | Out-Null
        Invoke-Api "supply order superadmin approve" "POST" "/api/v1/supply/orders/$catalogOrderId/superadmin-approve" $super.token "superadmin" | Out-Null
        Invoke-Api "operator deliver supply order" "POST" "/api/v1/supply/orders/$catalogOrderId/deliver" $operator.token "operator" | Out-Null
        Invoke-Api "supply vendor paid" "POST" "/api/v1/dashboard/vendor-dues/catalog-orders/$catalogOrderId/mark-paid" $admin.token "school-admin" @{
            schoolId = $schoolId
            notes = "logical e2e paid"
        } | Out-Null
        Assert-OutboxEvent "tenant_school" "catalog-order.upserted.v1" $catalogOrderId "catalog order outbox"

        Invoke-Api "annual plan item create" "POST" "/api/v1/supply/annual-plan/items?schoolId=$schoolId" $admin.token "school-admin" @{
            schoolId = $schoolId
            term = "Term 1"
            category = "stationery"
            description = "Logical annual plan"
            quantity = "10"
            estimatedAmount = 5000
        } | Out-Null
        Invoke-Api "annual plan confirm" "POST" "/api/v1/supply/annual-plan/confirm" $admin.token "school-admin" | Out-Null

        $fire = Invoke-Api "urgent procurement create" "POST" "/api/v1/ff/requests" $admin.token "school-admin" @{
            schoolId = $schoolId
            title = "Logical Urgent Procurement $script:runId"
            category = "Maintenance"
            urgency = "LOW"
            requiredByDate = "2026-08-20"
            estimatedBudget = 5000
            summary = "Logical e2e"
            description = "Logical urgent procurement request"
        }
        $script:fireCode = [string](Get-Value $fire.json "code" (Get-Value $fire.json "id"))
        $script:state.firefightingCode = $fireCode
        Assert-E2E "urgent procurement created" (-not [string]::IsNullOrWhiteSpace($fireCode)) "code=$fireCode"
        $quote = Invoke-Api "urgent procurement quotation" "POST" "/api/v1/ff/requests/$fireCode/quotations" $admin.token "school-admin" @{
            vendorName = "Logical Vendor"
            amount = 5000
            deliveryTimeline = "7 days"
            notes = "logical quote"
        }
        $quoteId = [string](Get-Value $quote.json "id")
        Invoke-Api "urgent procurement submit" "POST" "/api/v1/ff/requests/$fireCode/submit" $admin.token "school-admin" | Out-Null
        Invoke-Api "urgent procurement bursar approve" "POST" "/api/v1/ff/requests/$fireCode/approve-bursar" $admin.token "school-admin" @{
            note = "logical bursar"
        } | Out-Null
        Invoke-Api "urgent procurement principal approve" "POST" "/api/v1/ff/requests/$fireCode/approve-principal" $admin.token "school-admin" @{
            selectedQuotationId = $quoteId
            note = "logical principal"
        } | Out-Null
        Invoke-Api "urgent procurement custoking approve" "POST" "/api/v1/ff/requests/$fireCode/approve-custoking" $super.token "superadmin" | Out-Null
        Invoke-Api "urgent procurement fulfill" "PATCH" "/api/v1/ff/requests/$fireCode/fulfill" $admin.token "school-admin" | Out-Null
        Invoke-Api "urgent procurement vendor paid" "POST" "/api/v1/dashboard/vendor-dues/firefighting/$fireCode/mark-paid" $admin.token "school-admin" @{
            schoolId = $schoolId
            notes = "logical paid"
        } | Out-Null
        $timeline = Invoke-Api "urgent procurement timeline" "GET" "/api/v1/ff/requests/$fireCode/timeline" $admin.token "school-admin"
        Assert-E2E "urgent procurement timeline populated" (@(Get-Array $timeline.json).Count -ge 1) "timeline count"
        Assert-OutboxEvent "firefighting" "firefighting-request.upserted.v1" $fireCode "urgent procurement outbox"
    }

    Invoke-Phase "workflow reporting notification billing" {
        $defs = Invoke-Api "workflow definitions" "GET" "/api/v1/workflows/definitions" $admin.token "school-admin"
        $definitions = @(Get-Array $defs.json)
        if ($definitions.Count -gt 0) {
            $definitionId = [string](Get-Value ($definitions | Select-Object -First 1) "id")
            $workflow = Invoke-Api "workflow instance create" "POST" "/api/v1/workflows/instances" $admin.token "school-admin" @{
                schoolId = $schoolId
                entityType = "CATALOG_ORDER"
                entityId = $catalogOrderId
                definitionId = $definitionId
            }
            $workflowId = [long](Get-Value $workflow.json "id" 0)
            Invoke-Api "workflow submit" "POST" "/api/v1/workflows/instances/$workflowId/submit" $admin.token "school-admin" @{
                notes = "logical submit"
            } | Out-Null
            Invoke-Api "workflow approve" "POST" "/api/v1/workflows/instances/$workflowId/approve" $admin.token "school-admin" @{
                notes = "logical approve"
            } | Out-Null
            $actions = Invoke-Api "workflow actions" "GET" "/api/v1/workflows/$workflowId/actions" $admin.token "school-admin"
            Assert-E2E "workflow actions recorded" (@(Get-Array $actions.json).Count -ge 2) "actions count"
        } else {
            Add-Result "workflow skipped no active definitions" "SKIP" "" "" $true "No workflow definitions are seeded" $true
        }

        Invoke-Api "workspace summary" "GET" "/api/v1/workspace?schoolId=$schoolId" $admin.token "school-admin" | Out-Null
        Invoke-Api "dashboard summary" "GET" "/api/v1/dashboard?schoolId=$schoolId" $admin.token "school-admin" | Out-Null
        Invoke-Api "dashboard command center" "GET" "/api/v1/dashboard/command-center?schoolId=$schoolId" $admin.token "school-admin" | Out-Null
        Invoke-Api "command centre feed" "GET" "/api/v1/command-centre/feed?schoolId=$schoolId&limit=5" $admin.token "school-admin" | Out-Null
        Invoke-Api "dashboard fee defaulters" "GET" "/api/v1/dashboard/finance/fee-defaulters?schoolId=$schoolId&page=0&size=5" $admin.token "school-admin" | Out-Null
        Invoke-Api "dashboard vendor dues" "GET" "/api/v1/dashboard/vendor-dues?schoolId=$schoolId" $admin.token "school-admin" | Out-Null
        Invoke-Api "audit logs" "GET" "/api/v1/audit-logs?limit=5" $super.token "superadmin" | Out-Null

        $broadcast = Invoke-Api "notification broadcast create" "POST" "/api/v1/notifications/broadcasts" $super.token "superadmin" @{
            schoolId = $schoolId
            title = "Logical Broadcast $script:runId"
            message = "Logical E2E broadcast"
            module = "ERP"
            audienceType = "ALL"
            channels = @("SMS")
        } @(200, 201)
        $broadcastId = [string](Get-Value $broadcast.json "id")
        Assert-E2E "notification broadcast created" (-not [string]::IsNullOrWhiteSpace($broadcastId)) "broadcastId=$broadcastId"
        Invoke-Api "notification broadcast delivery status" "GET" "/api/v1/notifications/broadcasts/$broadcastId/delivery-status" $super.token "superadmin" | Out-Null

        $invoice = Invoke-Api "billing invoice create" "POST" "/api/v1/sa/invoices" $super.token "superadmin" @{
            orderRef = "LOGICAL-E2E-$script:runId"
            school = "Logical E2E School $script:runId"
            schoolId = $schoolId
            description = "Logical E2E invoice"
            qty = 1
            rate = 10000
            amount = 10000
            notes = "logical e2e"
        } @(201)
        $invoiceId = [string](Get-Value $invoice.json "id")
        Assert-E2E "billing invoice created" (-not [string]::IsNullOrWhiteSpace($invoiceId)) "invoiceId=$invoiceId"
        Invoke-Api "billing invoice update" "PATCH" "/api/v1/sa/invoices/$invoiceId" $super.token "superadmin" @{
            status = "SENT"
            notes = "logical e2e sent"
        } | Out-Null
        Invoke-Api "billing invoice by order" "GET" "/api/v1/sa/invoices/by-order/LOGICAL-E2E-$script:runId" $super.token "superadmin" | Out-Null
        Assert-OutboxEvent "billing" "billing.invoice-upserted.v1" $invoiceId "billing invoice outbox"
    }

    Invoke-Phase "promotion delete history" {
        if ($script:canUseLocalDb) {
            $targetYearId = "logical_e2e_$script:runId"
            Invoke-Psql -Sql "INSERT INTO tenant_school.academic_years (id, label, active) VALUES ($(Sql-Quote $targetYearId), $(Sql-Quote "Logical E2E $script:runId"), false) ON CONFLICT (id) DO UPDATE SET label = EXCLUDED.label, active = false;" | Out-Null
        } else {
            throw "Promotion lifecycle test needs local DB access to create a target academic year."
        }

        $batch = Invoke-Api "student promotion batch create" "POST" "/api/v1/students/promotion-batches" $admin.token "school-admin" @{
            schoolId = $schoolId
            sourceClassId = $class1
            sourceSectionId = $section1A
            targetAcademicYearId = "logical_e2e_$script:runId"
            targetClassId = $class2
            targetSectionId = $section2A
            notes = "logical e2e promotion"
        }
        $batchId = [string](Get-Value $batch.json "id")
        $items = @(Get-Array (Get-Value $batch.json "items"))
        Assert-E2E "promotion batch has items" ($items.Count -ge 2) "items=$($items.Count)"
        $importedItem = @($items | Where-Object { [long](Get-Value $_ "studentId" 0) -eq $importedStudentId } | Select-Object -First 1)
        if ($importedItem.Count -gt 0) {
            $itemId = [string](Get-Value $importedItem[0] "id")
            Invoke-Api "promotion item hold" "PATCH" "/api/v1/students/promotion-batches/$batchId/items/$itemId" $admin.token "school-admin" @{
                action = "HOLD"
                reason = "logical e2e hold one student"
            } | Out-Null
        }
        $applied = Invoke-Api "promotion batch apply" "POST" "/api/v1/students/promotion-batches/$batchId/apply" $admin.token "school-admin"
        Assert-E2E "promotion applied at least one" ([int](Get-Value $applied.json "promoted" 0) -ge 1) "promoted=$(Get-Value $applied.json "promoted")"
        $promotedDetail = Invoke-Api "promoted student detail" "GET" "/api/v1/students/$primaryStudentId/workspace" $admin.token "school-admin"
        Assert-E2E "student promoted class" ([string](Get-Value $promotedDetail.json "classId") -eq $class2) "classId=$(Get-Value $promotedDetail.json "classId")"

        $deleted = Invoke-Api "student delete" "DELETE" "/api/v1/students/$primaryStudentId" $admin.token "school-admin" @{
            reason = "logical e2e delete after completed year"
        }
        Assert-E2E "student delete succeeded" ([bool](Get-Value $deleted.json "deleted" $false)) "deleted=$(Get-Value $deleted.json "deleted")"
        Assert-E2E "student delete preserved history" ([bool](Get-Value $deleted.json "historyPreserved" $false)) "historyPreserved=$(Get-Value $deleted.json "historyPreserved")"

        $history = Invoke-Api "student history read" "GET" "/api/v1/students/$primaryStudentId/history" $admin.token "school-admin"
        Assert-E2E "history has enrollments" (@(Get-Array (Get-Value $history.json "enrollments")).Count -ge 1) "enrollments"
        Assert-E2E "history has promotions" (@(Get-Array (Get-Value $history.json "promotions")).Count -ge 1) "promotions"
        Assert-E2E "history has fee assignments" (@(Get-Array (Get-Value $history.json "feeAssignments")).Count -ge 1) "feeAssignments"
        $historyPayments = @(Get-Array (Get-Value $history.json "feePayments"))
        Assert-E2E "history preserves fee payment amount" (@($historyPayments | Where-Object { [long](Get-Value $_ "amountPaise" 0) -eq 50025 }).Count -ge 1) "payments=$($historyPayments.Count)"
        Assert-E2E "history years available" (@(Get-Array (Get-Value $history.json "historyYears")).Count -ge 1) "historyYears"
        Invoke-Api "active students excludes deleted" "GET" "/api/v1/students?schoolId=$schoolId&deleted=false&page=0&size=50" $admin.token "school-admin" | Out-Null
        $deletedList = Invoke-Api "deleted students includes deleted" "GET" "/api/v1/students?schoolId=$schoolId&deleted=true&page=0&size=50" $admin.token "school-admin"
        $deletedItems = @(Get-Array (Get-Value $deletedList.json "items"))
        Assert-E2E "deleted student appears in deleted list" (@($deletedItems | Where-Object { [long](Get-Value $_ "id" 0) -eq $primaryStudentId }).Count -eq 1) "deleted list count=$($deletedItems.Count)"
        Assert-OutboxEvent "tenant_school" "student.upserted.v1" $primaryStudentId "student delete outbox"
    }
} finally {
    foreach ($file in $script:tempFiles) {
        Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue
    }
    Finalize-Results
}

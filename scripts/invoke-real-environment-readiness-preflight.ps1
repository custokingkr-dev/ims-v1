param(
    [string]$ProjectId = "custoking",
    [string]$Region = "asia-south2",
    [string]$DeploymentSmokeJson,
    [string]$LegacyCompatibilityJson,
    [string]$GatewayBaseUrl,
    [string]$GcloudPath = "gcloud",
    [string]$OutputJson = "real-environment-readiness-preflight.json",
    [string]$OutputMarkdown = "real-environment-readiness-preflight.md"
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")
. (Join-Path $PSScriptRoot "secret-manager-catalog.ps1")

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$sdkGcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
if ($GcloudPath -eq "gcloud" -and (Test-Path -LiteralPath $sdkGcloud)) {
    $GcloudPath = $sdkGcloud
}

$checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Detail = "",
        [string]$Severity = "blocker"
    )

    $checks.Add([pscustomobject]@{
        name = $Name
        passed = $Passed
        severity = $Severity
        detail = $Detail
    }) | Out-Null
}

function Resolve-RepoPath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $null
    }
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $repoRoot $Path
}

function Invoke-GcloudJson {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)

    $output = & $GcloudPath @Args
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud command failed with exit code ${LASTEXITCODE}: gcloud $($Args -join ' ')"
    }
    if ([string]::IsNullOrWhiteSpace(($output -join ""))) {
        return $null
    }
    return ($output -join "`n") | ConvertFrom-Json
}

try {
    $config = Invoke-GcloudJson config list "--format=json"
    $activeProject = $config.core.project
    Add-Check "gcloud project" ($activeProject -eq $ProjectId) "activeProject=$activeProject expected=$ProjectId"
} catch {
    Add-Check "gcloud project" $false $_.Exception.Message
}

try {
    $auth = @(Invoke-GcloudJson auth list "--format=json")
    $activeAccount = @($auth | Where-Object { $_.status -eq "ACTIVE" } | Select-Object -First 1).account
    Add-Check "gcloud active account" (-not [string]::IsNullOrWhiteSpace($activeAccount)) "activeAccount=$activeAccount"
} catch {
    Add-Check "gcloud active account" $false $_.Exception.Message
}

try {
    $services = @(Invoke-GcloudJson run services list "--project=$ProjectId" "--region=$Region" "--format=json")
    $expectedServices = @((Get-MicroserviceBuildCatalog) | ForEach-Object { $_.Image })
    $serviceNames = @($services | ForEach-Object { $_.metadata.name })
    $missingServices = @($expectedServices | Where-Object { $serviceNames -notcontains $_ })
    Add-Check "cloud run service inventory" ($missingServices.Count -eq 0) "services=$($serviceNames.Count) missing=$($missingServices -join ', ')"

    if ([string]::IsNullOrWhiteSpace($GatewayBaseUrl) -and $gateway) {
        $GatewayBaseUrl = $gateway.status.url
    }
    if ([string]::IsNullOrWhiteSpace($GatewayBaseUrl)) {
        $GatewayBaseUrl = & $GcloudPath run services describe custoking-api-gateway "--project=$ProjectId" "--region=$Region" "--format=value(status.url)"
        if ($LASTEXITCODE -ne 0) {
            $GatewayBaseUrl = ""
        }
        $GatewayBaseUrl = "$GatewayBaseUrl".Trim()
    }
    Add-Check "gateway url resolved" (-not [string]::IsNullOrWhiteSpace($GatewayBaseUrl)) "gatewayBaseUrl=$GatewayBaseUrl"
} catch {
    Add-Check "cloud run service inventory" $false $_.Exception.Message
}

try {
    $gatewayDescription = Invoke-GcloudJson run services describe custoking-api-gateway "--project=$ProjectId" "--region=$Region" "--format=json"
    $gatewayEnv = @($gatewayDescription.spec.template.spec.containers[0].env)
    $backendUpstreams = @($gatewayEnv | Where-Object { $_.name -like "*_UPSTREAM" -and "$($_.value)" -like "*custoking-backend*" })
    Add-Check "gateway service-only upstreams" ($backendUpstreams.Count -eq 0) "backendUpstreams=$($backendUpstreams.Count)"
} catch {
    Add-Check "gateway service-only upstreams" $false $_.Exception.Message
}

try {
    $secrets = @(Invoke-GcloudJson secrets list "--project=$ProjectId" "--format=json")
    $secretNames = @($secrets | ForEach-Object { Split-Path $_.name -Leaf })
    $requiredSecrets = @((Get-SecretManagerCatalog) | ForEach-Object { $_.Name })
    $missingSecrets = @($requiredSecrets | Where-Object { $secretNames -notcontains $_ })
    Add-Check "required secret names" ($missingSecrets.Count -eq 0) "missing=$($missingSecrets -join ', ')"
} catch {
    Add-Check "required secret names" $false $_.Exception.Message
}

try {
    $builds = @(Invoke-GcloudJson builds list "--project=$ProjectId" "--limit=1" "--sort-by=~createTime" "--format=json")
    $latestBuild = @($builds | Select-Object -First 1)
    $latestBuildId = if ($latestBuild) { $latestBuild.id } else { "" }
    $latestBuildStatus = if ($latestBuild) { $latestBuild.status } else { "" }
    Add-Check "latest cloud build" ($latestBuildStatus -eq "SUCCESS") "id=$latestBuildId status=$latestBuildStatus"
} catch {
    Add-Check "latest cloud build" $false $_.Exception.Message
}

$deploymentSmokePassed = $false
$smokePath = Resolve-RepoPath $DeploymentSmokeJson
if ($smokePath -and (Test-Path $smokePath)) {
    try {
        $smoke = Get-Content -Raw -Path $smokePath | ConvertFrom-Json
        $deploymentSmokePassed = [int]$smoke.failures -eq 0
        Add-Check "deployment smoke artifact" $deploymentSmokePassed "path=$DeploymentSmokeJson failures=$($smoke.failures) total=$($smoke.total)"
    } catch {
        Add-Check "deployment smoke artifact" $false "invalid JSON: $($_.Exception.Message)"
    }
} else {
    Add-Check "deployment smoke artifact" $false "missing DeploymentSmokeJson; generate with smoke-deployment-readiness.ps1"
}

$legacyPath = Resolve-RepoPath $LegacyCompatibilityJson
if ($legacyPath -and (Test-Path $legacyPath)) {
    try {
        $legacy = Get-Content -Raw -Path $legacyPath | ConvertFrom-Json
        Add-Check "legacy compatibility artifact" ([int]$legacy.summary.needsBackfillReview -eq 0) "path=$LegacyCompatibilityJson needsBackfillReview=$($legacy.summary.needsBackfillReview)"
    } catch {
        Add-Check "legacy compatibility artifact" $false "invalid JSON: $($_.Exception.Message)"
    }
} else {
    Add-Check "legacy compatibility artifact" $false "missing LegacyCompatibilityJson; generate with audit-legacy-compatibility-state.ps1"
}

$hasSuperToken = -not [string]::IsNullOrWhiteSpace($env:IMS_SMOKE_SUPERADMIN_TOKEN)
$hasAdminToken = -not [string]::IsNullOrWhiteSpace($env:IMS_SMOKE_ADMIN_TOKEN)
$hasSuperCreds = -not [string]::IsNullOrWhiteSpace($env:IMS_SMOKE_SUPERADMIN_EMAIL) -and -not [string]::IsNullOrWhiteSpace($env:IMS_SMOKE_SUPERADMIN_PASSWORD)
$hasAdminCreds = -not [string]::IsNullOrWhiteSpace($env:IMS_SMOKE_ADMIN_EMAIL) -and -not [string]::IsNullOrWhiteSpace($env:IMS_SMOKE_ADMIN_PASSWORD)
Add-Check "smoke superadmin auth input" ($deploymentSmokePassed -or $hasSuperToken -or $hasSuperCreds) "deploymentSmokePassed=$deploymentSmokePassed token=$hasSuperToken credentials=$hasSuperCreds"
Add-Check "smoke admin auth input" ($deploymentSmokePassed -or $hasAdminToken -or $hasAdminCreds) "deploymentSmokePassed=$deploymentSmokePassed token=$hasAdminToken credentials=$hasAdminCreds"

$blockers = @($checks | Where-Object { -not $_.passed -and $_.severity -eq "blocker" })
$ready = $blockers.Count -eq 0

[ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    projectId = $ProjectId
    region = $Region
    gatewayBaseUrl = $GatewayBaseUrl
    readyForRealBundle = $ready
    blockerCount = $blockers.Count
    checks = $checks
} | ConvertTo-Json -Depth 6 | Set-Content -Path $OutputJson -Encoding UTF8

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# Real Environment Readiness Preflight")
$lines.Add("")
$lines.Add("- Project: $ProjectId")
$lines.Add("- Region: $Region")
$lines.Add("- Gateway: $GatewayBaseUrl")
$lines.Add("- Ready for real bundle: $ready")
$lines.Add("- Blockers: $($blockers.Count)")
$lines.Add("")
$lines.Add("## Checks")
foreach ($check in $checks) {
    $lines.Add("- $($check.name): passed=$($check.passed) severity=$($check.severity) detail=$($check.detail)")
}
$lines | Set-Content -Path $OutputMarkdown -Encoding UTF8

Write-Host "Created real environment preflight: $OutputJson"
Write-Host "Created real environment preflight markdown: $OutputMarkdown"
Write-Host "Ready for real bundle: $ready"
Write-Host "Blockers: $($blockers.Count)"

if (-not $ready) {
    exit 1
}

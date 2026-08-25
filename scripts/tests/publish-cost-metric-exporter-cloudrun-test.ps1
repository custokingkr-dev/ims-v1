$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "../..")
$publisherPath = Join-Path $root "scripts/publish-cost-metric-exporter-cloudrun.ps1"
$publisher = Get-Content -Raw -LiteralPath $publisherPath
$canonicalScript = Join-Path $root "scripts/cost-metric-exporter.sh"
$mockGcloud = Join-Path $PSScriptRoot "fixtures/mock-cost-metric-gcloud.ps1"

$tokens = $null
$parseErrors = $null
[void][Management.Automation.Language.Parser]::ParseFile(
    $publisherPath,
    [ref]$tokens,
    [ref]$parseErrors
)
if (@($parseErrors).Count -gt 0) {
    throw "Cost-metric publisher has PowerShell parse errors: $($parseErrors -join '; ')"
}

foreach ($required in @(
    'This publisher is restricted to the production cost-metric job.',
    'Only the canonical repository cost-metric-exporter.sh can be published.',
    'Production publication requires both -Apply and -ConfirmProduction.',
    'cost-metric-exporter@custoking-prod.iam.gserviceaccount.com',
    'The production cost-metric job command contract has drifted.',
    'The production cost-metric environment contains an unknown or missing entry.',
    '"--env-vars-file=$envPath"',
    'Production cost-metric exporter verification did not match the repository script.',
    'Remove-Item -LiteralPath $envPath -Force',
    'Post-publication cost-metric execution succeeded.'
)) {
    if (-not $publisher.Contains($required)) {
        throw "Cost-metric publisher safety contract is missing: $required"
    }
}
if ($publisher -match '(?i)(password|token|secret)\s*=\s*["''][^"'']+["'']') {
    throw "Cost-metric publisher appears to contain a literal credential."
}

$confirmationGateWorked = $false
try {
    & $publisherPath -Apply 6>&1 | Out-Null
} catch {
    $confirmationGateWorked = $_.Exception.Message -eq `
        "Production publication requires both -Apply and -ConfirmProduction."
}
if (-not $confirmationGateWorked) {
    throw "Cost-metric publisher did not fail before cloud access when confirmation was absent."
}

$targetGateWorked = $false
try {
    & $publisherPath -ProjectId "custoking-dev" 6>&1 | Out-Null
} catch {
    $targetGateWorked = $_.Exception.Message -eq `
        "This publisher is restricted to the production cost-metric job."
}
if (-not $targetGateWorked) {
    throw "Cost-metric publisher allowed a non-production target."
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("cost-metric-publisher-test-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$statePath = Join-Path $testRoot "job.json"
$logPath = Join-Path $testRoot "gcloud.log"
$env:MOCK_COST_METRIC_STATE = $statePath
$env:MOCK_COST_METRIC_LOG = $logPath

function Set-MockState([string]$ScriptBase64, [hashtable]$AdditionalEnvironment = @{}) {
    $entries = [ordered]@{
        COST_METRIC_PROJECT = "custoking-prod"
        COST_METRIC_BQ_PROJECT = "custoking-prod"
        COST_METRIC_SCOPE_PROJECT = "custoking-prod"
        SCRIPT_B64 = $ScriptBase64
        COST_METRIC_PUBLISH_PROJECT = "custoking-prod"
    }
    foreach ($entry in $AdditionalEnvironment.GetEnumerator()) { $entries[$entry.Key] = $entry.Value }
    $state = [ordered]@{
        spec = [ordered]@{
            template = [ordered]@{
                spec = [ordered]@{
                    template = [ordered]@{
                        spec = [ordered]@{
                            serviceAccountName = "cost-metric-exporter@custoking-prod.iam.gserviceaccount.com"
                            containers = @([ordered]@{
                                image = "google/cloud-sdk:slim"
                                command = @("bash")
                                args = @("-c", 'echo $SCRIPT_B64 | base64 -d > /tmp/exporter.sh && bash /tmp/exporter.sh')
                                env = @($entries.GetEnumerator() | ForEach-Object {
                                    [ordered]@{ name = [string]$_.Key; value = [string]$_.Value }
                                })
                            })
                        }
                    }
                }
            }
        }
    }
    $state | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $statePath -Encoding utf8
    Set-Content -LiteralPath $logPath -Value ""
}

try {
    $oldScript = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("old exporter"))
    Set-MockState $oldScript
    $dryRun = @(& $publisherPath -Gcloud $mockGcloud 6>&1 | ForEach-Object { [string]$_ })
    if ($dryRun -notcontains "Dry run only. No Cloud Run job was changed." `
        -or (Get-Content -Raw $logPath) -match 'run jobs update') {
        throw "Cost-metric publisher dry run attempted a mutation"
    }

    & $publisherPath -Gcloud $mockGcloud -Apply -ConfirmProduction 6>&1 | Out-Null
    $updated = Get-Content -Raw $statePath | ConvertFrom-Json
    $published = @($updated.spec.template.spec.template.spec.containers[0].env |
        Where-Object { $_.name -eq "SCRIPT_B64" })[0].value
    $expected = [Convert]::ToBase64String([IO.File]::ReadAllBytes($canonicalScript))
    if ($published -ne $expected) { throw "Mock publication did not install the canonical exporter" }
    $envPathLine = Get-Content $logPath | Where-Object { $_ -like 'ENV_PATH=*' } | Select-Object -Last 1
    if (-not $envPathLine -or (Test-Path -LiteralPath $envPathLine.Substring('ENV_PATH='.Length))) {
        throw "Cost-metric publisher did not remove its temporary environment file"
    }

    Set-MockState $oldScript @{ COST_METRIC_DATASET = "unexpected" }
    $unknownEnvironmentWorked = $false
    try {
        & $publisherPath -Gcloud $mockGcloud 6>&1 | Out-Null
    } catch {
        $unknownEnvironmentWorked = $_.Exception.Message -eq `
            "The production cost-metric environment contains an unknown or missing entry."
    }
    if (-not $unknownEnvironmentWorked) { throw "Publisher preserved an unknown behavior-changing environment variable" }

    $otherScript = Join-Path $testRoot "other.sh"
    Set-Content -LiteralPath $otherScript -Value "echo unsafe" -Encoding utf8
    $canonicalPathGateWorked = $false
    try {
        & $publisherPath -ScriptPath $otherScript 6>&1 | Out-Null
    } catch {
        $canonicalPathGateWorked = $_.Exception.Message -eq `
            "Only the canonical repository cost-metric-exporter.sh can be published."
    }
    if (-not $canonicalPathGateWorked) { throw "Publisher accepted a non-canonical shell script" }

    Set-MockState $oldScript
    $env:MOCK_COST_METRIC_UPDATE_FAIL = '1'
    $updateFailureWorked = $false
    try {
        & $publisherPath -Gcloud $mockGcloud -Apply -ConfirmProduction 6>&1 | Out-Null
    } catch {
        $updateFailureWorked = $_.Exception.Message -eq "Could not publish the production cost-metric exporter."
    } finally {
        Remove-Item Env:MOCK_COST_METRIC_UPDATE_FAIL -ErrorAction SilentlyContinue
    }
    $failedEnvPathLine = Get-Content $logPath | Where-Object { $_ -like 'ENV_PATH=*' } | Select-Object -Last 1
    if (-not $updateFailureWorked -or -not $failedEnvPathLine `
        -or (Test-Path -LiteralPath $failedEnvPathLine.Substring('ENV_PATH='.Length))) {
        throw "Publisher did not fail and clean up after an update error"
    }

    Set-MockState $oldScript
    $env:MOCK_COST_METRIC_IGNORE_UPDATE = '1'
    $verificationWorked = $false
    try {
        & $publisherPath -Gcloud $mockGcloud -Apply -ConfirmProduction 6>&1 | Out-Null
    } catch {
        $verificationWorked = $_.Exception.Message -eq `
            "Production cost-metric exporter verification did not match the repository script."
    } finally {
        Remove-Item Env:MOCK_COST_METRIC_IGNORE_UPDATE -ErrorAction SilentlyContinue
    }
    if (-not $verificationWorked) { throw "Publisher accepted a post-update verification mismatch" }

    Set-MockState $oldScript
    $env:MOCK_COST_METRIC_POST_UPDATE_EXTRA_ENV = '1'
    $postUpdateContractWorked = $false
    try {
        & $publisherPath -Gcloud $mockGcloud -Apply -ConfirmProduction 6>&1 | Out-Null
    } catch {
        $postUpdateContractWorked = $_.Exception.Message -eq `
            "The production cost-metric environment contains an unknown or missing entry."
    } finally {
        Remove-Item Env:MOCK_COST_METRIC_POST_UPDATE_EXTRA_ENV -ErrorAction SilentlyContinue
    }
    if (-not $postUpdateContractWorked) { throw "Publisher accepted post-update contract drift" }

    Set-MockState $expected
    $env:MOCK_COST_METRIC_EXECUTE_FAIL = '1'
    $executionFailureWorked = $false
    try {
        & $publisherPath -Gcloud $mockGcloud -Apply -ConfirmProduction -ExecuteAfterUpdate 6>&1 | Out-Null
    } catch {
        $executionFailureWorked = $_.Exception.Message -eq "The post-publication cost-metric execution failed."
    } finally {
        Remove-Item Env:MOCK_COST_METRIC_EXECUTE_FAIL -ErrorAction SilentlyContinue
    }
    if (-not $executionFailureWorked) { throw "Publisher ignored a failed post-publication execution" }
} finally {
    Remove-Item Env:MOCK_COST_METRIC_STATE -ErrorAction SilentlyContinue
    Remove-Item Env:MOCK_COST_METRIC_LOG -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$global:LASTEXITCODE = 0
Write-Host "cost-metric Cloud Run publisher tests passed"

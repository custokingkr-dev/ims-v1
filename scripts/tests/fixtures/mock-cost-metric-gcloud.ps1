param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GcloudArguments)

$ErrorActionPreference = "Stop"
if (-not $env:MOCK_COST_METRIC_STATE -or -not $env:MOCK_COST_METRIC_LOG) {
    throw "Mock state and log paths are required."
}
$command = $GcloudArguments -join " "
Add-Content -LiteralPath $env:MOCK_COST_METRIC_LOG -Value $command

if ($command -match '^run jobs describe ') {
    Get-Content -Raw -LiteralPath $env:MOCK_COST_METRIC_STATE
    exit 0
}
if ($command -match '^run jobs update ') {
    $envArgument = @($GcloudArguments | Where-Object { $_ -like '--env-vars-file=*' })[0]
    if (-not $envArgument) { throw "Mock update did not receive an environment file." }
    $envPath = $envArgument.Substring('--env-vars-file='.Length)
    Add-Content -LiteralPath $env:MOCK_COST_METRIC_LOG -Value "ENV_PATH=$envPath"
    if ($env:MOCK_COST_METRIC_UPDATE_FAIL -eq '1') { exit 1 }
    if ($env:MOCK_COST_METRIC_IGNORE_UPDATE -eq '1') { exit 0 }

    $newEnvironment = Get-Content -Raw -LiteralPath $envPath | ConvertFrom-Json -AsHashtable
    if ($env:MOCK_COST_METRIC_POST_UPDATE_EXTRA_ENV -eq '1') {
        $newEnvironment['COST_METRIC_DATASET'] = 'unexpected'
    }
    $job = Get-Content -Raw -LiteralPath $env:MOCK_COST_METRIC_STATE | ConvertFrom-Json
    $job.spec.template.spec.template.spec.containers[0].env = @(
        $newEnvironment.GetEnumerator() | ForEach-Object {
            [pscustomobject]@{ name = [string]$_.Key; value = [string]$_.Value }
        }
    )
    $job | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $env:MOCK_COST_METRIC_STATE -Encoding utf8
    exit 0
}
if ($command -match '^run jobs execute ') {
    if ($env:MOCK_COST_METRIC_EXECUTE_FAIL -eq '1') { exit 1 }
    exit 0
}

throw "Unexpected mock gcloud command: $command"

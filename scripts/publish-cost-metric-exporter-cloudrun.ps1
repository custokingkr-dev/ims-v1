param(
    [string]$ProjectId = "custoking-prod",
    [string]$Region = "asia-south2",
    [string]$JobName = "ims-cost-metric-prod",
    [string]$ScriptPath = (Join-Path $PSScriptRoot "cost-metric-exporter.sh"),
    [switch]$Apply,
    [switch]$ConfirmProduction,
    [switch]$ExecuteAfterUpdate,
    [string]$Gcloud = $(if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" })
)

$ErrorActionPreference = "Stop"

if ($ProjectId -ne "custoking-prod" -or $Region -ne "asia-south2" -or $JobName -ne "ims-cost-metric-prod") {
    throw "This publisher is restricted to the production cost-metric job."
}
if (-not (Test-Path -LiteralPath $ScriptPath -PathType Leaf)) {
    throw "Cost-metric exporter script was not found: $ScriptPath"
}
$canonicalScriptPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "cost-metric-exporter.sh"))
$resolvedScriptPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $ScriptPath))
if (-not $resolvedScriptPath.Equals($canonicalScriptPath, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Only the canonical repository cost-metric-exporter.sh can be published."
}
if ($Apply -and -not $ConfirmProduction) {
    throw "Production publication requires both -Apply and -ConfirmProduction."
}
if ($ExecuteAfterUpdate -and -not $Apply) {
    throw "-ExecuteAfterUpdate requires -Apply."
}

function Invoke-GcloudJson {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = @(& $Gcloud @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the production cost-metric job."
    }
    return (($output -join "`n") | ConvertFrom-Json)
}

function Get-Sha256([byte[]]$Bytes) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant()
}

function Get-ValidatedEnvironment($Job) {
    $taskSpec = $Job.spec.template.spec.template.spec
    if ([string]$taskSpec.serviceAccountName -ne "cost-metric-exporter@custoking-prod.iam.gserviceaccount.com") {
        throw "The production job does not use the dedicated cost-metric exporter identity."
    }
    $containers = @($taskSpec.containers)
    if ($containers.Count -ne 1 -or [string]$containers[0].image -ne "google/cloud-sdk:slim") {
        throw "The production cost-metric job container contract has drifted."
    }
    $container = $containers[0]
    if ((@($container.command) -join " ") -ne "bash" `
            -or (@($container.args) -join " ") -ne '-c echo $SCRIPT_B64 | base64 -d > /tmp/exporter.sh && bash /tmp/exporter.sh') {
        throw "The production cost-metric job command contract has drifted."
    }

    $environment = [ordered]@{}
    foreach ($entry in @($container.env)) {
        if (-not $entry.name -or $null -eq $entry.value) {
            throw "The cost-metric publisher will not replace jobs containing secret or non-literal environment entries."
        }
        if ($environment.Contains([string]$entry.name)) {
            throw "The production cost-metric environment contains a duplicate entry."
        }
        $environment[[string]$entry.name] = [string]$entry.value
    }
    $requiredEnvironment = @{
        COST_METRIC_PROJECT = "custoking-prod"
        COST_METRIC_BQ_PROJECT = "custoking-prod"
        COST_METRIC_PUBLISH_PROJECT = "custoking-prod"
        COST_METRIC_SCOPE_PROJECT = "custoking-prod"
    }
    $allowedEnvironmentNames = @($requiredEnvironment.Keys) + "SCRIPT_B64"
    $unknownEnvironmentNames = @($environment.Keys | Where-Object { $_ -notin $allowedEnvironmentNames })
    if ($unknownEnvironmentNames.Count -gt 0 -or $environment.Count -ne $allowedEnvironmentNames.Count) {
        throw "The production cost-metric environment contains an unknown or missing entry."
    }
    foreach ($required in $requiredEnvironment.GetEnumerator()) {
        if ([string]$environment[$required.Key] -ne [string]$required.Value) {
            throw "The production cost-metric environment has drifted at $($required.Key)."
        }
    }
    if (-not $environment.Contains("SCRIPT_B64")) {
        throw "The production cost-metric job has no SCRIPT_B64 environment entry."
    }
    return ,$environment
}

$job = Invoke-GcloudJson -Arguments @(
    "run", "jobs", "describe", $JobName,
    "--project=$ProjectId", "--region=$Region", "--format=json"
)
$environment = Get-ValidatedEnvironment $job

$scriptBytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $ScriptPath))
$encodedScript = [Convert]::ToBase64String($scriptBytes)
$currentBytes = try { [Convert]::FromBase64String([string]$environment.SCRIPT_B64) } catch {
    throw "The production SCRIPT_B64 value is not valid base64."
}
$currentHash = Get-Sha256 $currentBytes
$candidateHash = Get-Sha256 $scriptBytes
Write-Host "Production cost-metric exporter current sha256=$currentHash candidate sha256=$candidateHash"

if (-not $Apply) {
    Write-Host "Dry run only. No Cloud Run job was changed."
    return
}
if ($currentHash -eq $candidateHash) {
    Write-Host "Production cost-metric exporter already matches the repository script."
} else {
    $environment.SCRIPT_B64 = $encodedScript
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $envPath = [IO.Path]::GetFullPath((Join-Path $tempRoot ("cost-metric-env-{0}.json" -f [guid]::NewGuid().ToString("N"))))
    if (-not $envPath.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The temporary environment file escaped the operating-system temporary directory."
    }
    try {
        [IO.File]::WriteAllText(
            $envPath,
            ($environment | ConvertTo-Json -Compress),
            [Text.UTF8Encoding]::new($false)
        )
        & $Gcloud run jobs update $JobName `
            "--project=$ProjectId" "--region=$Region" "--env-vars-file=$envPath" --quiet
        if ($LASTEXITCODE -ne 0) {
            throw "Could not publish the production cost-metric exporter."
        }
    } finally {
        if (Test-Path -LiteralPath $envPath -PathType Leaf) {
            Remove-Item -LiteralPath $envPath -Force
        }
    }

    $verifiedJob = Invoke-GcloudJson -Arguments @(
        "run", "jobs", "describe", $JobName,
        "--project=$ProjectId", "--region=$Region", "--format=json"
    )
    $verifiedEnvironment = Get-ValidatedEnvironment $verifiedJob
    if ([string]$verifiedEnvironment.SCRIPT_B64 -ne $encodedScript) {
        throw "Production cost-metric exporter verification did not match the repository script."
    }
    Write-Host "Production cost-metric exporter publication verified."
}

if ($ExecuteAfterUpdate) {
    & $Gcloud run jobs execute $JobName `
        "--project=$ProjectId" "--region=$Region" --wait --quiet
    if ($LASTEXITCODE -ne 0) {
        throw "The post-publication cost-metric execution failed."
    }
    Write-Host "Post-publication cost-metric execution succeeded."
}

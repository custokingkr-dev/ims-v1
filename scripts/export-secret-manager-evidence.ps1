param(
    [string]$ProjectId = "custoking-ims",
    [string]$OutputJson = "secret-manager-evidence.json",
    [string]$GcloudPath = "gcloud",
    [switch]$Mock
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "secret-manager-catalog.ps1")

$sdkGcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
if ($GcloudPath -eq "gcloud" -and (Test-Path -LiteralPath $sdkGcloud)) {
    $GcloudPath = $sdkGcloud
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

function Resolve-Replication {
    param([object]$Description)

    if ($Description.replication.automatic) {
        return "automatic"
    }
    if ($Description.replication.userManaged) {
        return "userManaged"
    }
    return $null
}

$secrets = New-Object System.Collections.Generic.List[object]

foreach ($entry in Get-SecretManagerCatalog) {
    if ($Mock) {
        $present = $true
        $enabledVersions = @([pscustomobject]@{
            name = "projects/$ProjectId/secrets/$($entry.Name)/versions/1"
            state = "ENABLED"
            createTime = (Get-Date).ToUniversalTime().ToString("o")
        })
        $replication = "automatic"
    } else {
        try {
            $description = Invoke-GcloudJson secrets describe $entry.Name "--project=$ProjectId" "--format=json"
            $present = $true
            $replication = Resolve-Replication $description
            $enabledVersions = @(Invoke-GcloudJson secrets versions list $entry.Name `
                "--project=$ProjectId" `
                "--filter=state=ENABLED" `
                "--limit=1" `
                "--sort-by=~createTime" `
                "--format=json")
        } catch {
            $present = $false
            $replication = $null
            $enabledVersions = @()
        }
    }

    $latestEnabledVersion = $null
    if ($enabledVersions.Count -gt 0 -and $enabledVersions[0].name -match "/versions/([^/]+)$") {
        $latestEnabledVersion = $Matches[1]
    }

    $secrets.Add([pscustomobject]@{
        name = $entry.Name
        purpose = $entry.Purpose
        present = $present
        enabledVersionCount = $enabledVersions.Count
        latestEnabledVersion = $latestEnabledVersion
        replication = $replication
    }) | Out-Null
}

[ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    projectId = $ProjectId
    secrets = $secrets
} | ConvertTo-Json -Depth 5 | Set-Content -Path $OutputJson -Encoding UTF8

$missing = @($secrets | Where-Object { -not $_.present -or [int]$_.enabledVersionCount -lt 1 })
Write-Host "Exported Secret Manager evidence: $OutputJson"
Write-Host "Secrets exported: $($secrets.Count)"
Write-Host "Missing or disabled secrets: $($missing.Count)"

param(
    [string]$ProjectId = "custoking-ims",
    [string]$Region = "asia-south2",
    [string]$OutputJson = "cloud-run-revisions.json",
    [string]$GcloudPath = "gcloud",
    [switch]$Mock
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")

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

function Get-ServiceName {
    param([object]$CatalogEntry)
    return $CatalogEntry.Image
}

$services = New-Object System.Collections.Generic.List[object]

foreach ($entry in Get-MicroserviceBuildCatalog) {
    $serviceName = Get-ServiceName $entry

    if ($Mock) {
        $mockRevision = "$serviceName-mock-revision"
        $service = [pscustomobject]@{
            status = [pscustomobject]@{
                url = "https://$serviceName.example.invalid"
                latestReadyRevisionName = $mockRevision
                latestCreatedRevisionName = $mockRevision
                traffic = @([pscustomobject]@{
                    revisionName = $mockRevision
                    percent = 100
                    latestRevision = $true
                })
            }
        }
        $revisions = @([pscustomobject]@{
            metadata = [pscustomobject]@{
                name = $mockRevision
                creationTimestamp = (Get-Date).ToUniversalTime().ToString("o")
            }
            spec = [pscustomobject]@{
                containers = @([pscustomobject]@{ image = "$($entry.Image):mock" })
            }
            status = [pscustomobject]@{
                conditions = @([pscustomobject]@{ type = "Ready"; status = "True" })
            }
        })
    } else {
        $service = Invoke-GcloudJson run services describe $serviceName `
            "--project=$ProjectId" `
            "--region=$Region" `
            "--format=json"

        $revisions = Invoke-GcloudJson run revisions list `
            "--service=$serviceName" `
            "--project=$ProjectId" `
            "--region=$Region" `
            "--format=json"
    }

    $traffic = @($service.status.traffic | ForEach-Object {
        [pscustomobject]@{
            revisionName = $_.revisionName
            percent = $_.percent
            latestRevision = $_.latestRevision
        }
    })

    $revisionRows = @($revisions | ForEach-Object {
        [pscustomobject]@{
            name = $_.metadata.name
            image = $_.spec.containers[0].image
            createdAt = $_.metadata.creationTimestamp
            readyStatus = @($_.status.conditions | Where-Object { $_.type -eq "Ready" } | Select-Object -First 1).status
        }
    })

    $services.Add([pscustomobject]@{
        name = $entry.Name
        cloudRunService = $serviceName
        url = $service.status.url
        latestReadyRevision = $service.status.latestReadyRevisionName
        latestCreatedRevision = $service.status.latestCreatedRevisionName
        traffic = $traffic
        revisions = $revisionRows
    }) | Out-Null
}

[ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    projectId = $ProjectId
    region = $Region
    services = $services
} | ConvertTo-Json -Depth 8 | Set-Content -Path $OutputJson -Encoding UTF8

Write-Host "Exported Cloud Run revision inventory: $OutputJson"
Write-Host "Services exported: $($services.Count)"

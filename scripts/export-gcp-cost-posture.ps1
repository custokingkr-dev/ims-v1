param(
    [string]$ProjectId = "custoking",
    [string]$Region = "asia-south2",
    [string]$OutputDirectory = "artifacts/gcp-cost-posture",
    [string]$GcloudPath = "gcloud",
    [switch]$Mock
)

$ErrorActionPreference = "Stop"

$sdkGcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
if ($GcloudPath -eq "gcloud" -and (Test-Path -LiteralPath $sdkGcloud)) {
    $GcloudPath = $sdkGcloud
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

function Invoke-GcloudJson {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)

    $output = & $GcloudPath @Args
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud command failed with exit code ${LASTEXITCODE}: gcloud $($Args -join ' ')"
    }
    if ([string]::IsNullOrWhiteSpace(($output -join ""))) {
        return $null
    }
    $text = ($output -join "`n").Trim()
    try {
        return $text | ConvertFrom-Json
    } catch {
        $objectStart = $text.IndexOf("{")
        $objectEnd = $text.LastIndexOf("}")
        $arrayStart = $text.IndexOf("[")
        $arrayEnd = $text.LastIndexOf("]")

        if ($objectStart -ge 0 -and $objectEnd -gt $objectStart -and ($arrayStart -lt 0 -or $objectStart -lt $arrayStart)) {
            return $text.Substring($objectStart, $objectEnd - $objectStart + 1) | ConvertFrom-Json
        }
        if ($arrayStart -ge 0 -and $arrayEnd -gt $arrayStart) {
            return $text.Substring($arrayStart, $arrayEnd - $arrayStart + 1) | ConvertFrom-Json
        }
        throw
    }
}

function Invoke-GcloudText {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)

    $output = & $GcloudPath @Args
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud command failed with exit code ${LASTEXITCODE}: gcloud $($Args -join ' ')"
    }
    return $output
}

function Get-PropertyValue {
    param(
        [object]$Object,
        [string]$Name
    )

    if ($null -eq $Object) {
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-EnvFromName {
    param([string]$Name)

    if ($Name -match "-prod$") {
        return "prod"
    }
    if ($Name -match "-dev$") {
        return "dev"
    }
    return ""
}

function Expand-Items {
    param([object]$Items)

    foreach ($candidate in @($Items)) {
        if ($candidate -is [System.Array]) {
            foreach ($item in $candidate) {
                $item
            }
        } elseif ($null -ne $candidate) {
            $candidate
        }
    }
}

function Get-CloudRunSummary {
    param([object[]]$Services)

    $serviceRows = @(Expand-Items $Services)
    return @($serviceRows | Sort-Object { $_.metadata.name } | ForEach-Object {
        $annotations = $_.spec.template.metadata.annotations
        $container = @($_.spec.template.spec.containers)[0]
        $resources = $container.resources.limits
        $minScale = Get-PropertyValue $annotations "autoscaling.knative.dev/minScale"
        if ([string]::IsNullOrWhiteSpace([string]$minScale)) {
            $minScale = "0(unset)"
        }
        $maxScale = Get-PropertyValue $annotations "autoscaling.knative.dev/maxScale"
        if ([string]::IsNullOrWhiteSpace([string]$maxScale)) {
            $maxScale = "(unset)"
        }

        [pscustomobject]@{
            service = $_.metadata.name
            env = Get-EnvFromName $_.metadata.name
            url = $_.status.url
            cpu = Get-PropertyValue $resources "cpu"
            memory = Get-PropertyValue $resources "memory"
            concurrency = $_.spec.template.spec.containerConcurrency
            minScale = $minScale
            maxScale = $maxScale
            startupCpuBoost = Get-PropertyValue $annotations "run.googleapis.com/startup-cpu-boost"
            directVpc = [bool](Get-PropertyValue $annotations "run.googleapis.com/network-interfaces")
            vpcEgress = Get-PropertyValue $annotations "run.googleapis.com/vpc-access-egress"
            latestReadyRevision = $_.status.latestReadyRevisionName
            labels = $_.metadata.labels
        }
    })
}

function Get-CloudSqlSummary {
    param([object[]]$Instances)

    $instanceRows = @(Expand-Items $Instances)
    return @($instanceRows | Sort-Object name | ForEach-Object {
        $flags = @($_.settings.databaseFlags | ForEach-Object { "$($_.name)=$($_.value)" })
        [pscustomobject]@{
            name = $_.name
            env = if ($_.name -match "prod") { "prod" } elseif ($_.name -match "dev") { "dev" } else { "" }
            state = $_.state
            tier = $_.settings.tier
            edition = $_.settings.edition
            version = $_.databaseVersion
            region = $_.region
            zone = $_.gceZone
            activationPolicy = $_.settings.activationPolicy
            availabilityType = $_.settings.availabilityType
            diskSizeGb = $_.settings.dataDiskSizeGb
            diskType = $_.settings.dataDiskType
            storageAutoResize = $_.settings.storageAutoResize
            backupEnabled = $_.settings.backupConfiguration.enabled
            retainedBackups = $_.settings.backupConfiguration.backupRetentionSettings.retainedBackups
            deletionProtection = $_.settings.deletionProtectionEnabled
            flags = $flags
            privateIp = @($_.ipAddresses | Where-Object { $_.type -eq "PRIVATE" } | Select-Object -First 1).ipAddress
        }
    })
}

function Get-BucketSizeMap {
    param([object[]]$Buckets)

    $sizes = @{}
    foreach ($bucket in @(Expand-Items $Buckets)) {
        $name = $bucket.name
        try {
            $line = & $GcloudPath storage du --summarize "gs://$name" 2>$null
            if ($LASTEXITCODE -ne 0) {
                $sizes[$name] = $null
                continue
            }
            if ($line -and $line.Count -gt 0) {
                $firstLine = if ($line -is [System.Array]) { [string]$line[0] } else { [string]$line }
                $parts = ($firstLine -split "\s+") | Where-Object { $_ }
                if ($parts.Count -gt 0) {
                    $sizes[$name] = [int64]$parts[0]
                }
            }
        } catch {
            $sizes[$name] = $null
        }
    }
    return $sizes
}

function Get-BucketSummary {
    param(
        [object[]]$Buckets,
        [hashtable]$Sizes
    )

    $bucketRows = @(Expand-Items $Buckets)
    return @($bucketRows | Sort-Object name | ForEach-Object {
        [pscustomobject]@{
            name = $_.name
            location = $_.location
            storageClass = $_.default_storage_class
            bytes = $Sizes[$_.name]
            lifecycleRules = @($_.lifecycle_config.rule).Count
            softDeleteRetentionSeconds = $_.soft_delete_policy.retentionDurationSeconds
            versioning = [bool]$_.versioning_enabled
            publicAccessPrevention = $_.public_access_prevention
        }
    })
}

function Add-MarkdownTable {
    param(
        [System.Text.StringBuilder]$Builder,
        [string[]]$Headers,
        [object]$Rows
    )

    $rowList = New-Object System.Collections.Generic.List[object]
    foreach ($candidate in @($Rows)) {
        if ($candidate -is [System.Array]) {
            foreach ($item in $candidate) {
                $rowList.Add($item) | Out-Null
            }
        } elseif ($null -ne $candidate) {
            $rowList.Add($candidate) | Out-Null
        }
    }

    [void]$Builder.AppendLine("| $($Headers -join ' | ') |")
    [void]$Builder.AppendLine("| $((@($Headers | ForEach-Object { '---' })) -join ' | ') |")
    foreach ($row in $rowList) {
        $values = @($Headers | ForEach-Object {
            $value = Get-PropertyValue $row $_
            if ($null -eq $value) {
                ""
            } elseif ($value -is [System.Array]) {
                ([string]::Join(", ", @($value)) -replace "\|", "/")
            } else {
                ([string]$value -replace "\|", "/")
            }
        })
        [void]$Builder.AppendLine("| $($values -join ' | ') |")
    }
    [void]$Builder.AppendLine()
}

if ($Mock) {
    $cloudRunServices = @()
    $cloudSqlInstances = @()
    $topics = @()
    $subscriptions = @()
    $buckets = @()
    $artifactRepository = $null
    $artifactRepositoryListEntry = $null
    $loggingBuckets = @()
    $cloudSqlRecommendations = @()
} else {
    $cloudRunServices = @(Invoke-GcloudJson run services list "--platform=managed" "--region=$Region" "--project=$ProjectId" "--format=json")
    $cloudSqlInstances = @(Invoke-GcloudJson sql instances list "--project=$ProjectId" "--format=json")
    $topics = @(Invoke-GcloudJson pubsub topics list "--project=$ProjectId" "--format=json")
    $subscriptions = @(Invoke-GcloudJson pubsub subscriptions list "--project=$ProjectId" "--format=json")
    $buckets = @(Invoke-GcloudJson storage buckets list "--project=$ProjectId" "--format=json")
    $artifactRepositories = @(Invoke-GcloudJson artifacts repositories list "--project=$ProjectId" "--location=$Region" "--format=json")
    $artifactRepositoryListEntry = @(Expand-Items $artifactRepositories | Where-Object { $_.name -like "*/repositories/custoking" } | Select-Object -First 1)
    $artifactRepository = Invoke-GcloudJson artifacts repositories describe custoking "--project=$ProjectId" "--location=$Region" "--format=json"
    $loggingBuckets = @(Invoke-GcloudJson logging buckets list "--project=$ProjectId" "--location=global" "--format=json")
    $cloudSqlRecommendations = @(Invoke-GcloudJson recommender recommendations list "--project=$ProjectId" "--location=$Region" "--recommender=google.cloudsql.instance.OverprovisionedRecommender" "--format=json")
}

$bucketSizes = if ($Mock) { @{} } else { Get-BucketSizeMap $buckets }

$posture = [ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    projectId = $ProjectId
    region = $Region
    cloudRun = Get-CloudRunSummary $cloudRunServices
    cloudSql = Get-CloudSqlSummary $cloudSqlInstances
    pubsub = [ordered]@{
        topics = @(Expand-Items $topics | ForEach-Object { $_.name })
        subscriptions = @(Expand-Items $subscriptions | ForEach-Object {
            $endpoint = [string]::Join("", @($_.pushConfig.pushEndpoint)).Trim()
            $endpointHost = ""
            if (-not [string]::IsNullOrWhiteSpace($endpoint)) {
                try {
                    $endpointHost = ([uri]$endpoint).Host
                } catch {
                    $endpointHost = ""
                }
            }
            [pscustomobject]@{
                name = $_.name
                topic = $_.topic
                state = $_.state
                ackDeadlineSeconds = $_.ackDeadlineSeconds
                messageRetentionDuration = $_.messageRetentionDuration
                pushEndpointHost = $endpointHost
            }
        })
    }
    storage = Get-BucketSummary $buckets $bucketSizes
    artifactRegistry = if ($null -eq $artifactRepository) {
        $null
    } else {
        $cleanupPolicyCount = 0
        if ($null -ne $artifactRepository.cleanupPolicies) {
            $cleanupPolicyCount = @($artifactRepository.cleanupPolicies.PSObject.Properties).Count
        }
        [pscustomobject]@{
            name = $artifactRepository.name
            format = $artifactRepository.format
            sizeBytes = if ($artifactRepository.sizeBytes) { $artifactRepository.sizeBytes } else { $artifactRepositoryListEntry.sizeBytes }
            cleanupPolicyCount = $cleanupPolicyCount
            vulnerabilityScanning = $artifactRepository.vulnerabilityScanningConfig.enablementState
        }
    }
    logging = @(Expand-Items $loggingBuckets | ForEach-Object {
        [pscustomobject]@{
            name = $_.name
            retentionDays = $_.retentionDays
            lifecycleState = $_.lifecycleState
            locked = $_.locked
        }
    })
    recommenders = [ordered]@{
        cloudSqlOverprovisioned = @(Expand-Items $cloudSqlRecommendations | ForEach-Object {
            [pscustomobject]@{
                name = $_.name
                description = $_.description
                priority = $_.priority
                state = $_.stateInfo.state
            }
        })
    }
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$jsonPath = Join-Path $OutputDirectory "gcp-cost-posture-$timestamp.json"
$mdPath = Join-Path $OutputDirectory "gcp-cost-posture-$timestamp.md"

$posture | ConvertTo-Json -Depth 12 | Set-Content -Path $jsonPath -Encoding UTF8

$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# GCP Cost Posture")
[void]$md.AppendLine()
[void]$md.AppendLine("Generated UTC: $($posture.generatedAtUtc)")
[void]$md.AppendLine()
[void]$md.AppendLine("Project: $ProjectId")
[void]$md.AppendLine()
[void]$md.AppendLine("Region: $Region")
[void]$md.AppendLine()

[void]$md.AppendLine("## Cloud Run")
[void]$md.AppendLine()
Add-MarkdownTable $md @("service", "env", "cpu", "memory", "concurrency", "minScale", "maxScale", "startupCpuBoost", "directVpc") $posture.cloudRun

[void]$md.AppendLine("## Cloud SQL")
[void]$md.AppendLine()
Add-MarkdownTable $md @("name", "env", "state", "tier", "activationPolicy", "availabilityType", "diskSizeGb", "backupEnabled", "retainedBackups", "privateIp") $posture.cloudSql

[void]$md.AppendLine("## Storage")
[void]$md.AppendLine()
Add-MarkdownTable $md @("name", "location", "storageClass", "bytes", "lifecycleRules", "versioning", "softDeleteRetentionSeconds") $posture.storage

[void]$md.AppendLine("## Artifact Registry")
[void]$md.AppendLine()
if ($null -ne $posture.artifactRegistry) {
    Add-MarkdownTable $md @("name", "format", "sizeBytes", "cleanupPolicyCount", "vulnerabilityScanning") @($posture.artifactRegistry)
} else {
    [void]$md.AppendLine("No Artifact Registry repository summary exported.")
    [void]$md.AppendLine()
}

[void]$md.AppendLine("## Logging")
[void]$md.AppendLine()
Add-MarkdownTable $md @("name", "retentionDays", "lifecycleState", "locked") $posture.logging

[void]$md.AppendLine("## Recommenders")
[void]$md.AppendLine()
[void]$md.AppendLine("Cloud SQL overprovisioned recommendations: $(@($posture.recommenders.cloudSqlOverprovisioned).Count)")
[void]$md.AppendLine()

$md.ToString() | Set-Content -Path $mdPath -Encoding UTF8

Write-Host "Exported GCP cost posture JSON: $jsonPath"
Write-Host "Exported GCP cost posture Markdown: $mdPath"

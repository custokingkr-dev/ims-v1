param(
    [string]$ProjectId = "custoking-ims",
    [string]$Region = "asia-south2",
    [string]$Repository = "custoking",
    [string]$Tag = "latest",
    [string]$OutputJson = "image-digests.json",
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

function Try-Invoke-GcloudJson {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $GcloudPath @Args 2>$null
        if ($LASTEXITCODE -ne 0) {
            return $null
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ([string]::IsNullOrWhiteSpace(($output -join ""))) {
        return $null
    }
    return ($output -join "`n") | ConvertFrom-Json
}

function Resolve-Digest {
    param([object]$ImageDescription)

    foreach ($candidate in @(
        $ImageDescription.image_summary.digest,
        $ImageDescription.digest,
        $ImageDescription.metadata.imageSizeBytes)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and "$candidate" -like "sha256:*") {
            return "$candidate"
        }
    }

    if ($ImageDescription.name -match "@(sha256:[a-fA-F0-9]+)") {
        return $Matches[1]
    }

    return $null
}

function Resolve-DeployedImageRef {
    param([string]$ServiceName)

    $service = Invoke-GcloudJson run services describe $ServiceName `
        "--project=$ProjectId" `
        "--region=$Region" `
        "--format=json"

    $image = $service.spec.template.spec.containers[0].image
    if ([string]::IsNullOrWhiteSpace($image)) {
        throw "Could not resolve deployed image for Cloud Run service $ServiceName"
    }
    return "$image"
}

function Resolve-TagFromImageRef {
    param([string]$ImageRef)

    if ($ImageRef -match ":([^:/@]+)$") {
        return $Matches[1]
    }
    return ""
}

$images = New-Object System.Collections.Generic.List[object]

foreach ($entry in Get-MicroserviceBuildCatalog) {
    $imageRef = "$Region-docker.pkg.dev/$ProjectId/$Repository/$($entry.Image):$Tag"
    $resolvedTag = $Tag

    if ($Mock) {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $hash = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($imageRef))
            $digest = "sha256:" + ([System.BitConverter]::ToString($hash).Replace("-", "").ToLowerInvariant())
        } finally {
            $sha.Dispose()
        }
    } else {
        $description = Try-Invoke-GcloudJson artifacts docker images describe $imageRef `
            "--project=$ProjectId" `
            "--format=json"
        if ($null -eq $description) {
            $imageRef = Resolve-DeployedImageRef $entry.Image
            $resolvedTag = Resolve-TagFromImageRef $imageRef
            $description = Invoke-GcloudJson artifacts docker images describe $imageRef `
                "--project=$ProjectId" `
                "--format=json"
        }
        $digest = Resolve-Digest $description
        if ([string]::IsNullOrWhiteSpace($digest)) {
            throw "Could not resolve digest for image $imageRef"
        }
    }

    $images.Add([pscustomobject]@{
        name = $entry.Name
        image = $entry.Image
        tag = $resolvedTag
        imageRef = $imageRef
        digest = $digest
        immutableRef = "$Region-docker.pkg.dev/$ProjectId/$Repository/$($entry.Image)@$digest"
    }) | Out-Null
}

[ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    projectId = $ProjectId
    region = $Region
    repository = $Repository
    tag = $Tag
    images = $images
} | ConvertTo-Json -Depth 5 | Set-Content -Path $OutputJson -Encoding UTF8

Write-Host "Exported image digest inventory: $OutputJson"
Write-Host "Images exported: $($images.Count)"

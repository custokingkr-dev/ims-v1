param(
    [string]$ProjectId = "custoking",
    [string]$Region = "asia-south2",
    [ValidateSet("dev", "prod")]
    [string]$Environment = "dev",
    [string]$Repository = "custoking",
    [string]$CommitSha,
    [string]$DeployServices = "frontend",
    [string]$ArtifactDirectory = "artifacts/gcp-deployment",
    [string]$GcloudPath = "gcloud",
    [string]$DirectSmokeServiceAccount,
    [string]$SmokeSchoolId = "",
    [switch]$SkipLocalVerification,
    [switch]$SkipCloudBuild,
    [switch]$SkipDirectSmoke,
    [switch]$SkipDeploymentReadinessSmoke
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$sdkGcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
if ($GcloudPath -eq "gcloud" -and (Test-Path -LiteralPath $sdkGcloud)) {
    $GcloudPath = $sdkGcloud
}
if ([string]::IsNullOrWhiteSpace($CommitSha)) {
    $CommitSha = (& git -C $repoRoot rev-parse --short HEAD).Trim()
    if ([string]::IsNullOrWhiteSpace($CommitSha)) {
        $CommitSha = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmss")
    }
}
if ([string]::IsNullOrWhiteSpace($DirectSmokeServiceAccount)) {
    $DirectSmokeServiceAccount = "direct-service-smoke@$ProjectId.iam.gserviceaccount.com"
}

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $repoRoot $Path
}

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Block
    )
    Write-Host ""
    Write-Host "==> $Name"
    & $Block
    if ($LASTEXITCODE -ne 0) {
        throw "Step failed: $Name"
    }
    Write-Host "OK: $Name"
}

$artifactRoot = Resolve-RepoPath $ArtifactDirectory
if (-not (Test-Path -LiteralPath $artifactRoot)) {
    New-Item -ItemType Directory -Path $artifactRoot | Out-Null
}

if (-not $SkipLocalVerification) {
    Invoke-Step "verify microservice migration" {
        powershell -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/verify-microservice-migration.ps1")
    }
    Invoke-Step "test microservices and frontend" {
        powershell -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/invoke-microservice-tests.ps1")
    }
    Invoke-Step "build frontend" {
        Push-Location (Join-Path $repoRoot "frontend")
        try {
            npm run build
        } finally {
            Pop-Location
        }
    }
}

Invoke-Step "capture pre-deploy production evidence" {
    powershell -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/new-production-evidence-baseline.ps1") `
        -ProjectId $ProjectId `
        -Region $Region `
        -Repository $Repository `
        -Tag $CommitSha `
        -GcloudPath $GcloudPath `
        -OutputDirectory (Join-Path $artifactRoot "pre-deploy")
}

if (-not $SkipCloudBuild) {
    Invoke-Step "submit Cloud Build service-only deployment" {
        & $GcloudPath config set project $ProjectId | Out-Host
        & $GcloudPath builds submit `
            "--config=cloudbuild.yaml" `
            "--substitutions=_COMMIT_SHA=$CommitSha,_REGION=$Region,_AR_REPO=$Repository,_DEPLOY_SERVICES=$DeployServices" `
            "--project=$ProjectId" `
            "."
    }
}

$gatewayBaseUrl = (& $GcloudPath run services describe "custoking-api-gateway-$Environment" "--project=$ProjectId" "--region=$Region" "--format=value(status.url)").Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($gatewayBaseUrl)) {
    throw "Could not resolve custoking-api-gateway URL."
}

if (-not $SkipDirectSmoke) {
    $generatedJob = Join-Path $artifactRoot "direct-service-smoke-job.generated.yaml"
    Invoke-Step "ensure direct service smoke identity" {
        powershell -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/ensure-direct-service-smoke-identity.ps1") `
            -ProjectId $ProjectId `
            -Region $Region `
            -Environments @($Environment) `
            -GcloudPath $GcloudPath `
            -DirectSmokeServiceAccount $DirectSmokeServiceAccount
    }
    Invoke-Step "generate direct service smoke job" {
        powershell -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/new-direct-service-smoke-job.ps1") `
            -ProjectId $ProjectId `
            -Region $Region `
            -Environment $Environment `
            -GcloudPath $GcloudPath `
            -DirectSmokeServiceAccount $DirectSmokeServiceAccount `
            -SmokeSchoolId $SmokeSchoolId `
            -OutputPath $generatedJob
    }
    Invoke-Step "replace direct service smoke job" {
        & $GcloudPath run jobs replace $generatedJob "--project=$ProjectId" "--region=$Region"
    }
    Invoke-Step "execute direct service smoke job" {
        & $GcloudPath run jobs execute ims-direct-service-smoke "--project=$ProjectId" "--region=$Region" "--wait"
    }
}

if (-not $SkipDeploymentReadinessSmoke) {
    Invoke-Step "run gateway deployment readiness smoke" {
        powershell -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/smoke-deployment-readiness.ps1") `
            -GatewayBaseUrl $gatewayBaseUrl `
            -OutputJson (Join-Path $artifactRoot "deployment-smoke.json")
    }
}

Invoke-Step "capture post-deploy production evidence" {
    powershell -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/new-production-evidence-baseline.ps1") `
        -ProjectId $ProjectId `
        -Region $Region `
        -Repository $Repository `
        -Tag $CommitSha `
        -GcloudPath $GcloudPath `
        -OutputDirectory (Join-Path $artifactRoot "post-deploy")
}

Invoke-Step "real environment readiness preflight" {
    $deploymentSmoke = Join-Path $artifactRoot "deployment-smoke.json"
    $legacyState = Join-Path $artifactRoot "legacy-compatibility-state.json"
    $args = @(
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $repoRoot "scripts/invoke-real-environment-readiness-preflight.ps1"),
        "-ProjectId", $ProjectId,
        "-Region", $Region,
        "-GatewayBaseUrl", $gatewayBaseUrl,
        "-GcloudPath", $GcloudPath,
        "-OutputJson", (Join-Path $artifactRoot "real-environment-readiness-preflight.json"),
        "-OutputMarkdown", (Join-Path $artifactRoot "real-environment-readiness-preflight.md")
    )
    if (Test-Path -LiteralPath $deploymentSmoke) {
        $args += @("-DeploymentSmokeJson", $deploymentSmoke)
    }
    if (Test-Path -LiteralPath $legacyState) {
        $args += @("-LegacyCompatibilityJson", $legacyState)
    }
    powershell @args
}

Write-Host ""
Write-Host "Deployment flow completed. Artifacts: $artifactRoot"

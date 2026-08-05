param(
  [Parameter(Mandatory = $true)]
  [string]$ProjectId,

  [Parameter(Mandatory = $true)]
  [string]$Region,

  [Parameter(Mandatory = $true)]
  [ValidateSet("dev", "prod")]
  [string]$Environment,

  [Parameter(Mandatory = $true)]
  [string]$CommitSha,

  [Parameter(Mandatory = $true)]
  [string]$RunAttempt,

  [Parameter(Mandatory = $true)]
  [string]$ImagesJson,

  [string]$OutputPath = "release-evidence/deployment.json",

  [switch]$WaitForRollout,

  [switch]$AutoAdvanceCanary,

  [int]$RolloutTimeoutMinutes = 45
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if (-not (Test-Path -LiteralPath $ImagesJson)) {
  throw "Release image evidence not found: $ImagesJson"
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$skaffoldFile = Join-Path $repoRoot "deploy/skaffold.yaml"
$images = @((Get-Content -Raw -Path $ImagesJson | ConvertFrom-Json).services)
$releaseOrder = @(
  "school-core-service",
  "identity-service",
  "operations-service",
  "billing-service",
  "platform-service",
  "api-gateway",
  "frontend"
)
$byService = @{}
foreach ($image in $images) {
  $byService[[string]$image.service] = $image
}

$shortSha = $CommitSha.Substring(0, [Math]::Min(12, $CommitSha.Length))
$releaseId = ("rel-{0}-{1}-{2}" -f $Environment, $shortSha, $RunAttempt).ToLowerInvariant()
$deployments = @()

function Write-DeploymentEvidence {
  $outputDirectory = Split-Path -Parent $OutputPath
  if ($outputDirectory) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
  }

  [ordered]@{
    mode = "cloud-deploy"
    environment = $Environment
    createdAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    services = $deployments
  } | ConvertTo-Json -Depth 10 | Set-Content -Path $OutputPath
}

foreach ($service in $releaseOrder) {
  $image = $byService[$service]
  if (-not $image) {
    continue
  }

  $pipeline = "custoking-$service-$Environment"
  Write-Host "Creating Cloud Deploy release $pipeline/$releaseId."
  & $GcloudCommand deploy releases create $releaseId `
    "--project=$ProjectId" `
    "--region=$Region" `
    "--delivery-pipeline=$pipeline" `
    "--to-target=$service-$Environment" `
    "--skaffold-file=$skaffoldFile" `
    "--images=$($image.image)=$($image.immutableRef)" `
    "--deploy-parameters=git_sha=$CommitSha" `
    --quiet
  if ($LASTEXITCODE -ne 0) {
    throw "Could not create Cloud Deploy release $pipeline/$releaseId."
  }

  $rollout = (& $GcloudCommand deploy rollouts list `
    "--project=$ProjectId" `
    "--region=$Region" `
    "--delivery-pipeline=$pipeline" `
    "--release=$releaseId" `
    --limit=1 `
    --format="value(name)").Trim()
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($rollout)) {
    throw "Cloud Deploy release $pipeline/$releaseId did not create an initial rollout."
  }

  $deployments += [ordered]@{
    service = $service
    cloudRunService = "custoking-$service-$Environment"
    image = $image.immutableRef
    pipeline = $pipeline
    release = $releaseId
    target = "$service-$Environment"
    rollout = $rollout.Split("/")[-1]
  }

  # Persist after every release so failed jobs retain actionable rollout evidence.
  Write-DeploymentEvidence

  if ($WaitForRollout) {
    $waitArguments = @{
      ProjectId = $ProjectId
      Region = $Region
      Pipeline = $pipeline
      Release = $releaseId
      Rollout = $rollout.Split("/")[-1]
      TimeoutMinutes = $RolloutTimeoutMinutes
    }
    if ($AutoAdvanceCanary) {
      $waitArguments.AutoAdvanceCanary = $true
    }

    & (Join-Path $PSScriptRoot "wait-clouddeploy-rollout.ps1") @waitArguments
  }
}

Write-DeploymentEvidence

Write-Host "Created Cloud Deploy releases for $($deployments.Count) service(s)."

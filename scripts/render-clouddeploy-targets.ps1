param(
  [Parameter(Mandatory = $true)]
  # Stage is intentionally unavailable until stage target manifests, runtime identities, and a
  # protected GitHub Environment are implemented as one reviewed contract.
  [ValidateSet("dev", "prod")]
  [string]$Environment,

  [string]$TemplatePath,
  [string]$OutputPath
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if (-not $TemplatePath) {
  $TemplatePath = Join-Path $repoRoot "deploy/clouddeploy/targets-$Environment.yaml"
}
if (-not $OutputPath) {
  $OutputPath = Join-Path $repoRoot "artifacts/clouddeploy/targets-$Environment.rendered.yaml"
}

function Require-DeploymentValue([string]$Name) {
  $prefix = $Environment.ToUpperInvariant()
  $value = [Environment]::GetEnvironmentVariable("${prefix}_$Name")
  if ([string]::IsNullOrWhiteSpace($value)) {
    $value = [Environment]::GetEnvironmentVariable($Name)
  }
  if ([string]::IsNullOrWhiteSpace($value)) {
    throw "Required environment variable '${prefix}_$Name' or '$Name' is missing for Cloud Deploy $Environment target rendering."
  }
  return $value.Trim()
}

# GCP_PROJECT_ID / GCP_PROJECT_NUMBER / GCP_REGION are substituted so one template serves any project.
# Before the split these were hardcoded to "custoking", which silently pointed every rendered target at
# the old project.
$projectId = Require-DeploymentValue "GCP_PROJECT_ID"
$replacements = @{
  "__DB_HOST__" = Require-DeploymentValue "DB_HOST"
  "__DB_NAME__" = Require-DeploymentValue "DB_NAME"
  "__STUDENT_PHOTO_IMPORT_DRIVE_ROOT_FOLDER_ID__" = Require-DeploymentValue "STUDENT_PHOTO_IMPORT_DRIVE_ROOT_FOLDER_ID"
  "__PROJECT_ID__" = $projectId
  "__PROJECT_NUMBER__" = Require-DeploymentValue "GCP_PROJECT_NUMBER"
  "__REGION__" = Require-DeploymentValue "GCP_REGION"
  # The student-photo bucket used to be derived as custoking-student-photos-<env>. Bucket names are
  # globally unique, so the destination cannot reuse that name and the derivation would have pointed the
  # new project at the OLD project's bucket. It must be an explicit parameter.
  "__PHOTO_BUCKET__" = Require-DeploymentValue "STUDENT_PHOTO_BUCKET"
}

$text = Get-Content -Raw -Path $TemplatePath
foreach ($key in $replacements.Keys) {
  $text = $text.Replace($key, $replacements[$key])
}

$targetCount = ([regex]::Matches($text, '(?m)^kind:\s*Target\s*$')).Count
if ($targetCount -ne 7) {
  throw "Cloud Deploy $Environment target template must contain exactly seven service targets; found $targetCount."
}

$expectedExecutionAccount = "clouddeploy-$Environment-deployer@$projectId.iam.gserviceaccount.com"
$executionAccountCount = ([regex]::Matches(
    $text,
    "(?m)^\s*serviceAccount:\s*$([regex]::Escape($expectedExecutionAccount))\s*$"
  )).Count
if ($executionAccountCount -ne $targetCount) {
  throw "Every Cloud Deploy $Environment target must use $expectedExecutionAccount for RENDER and DEPLOY."
}

$runtimeAccounts = @([regex]::Matches($text, '(?m)^\s*runtime_service_account:\s*(\S+)\s*$') |
  ForEach-Object { $_.Groups[1].Value })
if ($runtimeAccounts.Count -ne $targetCount -or
    @($runtimeAccounts | Where-Object { $_ -notmatch "-$Environment@$([regex]::Escape($projectId))\.iam\.gserviceaccount\.com$" }).Count -gt 0) {
  throw "Every Cloud Deploy $Environment target must use its environment-specific dedicated runtime identity."
}

$unresolved = [regex]::Matches($text, "__[A-Z0-9_]+__") | ForEach-Object { $_.Value } | Sort-Object -Unique
if ($unresolved) {
  throw "Unresolved Cloud Deploy placeholders remain in ${TemplatePath}: $($unresolved -join ', ')"
}

$outputDirectory = Split-Path -Parent $OutputPath
if ($outputDirectory) {
  New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}

Set-Content -Path $OutputPath -Value $text -NoNewline
Write-Host "Rendered Cloud Deploy $Environment targets to $OutputPath"

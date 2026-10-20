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

# The precommit erasure feature may only bind the explicitly provisioned dev source.
# Production remains disabled until its own separately reviewed journal contract exists.
$schoolCoreTargets = @($text -split '(?m)^---\s*$' | Where-Object {
  $_ -match "(?m)^  name:\s*school-core-service-$Environment\s*$"
})
if ($schoolCoreTargets.Count -ne 1) { throw "Exactly one school-core-service-$Environment target is required." }
function Read-SchoolCoreParameter([string]$Name) {
  $parameterMatches = [regex]::Matches($schoolCoreTargets[0], "(?m)^  $([regex]::Escape($Name)):[ \t]*([^\r\n]*)")
  if ($parameterMatches.Count -ne 1) { throw "School target must declare exactly one '$Name'." }
  $value = $parameterMatches[0].Groups[1].Value.Trim()
  if ($value -match '^"([^"]*)"$' -or $value -match "^'([^']*)'$") { return $Matches[1] }
  return $value
}
$journalEnabled = Read-SchoolCoreParameter 'student_erasure_journal_enabled'
if ($journalEnabled -cnotin @('true','false')) { throw 'Erasure journal enablement must be explicit.' }
if ($journalEnabled -ceq 'true') {
  if ($Environment -cne 'dev' -or $projectId -cne 'custoking-dev' -or $replacements['__DB_NAME__'] -cne 'custoking_dev' -or
      (Read-SchoolCoreParameter 'student_erasure_journal_project_id') -cne 'custoking-dev' -or
      (Read-SchoolCoreParameter 'student_erasure_journal_bucket') -cne 'custoking-dev-erasure-journal') {
    throw 'The enabled erasure journal must bind the exact dedicated dev project, database and bucket.'
  }
  $controlPath = Join-Path $repoRoot 'deploy/gcp/erasure-journal/dev-source-control.json'
  $control = Get-Content -LiteralPath $controlPath -Raw | ConvertFrom-Json
  $hasher = [System.Security.Cryptography.SHA256]::Create()
  try { $controlSha = ([BitConverter]::ToString($hasher.ComputeHash([IO.File]::ReadAllBytes($controlPath)))).Replace('-','').ToLowerInvariant() }
  finally { $hasher.Dispose() }
  if ($control.project -cne 'custoking-dev' -or $control.sourceInstance -cne 'custoking-db-dev' -or
      $control.database -cne 'custoking_dev' -or $control.state -cne 'ACTIVE' -or $control.schemaVersion -ne 1 -or
      (Read-SchoolCoreParameter 'student_erasure_journal_source_lineage_id') -cne $control.sourceLineageId -or
      (Read-SchoolCoreParameter 'student_erasure_journal_restore_epoch') -cne $control.restoreEpoch -or
      (Read-SchoolCoreParameter 'student_erasure_journal_epoch_generation') -cnotmatch '^[1-9][0-9]{0,18}$' -or
      (Read-SchoolCoreParameter 'student_erasure_journal_epoch_sha256') -cne $controlSha) {
    throw 'Erasure deployment must pin the reviewed source control tuple and canonical body hash.'
  }
} else {
  foreach ($parameter in @('project_id','bucket','source_lineage_id','restore_epoch','epoch_sha256')) {
    if ((Read-SchoolCoreParameter "student_erasure_journal_$parameter") -cne '') { throw 'Disabled journal must not carry another source lineage.' }
  }
  if ((Read-SchoolCoreParameter 'student_erasure_journal_epoch_generation') -cne '0') { throw 'Disabled journal generation must be zero.' }
}

# Browser CORS needs both Cloud Run aliases, while frontend_url stays one upstream URL.
# These are exact reviewed aliases, not a hostname pattern or a runtime request-derived list.
# A new project/hash needs an explicit source review; canonical-only is always available.
$gatewayTargets = @($text -split '(?m)^---\s*$' | Where-Object {
  $_ -match "(?m)^  name:\s*api-gateway-$Environment\s*$"
})
if ($gatewayTargets.Count -ne 1) {
  throw "Exactly one api-gateway-$Environment target is required for gateway CORS."
}
function Read-GatewayParameter([string]$Name) {
  $parameterMatches = [regex]::Matches($gatewayTargets[0], "(?m)^  $([regex]::Escape($Name)):[ \t]*([^\r\n]*)")
  if ($parameterMatches.Count -ne 1) {
    throw "Gateway CORS target must declare exactly one '$Name' parameter."
  }
  $value = $parameterMatches[0].Groups[1].Value.Trim()
  if ($value -match '^"([^"]*)"$' -or $value -match "^'([^']*)'$") {
    return $Matches[1]
  }
  return $value
}
$canonicalFrontendOrigin = "https://custoking-frontend-$Environment-$($replacements['__PROJECT_NUMBER__']).$($replacements['__REGION__']).run.app"
$reviewedFrontendAliases = @{
  'custoking-dev/dev/asia-south2' = 'https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app'
  'custoking-prod/prod/asia-south2' = 'https://custoking-frontend-prod-yter7sugpa-em.a.run.app'
}
$allowedFrontendOrigins = @($canonicalFrontendOrigin)
$reviewedAlias = $reviewedFrontendAliases["$projectId/$Environment/$($replacements['__REGION__'])"]
if ($reviewedAlias) { $allowedFrontendOrigins += $reviewedAlias }
$gatewayOrigins = @((Read-GatewayParameter 'gateway_cors_allowed_origins') -split ',' | ForEach-Object { $_.Trim() })
if ((Read-GatewayParameter 'frontend_url') -cne $canonicalFrontendOrigin -or
    $gatewayOrigins.Count -lt 1 -or $gatewayOrigins.Count -gt $allowedFrontendOrigins.Count -or
    $gatewayOrigins -cnotcontains $canonicalFrontendOrigin -or
    @($gatewayOrigins | Select-Object -Unique).Count -ne $gatewayOrigins.Count -or
    @($gatewayOrigins | Where-Object { $allowedFrontendOrigins -cnotcontains $_ }).Count -gt 0) {
  throw "Gateway CORS origins must contain the exact canonical frontend origin and only its reviewed environment/project/region alias, without duplicates."
}

# Dry-run remains dev-only. Dedicated live email must supply every admission parameter;
# the generic inbox always retains logging/dry-run. Secret payloads never enter target YAML.
# Keep this guard in the governed renderer, before any Cloud Deploy target can be applied.
$platformTargets = @($text -split '(?m)^---\s*$' | Where-Object {
  $_ -match "(?m)^  name:\s*platform-service-$Environment\s*$"
})
if ($platformTargets.Count -ne 1) {
  throw "Exactly one platform-service-$Environment target is required."
}
function Read-PlatformParameter([string]$Name) {
  $matches = [regex]::Matches($platformTargets[0], "(?m)^  $([regex]::Escape($Name)):\s*([^\r\n#]+)")
  if ($matches.Count -ne 1) {
    throw "Platform target must declare exactly one '$Name' parameter."
  }
  return $matches[0].Groups[1].Value.Trim().Trim('"', "'")
}
$broadcastMode = Read-PlatformParameter "broadcast_dispatch_mode"
$broadcastWorkerReady = Read-PlatformParameter "broadcast_worker_ready"
$liveEnabled = Read-PlatformParameter "broadcast_live_enabled"
$broadcastOff = $broadcastMode -ceq "OFF" -and $broadcastWorkerReady -ceq "false" -and $liveEnabled -ceq "false"
$broadcastDevDryRun = $Environment -eq "dev" -and $broadcastMode -ceq "DRY_RUN" -and
  $broadcastWorkerReady -ceq "true" -and $liveEnabled -ceq "false" -and
  (Read-PlatformParameter "notification_delivery_provider") -ceq "logging" -and
  (Read-PlatformParameter "msg91_dry_run") -ceq "true"
$broadcastLive = $false
if ($broadcastMode -ceq "LIVE" -and $liveEnabled -ceq "true" -and $broadcastWorkerReady -ceq "true") {
  $liveSchools = Read-PlatformParameter "broadcast_live_school_ids"
  $liveDestinations = Read-PlatformParameter "broadcast_live_destination_sha256"
  $liveSender = Read-PlatformParameter "broadcast_live_email_sender"
  $liveDomain = Read-PlatformParameter "broadcast_live_email_domain"
  $liveName = Read-PlatformParameter "broadcast_live_email_sender_name"
  $broadcastLive = (Read-PlatformParameter "notification_delivery_provider") -ceq "logging" -and
    (Read-PlatformParameter "msg91_dry_run") -ceq "true" -and
    (Read-PlatformParameter "broadcast_live_sender_verified") -ceq "true" -and
    (Read-PlatformParameter "broadcast_live_email_template_verified") -ceq "true" -and
    (Read-PlatformParameter "broadcast_live_email_template_id") -cmatch '^[A-Za-z0-9_-]{1,150}$' -and
    $liveSchools -cmatch '^[1-9][0-9]{0,17}(,[1-9][0-9]{0,17}){0,99}$' -and
    $liveDestinations -cmatch '^[0-9a-f]{64}(,[0-9a-f]{64}){0,999}$' -and
    $liveDomain -cmatch '^[a-z0-9][a-z0-9.-]*\.[a-z]{2,63}$' -and
    $liveSender -cmatch ('^[^\s@<>]+@' + [regex]::Escape($liveDomain) + '$') -and
    $liveName.Length -ge 1 -and $liveName.Length -le 100 -and $liveName -notmatch '[\x00-\x1f\x7f]'
}
if (-not ($broadcastOff -or $broadcastDevDryRun -or $broadcastLive)) {
  throw "Broadcast parameters must be OFF/false, dev-only DRY_RUN/true, or fully admitted LIVE email with logging and MSG91 dry-run."
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

# Security admission is source-controlled: fixed service roles, audiences and principal carriers.
$securityRoles=@{ 'identity-service'='ims_identity_rt'; 'school-core-service'='ims_school_core_rt'; 'operations-service'='ims_operations_rt'; 'platform-service'='ims_platform_rt'; 'billing-service'='ims_billing_rt' }
$reviewedHashes=@{ 'custoking-dev/dev/asia-south2'='hd4wfwk7mq'; 'custoking-prod/prod/asia-south2'='yter7sugpa' }
$serviceHash=$reviewedHashes["$projectId/$Environment/$($replacements['__REGION__'])"]
if(-not $serviceHash){throw 'Service authentication requires a reviewed project/environment/region alias.'}
foreach($service in $securityRoles.Keys){
 $blocks=@($text -split '(?m)^---\s*$' | Where-Object {$_ -match "(?m)^  name:\s*$service-$Environment\s*$"})
 if($blocks.Count -ne 1){throw "Exactly one $service security target is required."}
 $expected=@{
  runtime_db_role=$securityRoles[$service]
  runtime_db_password_secret=($service.Replace('-service','')+"-runtime-db-password-$Environment")
  service_oidc_audiences="https://custoking-$service-$Environment-$($replacements['__PROJECT_NUMBER__']).$($replacements['__REGION__']).run.app,https://custoking-$service-$Environment-$serviceHash-em.a.run.app"
  user_context_caller_service_accounts="ims-api-gateway-$Environment@$projectId.iam.gserviceaccount.com"
 }
 if($service -eq 'school-core-service'){$expected.user_context_caller_service_accounts+=",ims-identity-$Environment@$projectId.iam.gserviceaccount.com"}
 foreach($key in $expected.Keys){
  $declarations=[regex]::Matches($blocks[0],"(?m)^  $([regex]::Escape($key)):[ \t]*([^\r\n]*)")
  if($declarations.Count -ne 1 -or $declarations[0].Groups[1].Value.Trim().Trim('"',"'") -cne $expected[$key]){throw "Unreviewed $service security target parameter: $key."}
 }
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

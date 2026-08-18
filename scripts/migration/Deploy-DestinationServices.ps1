<#
.SYNOPSIS
  Deploys the seven Cloud Run services into a destination Custoking project by transforming the captured
  live source definitions.

.DESCRIPTION
  Deliberately transforms the CAPTURED source YAML rather than hand-authoring env vars. The source
  services carry 40+ environment variables each; re-typing them is how a migration quietly loses a
  setting. Capture (Invoke-DestinationBuild.ps1 -Stage 90) is the specification.

  Substitutions applied:
    image registry project      asia-south2-docker.pkg.dev/<source>/  -> /<target>/
    runtime service accounts    @<source>.iam                          -> @<target>.iam
    service URLs                <svc>-<hash>-em.a.run.app              -> <svc>-<targetNumber>.<region>.run.app
    database host               source private IP                      -> destination private IP
    photo bucket                source bucket                          -> destination bucket
    project id env vars         GCP_PROJECT / GOOGLE_CLOUD_* / *_PUBSUB_PROJECT_ID / OTEL attrs
    GATEWAY_CORS_ALLOWED_ORIGINS  -> destination frontend URL

  GATEWAY_CORS_ALLOWED_ORIGINS is easy to miss: api-gateway is a Node service, so this setting is not in
  any Spring application.yml. The frontend's nginx proxies /api/v1 server-side but forwards the browser's
  Origin header, so a stale value here fails every API call in the browser after cutover.

  Cloud Deploy ownership metadata is stripped. These services are created directly, so leaving
  managed-by/delivery-pipeline-id/release-id labels would misrepresent them as Cloud Deploy-managed.

.EXAMPLE
  ./Deploy-DestinationServices.ps1 -Environment dev -WhatIf
  ./Deploy-DestinationServices.ps1 -Environment dev
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet('dev', 'prod')][string]$Environment,
  [string]$SourceProject = 'custoking',
  [string]$TargetProject,
  [string]$Region = 'asia-south2',
  [string]$SourceDbHost,
  [string]$TargetDbHost,
  [string]$SourcePhotoBucket,
  [string]$TargetPhotoBucket,
  [string]$CaptureDirectory,
  [string[]]$Service,
  # Artifact Registry repository id. Kept identical between projects, so a valid image reference legally
  # contains the literal word "custoking" as the repository segment.
  [string]$ArtifactRepository = 'custoking',
  # Withhold public access. Granting allUsers is the cutover switch itself, so a staged build must deploy
  # the services reachable only by their own identities and flip traffic later. Without this the
  # destination is publicly serving migrated data while the source is still live, which is exactly the
  # split-brain the invoker-IAM switch exists to prevent.
  [switch]$NoPublicAccess,
  [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
if (-not $TargetProject) { $TargetProject = "custoking-$Environment" }
if (-not $CaptureDirectory) { $CaptureDirectory = Join-Path $PSScriptRoot "capture/$Environment" }
if (-not $SourcePhotoBucket) { $SourcePhotoBucket = "custoking-student-photos-$Environment" }
if (-not $TargetPhotoBucket) { $TargetPhotoBucket = "custoking-$Environment-student-photos" }

$AllServices = @('identity-service', 'school-core-service', 'operations-service',
  'platform-service', 'billing-service', 'api-gateway', 'frontend')
if (-not $Service) { $Service = $AllServices }

function Invoke-Native {
  param([string[]]$Arguments, [switch]$AllowFailure)
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { $output = & gcloud @Arguments; $code = $LASTEXITCODE }
  finally { $ErrorActionPreference = $previous }
  if ($code -ne 0 -and -not $AllowFailure) { throw "gcloud $($Arguments -join ' ') exited $code" }
  return ($output | Out-String).Trim()
}

$TargetNumber = Invoke-Native @('projects', 'describe', $TargetProject, '--format=value(projectNumber)')
if (-not $SourceDbHost) {
  $SourceDbHost = Invoke-Native @('sql', 'instances', 'describe', "custoking-db-$Environment",
    "--project=$SourceProject", '--format=value(ipAddresses[0].ipAddress)')
}
if (-not $TargetDbHost) {
  $TargetDbHost = Invoke-Native @('sql', 'instances', 'describe', "custoking-db-$Environment",
    "--project=$TargetProject", '--format=value(ipAddresses[0].ipAddress)')
}

Write-Host "target project  : $TargetProject ($TargetNumber)"
Write-Host "database host   : $SourceDbHost -> $TargetDbHost"
Write-Host "photo bucket    : $SourcePhotoBucket -> $TargetPhotoBucket"

function Get-TargetUrl([string]$ServiceName) {
  # The deterministic Cloud Run hostname. Available alongside the legacy hashed form, and computable
  # before anything is deployed, which is what breaks the bootstrap deadlock: the gateway needs 13
  # upstream URLs and the frontend needs one, all at deploy time.
  return "https://custoking-$ServiceName-$Environment-$TargetNumber.$Region.run.app"
}

$rendered = Join-Path $CaptureDirectory 'rendered'
New-Item -ItemType Directory -Force -Path $rendered | Out-Null

foreach ($svc in $Service) {
  $source = Join-Path $CaptureDirectory "$svc.yaml"
  if (-not (Test-Path $source)) { throw "Missing capture for $svc. Run Invoke-DestinationBuild.ps1 -Stage 90 first." }
  Write-Host "`n=== $svc ===" -ForegroundColor Cyan
  $text = Get-Content -Raw -Path $source

  # 1. Image registry project.
  $text = $text.Replace("$Region-docker.pkg.dev/$SourceProject/", "$Region-docker.pkg.dev/$TargetProject/")
  # 2. Every service account reference.
  $text = $text.Replace("@$SourceProject.iam.gserviceaccount.com", "@$TargetProject.iam.gserviceaccount.com")
  # 3. Service URLs, legacy hashed host -> deterministic host.
  foreach ($other in $AllServices) {
    $pattern = "https://custoking-$other-$Environment-[a-z0-9]+-[a-z]{2}\.a\.run\.app"
    $text = [regex]::Replace($text, $pattern, (Get-TargetUrl $other))
  }
  # 4. Database host.
  if ($SourceDbHost -and $TargetDbHost) { $text = $text.Replace($SourceDbHost, $TargetDbHost) }
  # 5. Photo bucket.
  $text = $text.Replace($SourcePhotoBucket, $TargetPhotoBucket)
  # 6. Bare project-id values (GCP_PROJECT, GOOGLE_CLOUD_PROJECT, *_OUTBOX_PUBSUB_PROJECT_ID) and the
  #    OTEL attribute string. Anchored so that resource NAMES beginning "custoking-" are left alone.
  $text = [regex]::Replace($text, "(?m)^(\s*)value:\s*$([regex]::Escape($SourceProject))\s*$", "`$1value: $TargetProject")
  $text = $text.Replace("gcp.project_id=$SourceProject", "gcp.project_id=$TargetProject")

  # 7. Strip Cloud Deploy ownership and server-populated metadata.
  $lines = $text -split "`r?`n"
  $keep = New-Object System.Collections.Generic.List[string]
  foreach ($line in $lines) {
    if ($line -match '^\s*(managed-by|delivery-pipeline-id|release-id|target-id|project-id|location):\s') { continue }
    if ($line -match '^\s*(resourceVersion|selfLink|uid|generation|creationTimestamp|observedGeneration):\s') { continue }
    if ($line -match '^\s*run\.googleapis\.com/(operation-id|urls|ingress-status):\s') { continue }
    if ($line -match '^\s*serving\.knative\.dev/(creator|lastModifier):\s') { continue }
    # metadata.namespace carries the SOURCE project number. Cloud Run rejects the apply outright with
    # "Namespace must be project ID or quoted number", so rewrite rather than drop it.
    if ($line -match '^(\s*)namespace:\s') { $keep.Add("$($Matches[1])namespace: '$TargetProject'"); continue }
    # spec.template.metadata.name pins the REVISION name. Carried over from the capture it means Cloud Run
    # will not mint a fresh revision, so a failed first attempt can never be superseded -- every retry
    # collides with the same dead revision. Drop it and let Cloud Run generate the name.
    if ($line -match '^\s{6}name:\s+custoking-[a-z-]+-(dev|prod)-[a-z0-9]+\s*$') { continue }
    # The traffic block pins revisionName to a SOURCE revision that does not exist in the destination, so
    # routing fails with "Revision ... does not exist or is deleted" even though the container started
    # healthily. Production carries this and dev does not: prod is deployed by Cloud Deploy, which pins
    # revisions explicitly, while dev deploys directly and uses latestRevision. Route to the latest
    # revision instead.
    if ($line -match '^(\s*)revisionName:\s+custoking-[a-z-]+-(dev|prod)-[a-z0-9]+\s*$') {
      $keep.Add("$($Matches[1])latestRevision: true"); continue
    }
    $keep.Add($line)
  }
  $text = ($keep -join "`n")

  # Residual check: nothing should still point at the source PROJECT. Two legitimate uses of the bare
  # word survive on purpose and must not trip this:
  #   - the Artifact Registry repository is itself named "custoking", so a correct image reference reads
  #     <region>-docker.pkg.dev/<target-project>/custoking/custoking-<svc>@sha256:...
  #   - hyphenated names (custoking-ims, custoking-<svc>-<env>) are resource names, not project ids,
  #     and are excluded by the word-boundary lookarounds.
  $scannable = $text.Replace("$Region-docker.pkg.dev/$TargetProject/$ArtifactRepository/", 'ARTIFACT_REGISTRY_PATH/')
  $residual = @($scannable -split "`r?`n" | Where-Object { $_ -match "(?<![-\w])$([regex]::Escape($SourceProject))(?![-\w])" })
  if ($residual.Count -gt 0) {
    Write-Host '  residual source-project references found:' -ForegroundColor Red
    $residual | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
    throw "Refusing to deploy $svc with unresolved references to $SourceProject."
  }

  $target = Join-Path $rendered "$svc.yaml"
  $text | Set-Content -Path $target -Encoding utf8
  Write-Host "  rendered -> $target"

  if ($WhatIf) { Write-Host '  WHATIF (not applied)' -ForegroundColor Yellow; continue }
  Invoke-Native @('run', 'services', 'replace', $target, "--region=$Region", "--project=$TargetProject") | Out-Null
  Write-Host '  deployed' -ForegroundColor Green
}

if (-not $WhatIf) {
  # Service-to-service invoker IAM. Without this the five private backends reject the gateway's OIDC
  # token and every API call returns a bare Cloud Run 403 ("Your client does not have permission"),
  # even though each service is individually healthy. Mirrored from the live source bindings.
  Write-Host "`n=== service-to-service invoker IAM ===" -ForegroundColor Cyan
  $callers = @{
    'identity-service'    = @('api-gateway')
    'school-core-service' = @('api-gateway', 'identity', 'operations', 'platform')
    'operations-service'  = @('api-gateway', 'platform')
    'platform-service'    = @('api-gateway')
    'billing-service'     = @('api-gateway')
  }
  # Runtime account short names differ from service names for the merged services.
  $accountFor = @{
    'api-gateway' = "ims-api-gateway-$Environment"; 'identity' = "ims-identity-$Environment"
    'operations'  = "ims-operations-$Environment";  'platform' = "ims-platform-$Environment"
  }
  foreach ($target in $callers.Keys) {
    if ($Service -notcontains $target) { continue }
    foreach ($caller in $callers[$target]) {
      Invoke-Native @('run', 'services', 'add-iam-policy-binding', "custoking-$target-$Environment",
        "--member=serviceAccount:$($accountFor[$caller])@$TargetProject.iam.gserviceaccount.com",
        '--role=roles/run.invoker', "--region=$Region", "--project=$TargetProject") | Out-Null
    }
    Write-Host "  custoking-$target-$Environment <- $($callers[$target] -join ', ')"
  }

  # Pub/Sub push identities invoke platform-service directly, not through the gateway.
  if ($Service -contains 'platform-service') {
    foreach ($push in @("ims-reporting-push-$Environment", "ims-notification-push-$Environment")) {
      Invoke-Native @('run', 'services', 'add-iam-policy-binding', "custoking-platform-service-$Environment",
        "--member=serviceAccount:$push@$TargetProject.iam.gserviceaccount.com",
        '--role=roles/run.invoker', "--region=$Region", "--project=$TargetProject") | Out-Null
    }
    Write-Host "  custoking-platform-service-$Environment <- reporting-push, notification-push"
  }

  # The authenticated uptime-check identity is per-project: its name embeds the project number, so the
  # source grant does not carry over and checks would fail silently.
  $monitoringAgent = "service-$TargetNumber@gcp-sa-monitoring-notification.iam.gserviceaccount.com"
  foreach ($svc in $Service) {
    if ($svc -eq 'frontend') { continue }
    Invoke-Native @('run', 'services', 'add-iam-policy-binding', "custoking-$svc-$Environment",
      "--member=serviceAccount:$monitoringAgent", '--role=roles/run.invoker',
      "--region=$Region", "--project=$TargetProject") -AllowFailure | Out-Null
  }
  Write-Host '  monitoring uptime-check agent granted on backends'

  if ($NoPublicAccess) {
    Write-Host "`n=== public access WITHHELD (-NoPublicAccess) ===" -ForegroundColor Yellow
    Write-Host '  frontend and gateway are deployed but NOT reachable. Grant allUsers at cutover.'
    return
  }
  Write-Host "`n=== public access ===" -ForegroundColor Cyan
  # Only the frontend and the gateway are public; the five backends stay private and are reachable only
  # by the named runtime identities. This mirrors the source exactly.
  foreach ($public in @('frontend', 'api-gateway')) {
    if ($Service -notcontains $public) { continue }
    Invoke-Native @('run', 'services', 'add-iam-policy-binding', "custoking-$public-$Environment",
      '--member=allUsers', '--role=roles/run.invoker', "--region=$Region", "--project=$TargetProject") | Out-Null
    Write-Host "  $public is public: $(Get-TargetUrl $public)"
  }
}

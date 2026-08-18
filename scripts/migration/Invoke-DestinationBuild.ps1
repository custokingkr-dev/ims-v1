<#
.SYNOPSIS
  Builds a destination Custoking project (custoking-dev or custoking-prod) from the live source project.

.DESCRIPTION
  Idempotent, staged build. Every stage is safe to re-run. Nothing in this script deletes or mutates the
  SOURCE project except reads, with one exception clearly marked in Stage 90 (starting the dev Cloud SQL
  instance so its data can be exported).

  Run stages in order. Stop and inspect on any failure: a partially built stage is safe to re-run, but a
  skipped stage will surface later as a confusing runtime failure.

  Stages:
     10  APIs
     20  Network (VPC, subnet, Private Service Access range + peering)
     30  Cloud SQL instance, database, appuser
     40  Buckets
     50  Runtime + push service accounts
     60  Secrets (names + per-secret IAM only; values are transferred separately)
     70  Artifact Registry + copy the live image digests
     80  Pub/Sub topics, DLQs, subscriptions
     90  (source read) capture the source service definitions used by Deploy-DestinationServices.ps1

.EXAMPLE
  ./Invoke-DestinationBuild.ps1 -Environment dev -Stage 10,20,30
  ./Invoke-DestinationBuild.ps1 -Environment dev            # all stages
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet('dev', 'prod')][string]$Environment,
  [string]$SourceProject = 'custoking',
  [string]$Region = 'asia-south2',
  [int[]]$Stage,
  [string]$PhotoBucket,
  [string]$CaptureDirectory,
  # Tag applied to copied images. Deployment resolves by digest, so this is only a human-readable handle
  # that keeps the copied manifest from being untagged and swept by a cleanup policy.
  [string]$MigrationTag = 'migrate-tmp',
  [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$TargetProject = "custoking-$Environment"
if (-not $PhotoBucket) { $PhotoBucket = "custoking-$Environment-student-photos" }
if (-not $CaptureDirectory) { $CaptureDirectory = Join-Path $PSScriptRoot "capture/$Environment" }

$Services = @(
  'identity-service', 'school-core-service', 'operations-service',
  'platform-service', 'billing-service', 'api-gateway', 'frontend'
)

# Runtime identity per service. Deliberately mirrors the source names so that hardcoded references in
# rollback.yml and build-release.yml keep resolving after the migration.
$RuntimeAccounts = @{
  'identity-service'    = "ims-identity-$Environment"
  'school-core-service' = "ims-school-core-$Environment"
  'operations-service'  = "ims-operations-$Environment"
  'platform-service'    = "ims-platform-$Environment"
  'billing-service'     = "ims-billing-$Environment"
  'api-gateway'         = "ims-api-gateway-$Environment"
  'frontend'            = "ims-frontend-$Environment"
}

function Write-Stage([string]$Name) { Write-Host "`n=== $Name ===" -ForegroundColor Cyan }
function Write-Ok([string]$Message) { Write-Host "  ok   $Message" -ForegroundColor Green }
function Write-Skip([string]$Message) { Write-Host "  --   $Message (already present)" -ForegroundColor DarkGray }

# Windows PowerShell 5.1 wraps a native command's redirected stderr in an ErrorRecord and sets $? to
# false even when the executable exited 0. gcloud writes progress to stderr constantly, so never redirect
# it here: drop $ErrorActionPreference to Continue around the call and judge success by $LASTEXITCODE.
function Invoke-Gcloud {
  param([string[]]$Arguments, [switch]$AllowFailure)
  if ($WhatIf) { Write-Host "  WHATIF gcloud $($Arguments -join ' ')" -ForegroundColor Yellow; return '' }
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $output = & gcloud @Arguments
    $code = $LASTEXITCODE
  }
  finally { $ErrorActionPreference = $previous }
  if ($code -ne 0 -and -not $AllowFailure) {
    throw "gcloud $($Arguments -join ' ') exited $code"
  }
  return ($output | Out-String).Trim()
}

function Test-Exists {
  param([string[]]$Arguments)
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    & gcloud @Arguments --verbosity=none | Out-Null
    $code = $LASTEXITCODE
  }
  finally { $ErrorActionPreference = $previous }
  return $code -eq 0
}

function Should-Run([int]$Number) { return (-not $Stage) -or ($Stage -contains $Number) }

# gcloud output arrives through Out-String, which joins with CRLF. Splitting on "`n" alone leaves a
# trailing carriage return on every line, so anchored matches like "-dev$" silently never fire.
function Split-Lines([string]$Text) {
  if (-not $Text) { return @() }
  return ($Text -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

Write-Host "Source : $SourceProject"
Write-Host "Target : $TargetProject ($Environment) in $Region"
Write-Host "Bucket : $PhotoBucket"

$TargetProjectNumber = Invoke-Gcloud @('projects', 'describe', $TargetProject, '--format=value(projectNumber)')
if (-not $TargetProjectNumber) { throw "Could not resolve the project number for $TargetProject." }
Write-Host "Number : $TargetProjectNumber"

# Guard: refuse to build into the source project.
if ($TargetProject -eq $SourceProject) { throw 'Target and source project must differ.' }

# ---------------------------------------------------------------- 10 APIs
if (Should-Run 10) {
  Write-Stage '10 Enable APIs'
  # Exactly the set enabled on the source and missing from a fresh project, minus those confirmed unused:
  # cloudscheduler (no jobs exist anywhere), vpcaccess (Direct VPC egress is used, no connectors exist),
  # deploymentmanager / oslogin / privilegedaccessmanager / recommender (project defaults, no dependency).
  $apis = @(
    'artifactregistry.googleapis.com', 'clouddeploy.googleapis.com', 'cloudbuild.googleapis.com',
    'cloudresourcemanager.googleapis.com', 'compute.googleapis.com', 'drive.googleapis.com',
    'iam.googleapis.com', 'iamcredentials.googleapis.com', 'logging.googleapis.com',
    'monitoring.googleapis.com', 'pubsub.googleapis.com', 'run.googleapis.com',
    'secretmanager.googleapis.com', 'servicenetworking.googleapis.com', 'sqladmin.googleapis.com',
    'storage.googleapis.com', 'sts.googleapis.com', 'cloudtrace.googleapis.com',
    'telemetry.googleapis.com'
  )
  Invoke-Gcloud (@('services', 'enable') + $apis + @("--project=$TargetProject"))
  Write-Ok "$($apis.Count) APIs enabled"
}

# ---------------------------------------------------------------- 20 Network
if (Should-Run 20) {
  Write-Stage '20 Network and Private Service Access'
  # Enabling compute.googleapis.com auto-creates the default VPC unless org policy forbids it. The
  # destination organization does not set constraints/compute.skipDefaultNetworkCreation, so 'default'
  # is expected to exist. Create it explicitly if it does not.
  if (Test-Exists @('compute', 'networks', 'describe', 'default', "--project=$TargetProject")) {
    Write-Skip 'VPC default'
  }
  else {
    Invoke-Gcloud @('compute', 'networks', 'create', 'default', '--subnet-mode=auto', "--project=$TargetProject")
    Write-Ok 'VPC default created'
  }

  # Private Service Access range for Cloud SQL. The source uses 10.92.0.0/16; reusing it is safe because
  # the projects do not peer with each other.
  if (Test-Exists @('compute', 'addresses', 'describe', 'google-managed-services-default', '--global', "--project=$TargetProject")) {
    Write-Skip 'PSA range'
  }
  else {
    Invoke-Gcloud @('compute', 'addresses', 'create', 'google-managed-services-default',
      '--global', '--purpose=VPC_PEERING', '--prefix-length=16', '--addresses=10.92.0.0',
      '--network=default', "--project=$TargetProject")
    Write-Ok 'PSA range 10.92.0.0/16 reserved'
  }

  $peering = Invoke-Gcloud @('services', 'vpc-peerings', 'list', '--network=default',
    "--project=$TargetProject", '--format=value(peering)') -AllowFailure
  if ($peering -match 'servicenetworking') {
    Write-Skip 'servicenetworking peering'
  }
  else {
    Invoke-Gcloud @('services', 'vpc-peerings', 'connect',
      '--service=servicenetworking.googleapis.com',
      '--ranges=google-managed-services-default', '--network=default', "--project=$TargetProject")
    Write-Ok 'servicenetworking peering established'
  }
}

# ---------------------------------------------------------------- 30 Cloud SQL
if (Should-Run 30) {
  Write-Stage '30 Cloud SQL'
  $instance = "custoking-db-$Environment"
  $database = "custoking_$Environment"

  if (Test-Exists @('sql', 'instances', 'describe', $instance, "--project=$TargetProject")) {
    Write-Skip "instance $instance"
  }
  else {
    # Mirrors the measured source configuration. Dev stays on the cheapest adequate tier; prod matches
    # db-g1-small with backups, PITR and deletion protection. Creation takes roughly ten minutes.
    # --edition=ENTERPRISE is REQUIRED and not optional. New instances now default to ENTERPRISE_PLUS,
    # which rejects shared-core tiers outright ("Invalid Tier (db-f1-micro) for (ENTERPRISE_PLUS)
    # Edition"). Both source instances are ENTERPRISE, so the destination must match or the tier — and
    # therefore the cost profile — silently changes.
    $tier = if ($Environment -eq 'prod') { 'db-g1-small' } else { 'db-f1-micro' }
    $sqlArgs = @('sql', 'instances', 'create', $instance,
      '--database-version=POSTGRES_16', '--edition=ENTERPRISE', "--tier=$tier", "--region=$Region",
      '--storage-type=SSD', '--storage-size=10', '--storage-auto-increase',
      '--availability-type=zonal', '--network=default', '--no-assign-ip',
      '--database-flags=max_connections=200',
      '--deletion-protection', "--project=$TargetProject")
    if ($Environment -eq 'prod') {
      $sqlArgs += @('--backup', '--backup-start-time=20:30', '--enable-point-in-time-recovery',
        '--retained-backups-count=14', '--retained-transaction-log-days=7')
    }
    else {
      $sqlArgs += @('--no-backup')
    }
    Invoke-Gcloud $sqlArgs
    Write-Ok "instance $instance created"
  }

  if (Test-Exists @('sql', 'databases', 'describe', $database, "--instance=$instance", "--project=$TargetProject")) {
    Write-Skip "database $database"
  }
  else {
    Invoke-Gcloud @('sql', 'databases', 'create', $database, "--instance=$instance", "--project=$TargetProject")
    Write-Ok "database $database created"
  }

  # appuser is the Flyway/owner role. Its password MUST equal the value of db-password-<env>, which is
  # transferred separately. app_rt is NOT created here: it is created by scripts/create-app-rt-role.sql
  # after the data import, because pg_dump does not carry instance-level roles.
  Write-Host '  note appuser and app_rt are created by Copy-MigrationSecrets.ps1 / the app_rt job.' -ForegroundColor Yellow

  $ip = Invoke-Gcloud @('sql', 'instances', 'describe', $instance, "--project=$TargetProject",
    '--format=value(ipAddresses[0].ipAddress)')
  Write-Ok "private IP: $ip   <-- SPRING_DATASOURCE_URL / DB_HOST"
}

# ---------------------------------------------------------------- 40 Buckets
if (Should-Run 40) {
  Write-Stage '40 Buckets'
  # Bucket names are globally unique, so the source names cannot be reused while the source still holds
  # them. Uniform bucket-level access is enforced by destination org policy anyway.
  if (Test-Exists @('storage', 'buckets', 'describe', "gs://$PhotoBucket", "--project=$TargetProject")) {
    Write-Skip "bucket $PhotoBucket"
  }
  else {
    Invoke-Gcloud @('storage', 'buckets', 'create', "gs://$PhotoBucket",
      "--location=$Region", '--uniform-bucket-level-access', '--public-access-prevention',
      "--project=$TargetProject")
    Write-Ok "bucket $PhotoBucket created"
  }

  # Lifecycle: expire transient photo-import staging after 14 days, matching the source.
  $lifecycle = Join-Path $env:TEMP "ck-lifecycle-$Environment.json"
  @'
{"lifecycle":{"rule":[{"action":{"type":"Delete"},"condition":{"age":14,"matchesPrefix":["temporary/photo-imports/"]}}]}}
'@ | Set-Content -Path $lifecycle -Encoding utf8
  Invoke-Gcloud @('storage', 'buckets', 'update', "gs://$PhotoBucket", "--lifecycle-file=$lifecycle", "--project=$TargetProject")
  Write-Ok 'lifecycle rule applied'
}

# ---------------------------------------------------------------- 50 Service accounts
if (Should-Run 50) {
  Write-Stage '50 Runtime and push service accounts'
  $accounts = @()
  foreach ($svc in $Services) { $accounts += $RuntimeAccounts[$svc] }
  $accounts += "ims-reporting-push-$Environment"
  $accounts += "ims-notification-push-$Environment"   # absent in the source prod project; created here deliberately

  # Service-account creation is rate limited per project per minute and returns 429 well before nine
  # accounts are made. Retry with backoff rather than failing the stage half-built.
  foreach ($account in $accounts) {
    $email = "$account@$TargetProject.iam.gserviceaccount.com"
    if (Test-Exists @('iam', 'service-accounts', 'describe', $email, "--project=$TargetProject")) {
      Write-Skip $account
      continue
    }
    for ($attempt = 1; $attempt -le 6; $attempt++) {
      $created = $null
      try {
        Invoke-Gcloud @('iam', 'service-accounts', 'create', $account,
          "--display-name=IMS $account", "--project=$TargetProject") | Out-Null
        $created = $true
      }
      catch {
        if ($attempt -eq 6) { throw }
        Write-Host "  ..   $account rate limited; waiting 65s (attempt $attempt/6)" -ForegroundColor DarkYellow
        Start-Sleep -Seconds 65
      }
      if ($created) { Write-Ok $account; break }
    }
  }

  # Least-privilege project roles, mirroring the source runtime grants.
  foreach ($svc in $Services) {
    $email = "serviceAccount:$($RuntimeAccounts[$svc])@$TargetProject.iam.gserviceaccount.com"
    foreach ($role in @('roles/cloudsql.client', 'roles/cloudtrace.agent', 'roles/serviceusage.serviceUsageConsumer')) {
      Invoke-Gcloud @('projects', 'add-iam-policy-binding', $TargetProject,
        "--member=$email", "--role=$role", '--condition=None', '--quiet') | Out-Null
    }
  }
  Write-Ok 'runtime project roles bound'

  # school-core writes and signs student photo objects. signBlob is required because Cloud Run service
  # accounts have no local private key for signed URLs.
  $schoolCore = "serviceAccount:$($RuntimeAccounts['school-core-service'])@$TargetProject.iam.gserviceaccount.com"
  Invoke-Gcloud @('storage', 'buckets', 'add-iam-policy-binding', "gs://$PhotoBucket",
    "--member=$schoolCore", '--role=roles/storage.objectAdmin', "--project=$TargetProject") | Out-Null
  Invoke-Gcloud @('iam', 'service-accounts', 'add-iam-policy-binding',
    "$($RuntimeAccounts['school-core-service'])@$TargetProject.iam.gserviceaccount.com",
    "--member=$schoolCore", '--role=roles/iam.serviceAccountTokenCreator', "--project=$TargetProject") | Out-Null
  Write-Ok 'school-core photo storage + signBlob bound'
}

# ---------------------------------------------------------------- 60 Secret shells
if (Should-Run 60) {
  Write-Stage '60 Secret containers and per-secret IAM'
  # Names only. Values are transferred by Copy-MigrationSecrets.ps1 so that no secret material passes
  # through this script, its logs, or shell history.
  $sourceSecrets = @(Split-Lines (Invoke-Gcloud @('secrets', 'list', "--project=$SourceProject", '--format=value(name)')) |
      Where-Object { $_ -match "-$Environment$" -or $_ -match '-sql$' })
  if ($sourceSecrets.Count -eq 0) { throw 'Resolved zero source secrets. Refusing to continue: the filter or the listing is wrong.' }

  foreach ($secret in $sourceSecrets) {
    if (Test-Exists @('secrets', 'describe', $secret, "--project=$TargetProject")) { Write-Skip $secret }
    else {
      Invoke-Gcloud @('secrets', 'create', $secret, '--replication-policy=automatic', "--project=$TargetProject")
      Write-Ok $secret
    }
  }
  Write-Host "  note $($sourceSecrets.Count) secret containers exist. Values are still EMPTY." -ForegroundColor Yellow

  # Mirror the source's per-secret accessor grants rather than granting project-wide.
  foreach ($secret in $sourceSecrets) {
    $members = Split-Lines (Invoke-Gcloud @('secrets', 'get-iam-policy', $secret, "--project=$SourceProject",
        '--flatten=bindings[].members', '--format=value(bindings.members)') -AllowFailure)
    foreach ($member in $members) {
      if ($member -notmatch '^serviceAccount:(ims-[a-z-]+)@') { continue }
      $target = "serviceAccount:$($Matches[1])@$TargetProject.iam.gserviceaccount.com"
      Invoke-Gcloud @('secrets', 'add-iam-policy-binding', $secret,
        "--member=$target", '--role=roles/secretmanager.secretAccessor', "--project=$TargetProject", '--quiet') | Out-Null
    }
  }
  Write-Ok 'per-secret accessor grants mirrored'
}

# ---------------------------------------------------------------- 70 Artifact Registry
if (Should-Run 70) {
  Write-Stage '70 Artifact Registry and image digests'
  if (Test-Exists @('artifacts', 'repositories', 'describe', 'custoking', "--location=$Region", "--project=$TargetProject")) {
    Write-Skip 'repository custoking'
  }
  else {
    Invoke-Gcloud @('artifacts', 'repositories', 'create', 'custoking',
      '--repository-format=docker', "--location=$Region",
      '--description=Custoking immutable release images', "--project=$TargetProject")
    Write-Ok 'repository custoking created'
  }

  # Copy only the digests that are actually deployed. The source repository holds 244 images / ~12 GB;
  # copying it wholesale would be pure waste and, if routed outside asia-south2, billed egress.
  $sourceRegistry = "$Region-docker.pkg.dev/$SourceProject/custoking"
  $targetRegistry = "$Region-docker.pkg.dev/$TargetProject/custoking"
  Invoke-Gcloud @('auth', 'configure-docker', "$Region-docker.pkg.dev", '--quiet') | Out-Null

  foreach ($svc in $Services) {
    $image = "custoking-$svc"
    $digest = Invoke-Gcloud @('run', 'services', 'describe', "custoking-$svc-$Environment",
      "--project=$SourceProject", "--region=$Region",
      '--format=value(spec.template.spec.containers[0].image)')
    if ($digest -notmatch '@(sha256:[0-9a-f]{64})$') { throw "Could not resolve a digest for $svc." }
    $sha = $Matches[1]

    if (Test-Exists @('artifacts', 'docker', 'images', 'describe', "$targetRegistry/$image@$sha", "--project=$TargetProject")) {
      Write-Skip "$image@$($sha.Substring(0,19))"
      continue
    }
    # docker buildx imagetools create performs a registry-to-registry copy and preserves the digest.
    # Verified: ~5-15s per image with no pull to the workstation, because source and destination share
    # the asia-south2-docker.pkg.dev host so blobs are copied server-side. That matters for more than
    # speed: pulling images to a workstation or CI runner is billed internet egress, which is the single
    # largest Artifact Registry cost line on this project.
    # Do NOT substitute docker pull/tag/push: it resolves one platform out of the OCI index and rewrites
    # the digest, which breaks the digest equality build-release.yml depends on.
    if ($WhatIf) { Write-Host "  WHATIF docker buildx imagetools create --tag $targetRegistry/$image`:$MigrationTag $sourceRegistry/$image@$sha" -ForegroundColor Yellow }
    else {
      & docker buildx imagetools create --tag "$targetRegistry/${image}:$MigrationTag" "$sourceRegistry/$image@$sha"
      if ($LASTEXITCODE -ne 0) { throw "Image copy failed for $image@$sha." }
      $copied = Invoke-Gcloud @('artifacts', 'docker', 'images', 'describe',
        "$targetRegistry/${image}:$MigrationTag", "--project=$TargetProject", '--format=value(image_summary.digest)')
      if ($copied -ne $sha) { throw "Digest changed during copy for $image. Source $sha, destination $copied." }
      Write-Ok "$image@$($sha.Substring(0,19)) copied, digest verified"
    }
  }
}

# ---------------------------------------------------------------- 80 Pub/Sub
if (Should-Run 80) {
  Write-Stage '80 Pub/Sub topics, dead-letter topics and subscriptions'
  $platformUrl = "https://custoking-platform-service-$Environment-$TargetProjectNumber.$Region.run.app"

  $flows = @(
    @{ Name = 'reporting'; Topic = "ims-reporting-events-v1-$Environment"; Dlq = "ims-reporting-dead-letter-v1-$Environment";
       Sub = "ims-reporting-service-push-$Environment"; Path = '/api/v1/pubsub/reporting-events'; Push = "ims-reporting-push-$Environment" },
    @{ Name = 'notifications'; Topic = "ims-notifications-events-v1-$Environment"; Dlq = "ims-notifications-dead-letter-v1-$Environment";
       Sub = "ims-notification-service-push-$Environment"; Path = '/api/v1/pubsub/notifications'; Push = "ims-notification-push-$Environment" }
  )

  foreach ($flow in $flows) {
    foreach ($topic in @($flow.Topic, $flow.Dlq)) {
      if (Test-Exists @('pubsub', 'topics', 'describe', $topic, "--project=$TargetProject")) { Write-Skip "topic $topic" }
      else { Invoke-Gcloud @('pubsub', 'topics', 'create', $topic, "--project=$TargetProject"); Write-Ok "topic $topic" }
    }

    $pushAccount = "$($flow.Push)@$TargetProject.iam.gserviceaccount.com"
    if (Test-Exists @('pubsub', 'subscriptions', 'describe', $flow.Sub, "--project=$TargetProject")) {
      Write-Skip "subscription $($flow.Sub)"
    }
    else {
      # The push audience must be the DESTINATION Cloud Run URL. Using the source URL here is the classic
      # way to leave events silently flowing back into the old project.
      Invoke-Gcloud @('pubsub', 'subscriptions', 'create', $flow.Sub,
        "--topic=$($flow.Topic)", "--push-endpoint=$platformUrl$($flow.Path)",
        "--push-auth-service-account=$pushAccount", "--push-auth-token-audience=$platformUrl",
        "--dead-letter-topic=$($flow.Dlq)", '--max-delivery-attempts=5',
        '--ack-deadline=60', '--message-retention-duration=7d', "--project=$TargetProject")
      Write-Ok "subscription $($flow.Sub) -> $platformUrl$($flow.Path)"
    }

    $inspect = "$($flow.Name)-dead-letter-inspection-$Environment"
    if (-not (Test-Exists @('pubsub', 'subscriptions', 'describe', $inspect, "--project=$TargetProject"))) {
      Invoke-Gcloud @('pubsub', 'subscriptions', 'create', $inspect, "--topic=$($flow.Dlq)", "--project=$TargetProject")
      Write-Ok "subscription $inspect"
    }

    # Pub/Sub's own service agent needs publish rights on the DLQ and subscribe on the source subscription.
    $agent = "serviceAccount:service-$TargetProjectNumber@gcp-sa-pubsub.iam.gserviceaccount.com"
    Invoke-Gcloud @('pubsub', 'topics', 'add-iam-policy-binding', $flow.Dlq,
      "--member=$agent", '--role=roles/pubsub.publisher', "--project=$TargetProject", '--quiet') | Out-Null
    Invoke-Gcloud @('pubsub', 'subscriptions', 'add-iam-policy-binding', $flow.Sub,
      "--member=$agent", '--role=roles/pubsub.subscriber', "--project=$TargetProject", '--quiet') | Out-Null
  }

  # Publishers: each service that owns an outbox publishes to the reporting topic.
  foreach ($svc in @('school-core-service', 'operations-service', 'billing-service')) {
    Invoke-Gcloud @('pubsub', 'topics', 'add-iam-policy-binding', "ims-reporting-events-v1-$Environment",
      "--member=serviceAccount:$($RuntimeAccounts[$svc])@$TargetProject.iam.gserviceaccount.com",
      '--role=roles/pubsub.publisher', "--project=$TargetProject", '--quiet') | Out-Null
  }
  Write-Ok 'publisher bindings applied'
}

# ---------------------------------------------------------------- 90 Capture source definitions
if (Should-Run 90) {
  Write-Stage '90 Capture source service definitions'
  New-Item -ItemType Directory -Force -Path $CaptureDirectory | Out-Null
  foreach ($svc in $Services) {
    $path = Join-Path $CaptureDirectory "$svc.yaml"
    $yaml = Invoke-Gcloud @('run', 'services', 'describe', "custoking-$svc-$Environment",
      "--project=$SourceProject", "--region=$Region", '--format=export')
    $yaml | Set-Content -Path $path -Encoding utf8
    Write-Ok "$svc -> $path"
  }
  Write-Host "  note feed these to Deploy-DestinationServices.ps1." -ForegroundColor Yellow
}

Write-Host "`nDone. Destination project number $TargetProjectNumber." -ForegroundColor Cyan
Write-Host "Next: Copy-MigrationSecrets.ps1, then Deploy-DestinationServices.ps1, then the data move." -ForegroundColor Cyan

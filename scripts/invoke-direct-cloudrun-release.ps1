param(
  [Parameter(Mandatory = $true)]
  [string]$ProjectId,

  [Parameter(Mandatory = $true)]
  [string]$Region,

  [Parameter(Mandatory = $true)]
  [ValidateSet("dev")]
  [string]$Environment,

  [Parameter(Mandatory = $true)]
  [string]$ImagesJson,

  [string]$OutputPath = "release-evidence/deployment.json"
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
function Invoke-DirectCloudRunDeployment {
  param([string[]]$Arguments)
  # Preserve true serial readiness and bound a stalled CLI/control-plane wait.
  $worker=Start-Job -ScriptBlock {
    param($command,$arguments)
    $ErrorActionPreference='Continue'
    $null=& $command @arguments 2>$null
    @{ExitCode=$LASTEXITCODE}
  } -ArgumentList $GcloudCommand,$Arguments
  try {
    $deadline=(Get-Date).AddMinutes(10)
    do { $done=Wait-Job $worker -Timeout 30 } while(-not $done -and (Get-Date) -lt $deadline)
    if(-not $done){Stop-Job $worker;throw 'Cloud Run synchronous readiness deadline exceeded; subsequent services remain blocked.'}
    $result=Receive-Job $worker -ErrorAction Stop
    if($result.ExitCode -ne 0){throw 'Cloud Run synchronous deployment failed; subsequent services remain blocked.'}
  } finally { Remove-Job $worker -Force -ErrorAction SilentlyContinue }
}
function Get-DirectReleaseService {
  param([string]$Service)
  $previousPreference=$ErrorActionPreference
  try {
    $ErrorActionPreference='Continue'
    $json=& $GcloudCommand run services describe "custoking-$Service-$Environment" "--project=$ProjectId" "--region=$Region" --format=json 2>$null
    $nativeExitCode=$LASTEXITCODE
  } finally { $ErrorActionPreference=$previousPreference }
  if($nativeExitCode -ne 0){throw "Direct release could not inspect $Service; reconcile configuration before retry."}
  return ($json -join "`n" | ConvertFrom-Json)
}
function Assert-DirectReleaseProfile {
  param([string]$Service,$Data)
  $container=$Data.spec.template.spec.containers[0]
  $settings=@{}
  foreach($entry in @($container.env)) {
    if($settings.ContainsKey([string]$entry.name)){throw "Duplicate runtime setting on $Service; reconcile configuration."}
    $settings[[string]$entry.name]=$entry
  }
  $roles=@{'identity-service'='identity';'school-core-service'='school-core';'operations-service'='operations';'billing-service'='billing';'platform-service'='platform'}
  if($roles.ContainsKey($Service)) {
    $prefix=$roles[$Service];$expectedRole='ims_'+$prefix.Replace('-','_')+'_rt'
    if([string]$settings['APP_MIGRATIONS_ENABLED'].value -ne 'false' -or
       @($settings.Keys|Where-Object {$_ -match '^(FLYWAY_|SPRING_FLYWAY_)'}).Count -gt 0 -or
       [string]$settings['SPRING_DATASOURCE_USERNAME'].value -ne $expectedRole -or
       [string]$settings['RUNTIME_DB_ROLE'].value -ne $expectedRole){
      throw "Direct release refuses old migration/shared-owner database profile on $Service; reconcile rendered deployment configuration first."
    }
    $secret=[string]$settings['SPRING_DATASOURCE_PASSWORD'].valueFrom.secretKeyRef.name
    $expectedSecret="$prefix-runtime-db-password-$Environment"
    if($secret -ne $expectedSecret -and $secret -ne "projects/$ProjectId/secrets/$expectedSecret"){
      throw "Direct release requires the exact dedicated database secret on $Service."
    }
    $expectedPool=if($Service -eq 'school-core-service'){'8'}else{'3'}
    if([string]$settings['DB_POOL_MAX'].value -ne $expectedPool){throw "Direct release requires reviewed database pool budget on $Service."}
  }
  if($Service -in @('api-gateway','frontend')){
    $ports=@($container.ports)
    if($ports.Count -ne 1 -or [int]$ports[0].containerPort -ne 8080){throw "Direct release requires reconciled port8080 on $Service."}
  }
  if($Service -eq 'api-gateway'){
    if([string]$settings['GATEWAY_AUTH_MODE'].value -ne 'enforce' -or
       [string]$settings['GATEWAY_CLOUD_RUN_AUTH'].value -notin @('auto','always') -or
       [string]$settings['GATEWAY_LOCAL_JWT_VERIFY'].value -ne 'disabled' -or $settings.ContainsKey('APP_JWT_SECRET')){
      throw 'Direct release requires the reconciled gateway authentication profile without a JWT signing secret.'
    }
    $ready=[string]$Data.status.latestReadyRevisionName
    if(-not $ready -or $ready -ne [string]$Data.status.latestCreatedRevisionName -or
       @($Data.spec.traffic|Where-Object {$_.latestRevision -eq $true -and [int]$_.percent -eq 100}).Count -ne 1 -or
       @($Data.status.traffic|Where-Object {$_.revisionName -eq $ready -and [int]$_.percent -eq 100}).Count -ne 1){
      throw 'Direct release requires ready gateway LATEST100 traffic; pinned traffic must be explicitly reconciled before any backend update.'
    }
  }
}

if (-not (Test-Path -LiteralPath $ImagesJson)) {
  throw "Release image evidence not found: $ImagesJson"
}

$imageEvidence = Get-Content -Raw -Path $ImagesJson | ConvertFrom-Json
$images = @($imageEvidence.services)
$commitSha = [string]$imageEvidence.commit
if ($commitSha -notmatch '^[0-9a-f]{40}$') {
  throw "Release image evidence has an invalid commit SHA."
}
$releaseOrder = @(
  "api-gateway",
  "identity-service",
  "frontend",
  "school-core-service",
  "operations-service",
  "billing-service",
  "platform-service"
)
$byService = @{}
foreach ($image in $images) {
  $byService[[string]$image.service] = $image
}
if($ProjectId -ne "custoking-$Environment"){throw 'Direct release project must match its explicit environment.'}
# Complete the entire read-only profile check before any image update or owner migration.
$preflightServices=@('api-gateway')+@($images|ForEach-Object {[string]$_.service})|Select-Object -Unique
$runtimeProfiles=@{}
foreach($preflightService in $preflightServices){
  if($preflightService -notin $releaseOrder){throw 'Direct release contains an unknown service.'}
  $profile=Get-DirectReleaseService -Service $preflightService
  Assert-DirectReleaseProfile -Service $preflightService -Data $profile
  $runtimeProfiles[$preflightService]=$profile
}

$deployments = @()
foreach ($service in $releaseOrder) {
  $image = $byService[$service]
  if (-not $image) {
    continue
  }

  $cloudRunService = "custoking-$service-$Environment"
  $tracedService = $service -in @("api-gateway", "billing-service", "identity-service", "operations-service", "platform-service", "school-core-service")
  $expectedResourceAttributes = if ($tracedService) {
    "gcp.project_id=$ProjectId,deployment.environment.name=$Environment,service.version=$commitSha"
  } else {
    ""
  }
  $expectedRuntimeRef = if ($image.PSObject.Properties.Name -contains "runtimeRef") {
    [string]$image.runtimeRef
  } else {
    [string]$image.immutableRef
  }
  $serviceData = $runtimeProfiles[$service]
  $currentResourceAttributes = [string](@($serviceData.spec.template.spec.containers[0].env | Where-Object {
    $_.name -eq "OTEL_RESOURCE_ATTRIBUTES"
  } | Select-Object -First 1).value)
  $traceMetadataCurrent = -not $tracedService -or $currentResourceAttributes -eq $expectedResourceAttributes
  $latestReady = [string]$serviceData.status.latestReadyRevisionName
  $latestCreated = [string]$serviceData.status.latestCreatedRevisionName
  $trafficReady = @($serviceData.status.traffic | Where-Object { $_.revisionName -eq $latestReady -and [int]$_.percent -eq 100 }).Count -gt 0
  $tracksLatest = @($serviceData.spec.traffic | Where-Object { $_.latestRevision -eq $true -and [int]$_.percent -eq 100 }).Count -gt 0

  if (-not [string]::IsNullOrWhiteSpace($latestReady) -and $latestReady -eq $latestCreated -and $trafficReady -and $tracksLatest) {
    $revisionJson = & $GcloudCommand run revisions describe $latestReady `
      "--project=$ProjectId" `
      "--region=$Region" `
      --format=json
    if ($LASTEXITCODE -eq 0) {
      $revisionData = $revisionJson | ConvertFrom-Json
      if ([string]$revisionData.status.imageDigest -eq $expectedRuntimeRef -and $traceMetadataCurrent) {
        Write-Host "$cloudRunService already serves $expectedRuntimeRef; deployment skipped."
        $deployments += [ordered]@{
          service = $service
          cloudRunService = $cloudRunService
          image = $image.immutableRef
          runtimeImage = $expectedRuntimeRef
          revision = $latestReady
          status = "already-current"
        }
        continue
      }
    }
  }

  if ($service -in @('identity-service','school-core-service','operations-service','platform-service','billing-service')) {
    & (Join-Path $PSScriptRoot 'invoke-service-migration-job.ps1') -ProjectId $ProjectId -Region $Region -Environment $Environment -Service $service -ImageRef $expectedRuntimeRef -CommitSha $commitSha -ConfigSource CloudRun -OutputDirectory (Join-Path (Split-Path -Parent $OutputPath) 'migrations') | Out-Null
  }
  Write-Host "Deploying $cloudRunService with $($image.immutableRef)."
  $deployArguments = @(
    "run", "deploy", $cloudRunService,
    "--image=$($image.immutableRef)",
    "--project=$ProjectId",
    "--region=$Region",
    "--quiet"
  )
  if ($tracedService) {
    $deployArguments += "--update-env-vars=^@^OTEL_RESOURCE_ATTRIBUTES=$expectedResourceAttributes"
  }
  Invoke-DirectCloudRunDeployment -Arguments $deployArguments

  $deployments += [ordered]@{
    service = $service
    cloudRunService = $cloudRunService
    image = $image.immutableRef
    runtimeImage = $expectedRuntimeRef
    status = "submitted"
  }
}

$outputDirectory = Split-Path -Parent $OutputPath
if ($outputDirectory) {
  New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}

[ordered]@{
  mode = "cloud-run-direct"
  environment = $Environment
  deployedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  services = $deployments
} | ConvertTo-Json -Depth 10 | Set-Content -Path $OutputPath

$submittedCount = @($deployments | Where-Object { $_.status -eq "submitted" }).Count
$currentCount = @($deployments | Where-Object { $_.status -eq "already-current" }).Count
Write-Host "Direct Cloud Run release completed: submitted=$submittedCount alreadyCurrent=$currentCount."

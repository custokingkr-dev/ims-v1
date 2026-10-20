$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$fixtures = Join-Path $repo ('artifacts/tests/rollout-groups-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtures -Force | Out-Null
$allServices = @('api-gateway','identity-service','frontend','school-core-service','operations-service','billing-service','platform-service')
$fixtureScripts = Join-Path $fixtures 'scripts'
New-Item -ItemType Directory -Path $fixtureScripts | Out-Null
foreach ($name in @('invoke-clouddeploy-release.ps1','wait-clouddeploy-rollout-group.ps1')) {
  Copy-Item -LiteralPath (Join-Path $repo "scripts/$name") -Destination $fixtureScripts
}
# Replace only the external migration boundary in the isolated test copy.
@'
param($ProjectId,$Region,$Environment,$Service,$ImageRef,$CommitSha,$ConfigSource,$OutputDirectory)
if ($ProjectId -ne 'fixture-prod' -or $Region -ne 'asia-south2' -or $Environment -ne 'prod' -or
    $ConfigSource -ne 'CloudDeploy' -or $ImageRef -notmatch '@sha256:b{64}$' -or $CommitSha -ne ('a'*40)) {
  throw 'Migration must use the exact runtime digest and explicit selected environment'
}
$global:rolloutMock.events.Add("migration/$Service")
if ($global:rolloutMock.failMigration -eq $Service) { throw 'MIGRATION_FAILED' }
'@ | Set-Content -LiteralPath (Join-Path $fixtureScripts 'invoke-service-migration-job.ps1')

function Assert-True($Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function global:Invoke-CiRolloutGcloudMock {
  $words = @($args | ForEach-Object { [string]$_ })
  $global:LASTEXITCODE = 0
  if ($words -notcontains '--project=fixture-prod' -or $words -notcontains '--region=asia-south2') { throw 'Every cloud command must carry the explicit project and region' }
  $pipeline = (($words | Where-Object { $_.StartsWith('--delivery-pipeline=') }) -replace '^--delivery-pipeline=', '')
  $service = $pipeline -replace '^custoking-', '' -replace '-prod$', ''
  $verb = $words[0..2] -join ' '
  $global:rolloutMock.events.Add("$verb/$service")
  if ($verb -eq 'deploy releases create') {
    $active = @($global:rolloutMock.states.Keys | Where-Object { $global:rolloutMock.states[$_].stage -lt 4 })
    foreach ($dependency in @('api-gateway','identity-service','frontend','school-core-service')) {
      if ($dependency -eq $service) { break }
      if ($global:rolloutMock.selected -contains $dependency -and $global:rolloutMock.states[$dependency].stage -ne 4) {
        throw "Required dependency $dependency was not ready before $service"
      }
    }
    if ($service -in @('identity-service','school-core-service','operations-service','billing-service','platform-service') -and
        $global:rolloutMock.events -notcontains "migration/$service") { throw 'Release started before its migration completed' }
    $global:rolloutMock.states[$service] = @{stage=0;describes=0}
    $global:rolloutMock.maxActive = [Math]::Max($global:rolloutMock.maxActive, $active.Count + 1)
    if ($global:rolloutMock.failCreate -eq $service) { $global:LASTEXITCODE=1 }
    return
  }
  if ($verb -eq 'deploy rollouts list') { return "projects/fixture-prod/locations/asia-south2/deliveryPipelines/$pipeline/releases/rel-prod-aaaaaaaaaaaa-1/rollouts/rollout-one" }
  $current = $global:rolloutMock.states[$service]
  if (!$current) { throw 'Unknown mock rollout' }
  if ($verb -eq 'deploy rollouts advance') {
    $expected = @('canary-5','canary-25','canary-50','stable')[$current.stage]
    Assert-True ($words -contains "--phase-id=$expected") 'Canary stages must advance once in the correct order'
    Assert-True ($current.describes -gt 1) 'PENDING_RELEASE must never be advanced'
    if ($global:rolloutMock.staleService -eq $service) { $current.staleStage=$current.stage; $current.staleReads=2 }
    $current.stage++
    return
  }
  if ($verb -eq 'deploy rollouts describe') {
    $current.describes++
    $visibleStage = $current.stage
    if ($current.staleReads -gt 0) { $visibleStage=$current.staleStage; $current.staleReads-- }
    $state = if ($visibleStage -eq 4) { 'SUCCEEDED' } elseif ($current.describes -eq 1) { 'PENDING_RELEASE' } else { 'IN_PROGRESS' }
    if ($global:rolloutMock.failService -eq $service -and $current.describes -gt 1) { $state = 'FAILED' }
    if ($global:rolloutMock.approval -eq $service -and $current.describes -gt 1) { $state = 'PENDING_APPROVAL' }
    $names = @('canary-5','canary-25','canary-50','stable')
    $phases = @(for ($i=0; $i -lt 4; $i++) { @{id=$names[$i];state=$(if($i -lt $visibleStage){'SUCCEEDED'}else{'PENDING'})} })
    if ($global:rolloutMock.phaseFailure -eq $service -and $current.describes -gt 1) { $phases[0].state='FAILED' }
    return @{state=$state;phases=$phases} | ConvertTo-Json -Depth 5 -Compress
  }
  throw "Unexpected cloud command: $verb"
}
function global:gcloud { Invoke-CiRolloutGcloudMock @args }
function global:gcloud.cmd { Invoke-CiRolloutGcloudMock @args }
function global:Start-Sleep {
  param([int]$Seconds)
  $global:rolloutMock.sleeps++
  if ($global:rolloutMock.sleeps -gt 40) { throw 'MOCK_WAIT_LIMIT' }
}

function Run-Case([string]$Name, [string[]]$Services, [int]$Concurrency=2, [string]$FailService='', [string]$FailCreate='', [string]$Approval='', [switch]$NoWait, [string]$StaleService='', [string]$PhaseFailure='', [string]$FailMigration='') {
  $global:rolloutMock = @{selected=$Services;states=@{};events=[Collections.Generic.List[string]]::new();maxActive=0;sleeps=0;failService=$FailService;failCreate=$FailCreate;approval=$Approval;staleService=$StaleService;phaseFailure=$PhaseFailure;failMigration=$FailMigration}
  $inputPath = Join-Path $fixtures "$Name-images.json"
  $outputPath = Join-Path $fixtures "$Name-deployment.json"
  @{services=@($Services | ForEach-Object { @{service=$_;image="custoking-$_";immutableRef=('region-docker.pkg.dev/fixture-prod/images/' + $_ + '@sha256:' + ('a'*64));runtimeRef=('region-docker.pkg.dev/fixture-prod/images/' + $_ + '@sha256:' + ('b'*64))} })} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $inputPath
  $errorText = $null
  try {
    & (Join-Path $fixtureScripts 'invoke-clouddeploy-release.ps1') -ProjectId fixture-prod -Region asia-south2 -Environment prod -CommitSha ('a'*40) -RunAttempt 1 -ImagesJson $inputPath -SourceStagingDir gs://fixture-staging/source -OutputPath $outputPath -WaitForRollout:(!$NoWait) -AutoAdvanceCanary -MaxParallelRollouts $Concurrency 6>$null
  } catch { $errorText=$_.Exception.Message }
  $evidence = if (Test-Path -LiteralPath $outputPath) { Get-Content -Raw -LiteralPath $outputPath | ConvertFrom-Json } else { $null }
  return @{error=$errorText;evidence=$evidence;mock=$global:rolloutMock}
}

try {
  foreach ($path in @('scripts/invoke-clouddeploy-release.ps1', 'scripts/wait-clouddeploy-rollout-group.ps1', 'scripts/resolve-release-images.ps1', 'scripts/resolve-release-image-worker.ps1')) {
    $routing = & (Join-Path $repo 'scripts/resolve-affected-ci-targets.ps1') -ChangedFilesOverride @($path) -Environment dev | ConvertFrom-Json
    Assert-True ($routing.has_service_changes -and $routing.service_matrix.include.Count -eq 7 -and !$routing.deployment_reconciliation_required) 'Deployment helper changes must select every service without bypassing or inventing reconciliation'
    if ($path -match '(invoke-clouddeploy|wait-clouddeploy)') { Assert-True $routing.deployment_config_changed 'Rollout helper changes must exercise the Cloud Deploy path on dev' }
  }
  $result=Run-Case full $allServices
  Assert-True (!$result.error) "Full rollout failed: $($result.error)"
  Assert-True ($result.mock.maxActive -eq 2) 'Independent backend rollouts must overlap, capped at two'
  Assert-True ($result.evidence.services.Count -eq 7 -and @($result.evidence.services | Where-Object status -ne succeeded).Count -eq 0) 'All seven successful receipts must be retained'
  Assert-True (@($result.mock.events | Where-Object { $_ -like 'deploy rollouts advance/*' }).Count -eq 28) 'Every service must retain all four canary transitions'
  $stale=Run-Case stale @('identity-service','operations-service') -StaleService identity-service
  Assert-True (!$stale.error -and @($stale.mock.events | Where-Object { $_ -like 'deploy rollouts advance/*' }).Count -eq 8) 'Stale snapshots must not repeat an advance request'
  $phase=Run-Case phase-failure $allServices -PhaseFailure operations-service
  Assert-True ($phase.error -like '*phase*ended in FAILED*' -and $phase.mock.states.Count -eq 6) 'A failed phase must block release even before aggregate state catches up'
  Assert-True (@($phase.mock.events | Where-Object { $_ -eq 'deploy rollouts advance/billing-service' }).Count -eq 0) 'A known failed phase must block peer advancement'
  $serial=Run-Case serial $allServices 1
  Assert-True (!$serial.error -and $serial.mock.maxActive -eq 1) 'Serial fallback must still work'
  $subset=Run-Case subset @('platform-service','frontend')
  Assert-True (!$subset.error -and $subset.evidence.services.Count -eq 2) 'Affected-only releases must not require or deploy absent services'
  $failure=Run-Case failure $allServices 2 operations-service
  Assert-True ($failure.error -like '*ended in FAILED*') 'Terminal failure must stop the release'
  Assert-True ($failure.mock.states.Count -eq 6 -and $failure.evidence.services.Count -eq 6) 'Failure must retain partial receipts and prevent later backend creation'
  Assert-True (@($failure.mock.events | Where-Object { $_ -eq 'deploy rollouts advance/billing-service' }).Count -eq 0) 'Known peer failure must block all group advancement'
  $creation=Run-Case creation $allServices 2 '' operations-service
  Assert-True ($creation.error -like '*Could not create*' -and $creation.evidence.services.Count -eq 5 -and $creation.evidence.services[-1].status -eq 'create-requested') 'Ambiguous creation must retain the exact attempted release receipt'
  $migration=Run-Case migration-failure $allServices -FailMigration school-core-service
  Assert-True ($migration.error -eq 'MIGRATION_FAILED' -and $migration.mock.states.Count -eq 3) 'Failed migration must prevent its release and every subsequent backend'
  $approval=Run-Case approval @('identity-service') 2 '' '' identity-service
  Assert-True ($approval.error -eq 'MOCK_WAIT_LIMIT') 'Approval must remain pending, not bypassed'
  Assert-True (@($approval.mock.events | Where-Object { $_ -like 'deploy rollouts advance/*' }).Count -eq 0) 'Pending approval must not be advanced'
  $invalid=Run-Case invalid @('unknown-service')
  Assert-True ($invalid.error -like '*Unknown or duplicate*' -and $invalid.mock.events.Count -eq 0) 'Unknown services must fail before cloud operations'
  $duplicate=Run-Case duplicate @('identity-service','identity-service')
  Assert-True ($duplicate.error -like '*Unknown or duplicate*' -and $duplicate.mock.events.Count -eq 0) 'Duplicate services must fail before cloud operations'
  $noWait=Run-Case no-wait @('identity-service') -NoWait
  Assert-True ($noWait.error -like '*require*WaitForRollout*' -and $noWait.mock.events.Count -eq 0) 'Parallel mode must require dependency waits'
  Write-Host 'Cloud Deploy rollout group tests passed: bounded overlap, dependency barriers, all canaries, failure/approval handling, partial evidence, affected-only and serial fallback.'
} finally {
  Remove-Item Function:/gcloud,Function:/gcloud.cmd,Function:/Start-Sleep,Function:/Invoke-CiRolloutGcloudMock -ErrorAction SilentlyContinue
  Remove-Variable rolloutMock -Scope Global -ErrorAction SilentlyContinue
}

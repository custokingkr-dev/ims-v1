#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runner=Join-Path $root 'scripts/resolve-release-images.ps1'
$fixture=Join-Path $PSScriptRoot 'fixtures/mock-release-image-command.ps1'
$scratch=Join-Path $root ('artifacts/release-image-tests-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch -Force | Out-Null
$oldPath=$env:PATH; $oldState=$env:RELEASE_IMAGE_TEST_STATE
$checks=0
function Assert([bool]$Condition,[string]$Message) { if (!$Condition) { throw $Message } }
function Read-Events([string]$Directory) { @(Get-ChildItem -LiteralPath $Directory -Filter '*.event.json' | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json }) }
try {
  $mockBin=Join-Path $scratch 'bin'; New-Item -ItemType Directory -Path $mockBin | Out-Null
  foreach ($program in @('gcloud','docker','cosign')) {
    $escaped=$fixture.Replace("'","''")
    [IO.File]::WriteAllText((Join-Path $mockBin "$program.ps1"),"& '$escaped' -Program '$program' -CommandArguments `$args")
  }
  $env:PATH=$mockBin+[IO.Path]::PathSeparator+$oldPath
  foreach ($program in @('gcloud','docker','cosign')) { Assert ((Get-Command $program).Source.StartsWith($mockBin)) "Command $program did not resolve to the strict local mock." }
  $sha=(& git -C $root rev-parse HEAD).Trim()
  $names=@('identity-service','school-core-service','operations-service','platform-service','billing-service','frontend','api-gateway')
  $entries=@($names | ForEach-Object { @{name=$_;image=$_;context='.';source_paths='pom.xml';build_args="SERVICE=$_"} })
  $matrix=@{include=$entries} | ConvertTo-Json -Depth 5 -Compress
  function Run-Scenario([string]$Scenario,[string]$ExpectedError='',[string]$Mode='prod',[int]$Limit=3) {
    $directory=Join-Path $scratch "$Scenario-$Mode-$Limit"; New-Item -ItemType Directory -Path $directory | Out-Null
    [IO.File]::WriteAllText((Join-Path $directory 'scenario'),$Scenario)
    $env:RELEASE_IMAGE_TEST_STATE=$directory
    $output=Join-Path $directory 'images.json'; $ghOutput=Join-Path $directory 'github-output'
    $arguments=@{Environment=$Mode;CommitSha=$sha;ProjectId=$(if ($Mode -eq 'dev') {'custoking-dev'}else{'custoking-prod'});
      SourceProjectId='custoking-dev';Region='asia-south2';Repository='custoking';GitHubRepository='custokingkr-dev/ims-v1';
      MatrixJson=$matrix;MaxConcurrency=$Limit;OutputPath=$output;GitHubOutput=$ghOutput}
    $failure=$null
    try { & $runner @arguments } catch { $failure=$_.Exception.Message }
    if ($ExpectedError) {
      Assert ($failure -and $failure -match $ExpectedError) "Scenario $Scenario did not fail as expected: $failure"
      Assert (!(Test-Path $output) -and !(Test-Path $ghOutput)) "Failure $Scenario published partial release/scan outputs."
    } else {
      Assert (!$failure) "Scenario $Scenario failed: $failure"
      $evidence=Get-Content -LiteralPath $output -Raw | ConvertFrom-Json
      Assert ($evidence.services.Count -eq 7 -and $evidence.commit -ceq $sha -and $evidence.registry -ceq 'asia-south2-docker.pkg.dev/custoking-dev/custoking') 'Release schema changed.'
      Assert (($evidence.services.service -join ',') -ceq ($names -join ',')) 'Parallel results changed stable matrix order.'
      $outputs=Get-Content -LiteralPath $ghOutput
      Assert ($outputs.Count -eq 14) 'All seven scan outputs must include immutable reference and digest key.'
      foreach ($key in @('identity','school_core','operations','platform','billing','frontend','api_gateway')) {
        Assert (@($outputs | Where-Object { $_ -cmatch "^${key}_ref=.+@sha256:[0-9a-f]{64}$" }).Count -eq 1) "Missing/duplicate immutable scan mapping: $key"
        Assert (@($outputs | Where-Object { $_ -cmatch "^${key}_digest_key=sha256-[0-9a-f]{64}$" }).Count -eq 1) "Missing/duplicate digest scan mapping: $key"
      }
      foreach ($i in 0..6) {
        $service=$evidence.services[$i]
        $expected=& (Join-Path $root 'scripts/resolve-image-source-id.ps1') -CommitSha $sha -Context '.' -SourcePaths 'pom.xml' -BuildArgs "SERVICE=$($names[$i])" | ConvertFrom-Json
        Assert ($service.sourceId -ceq $expected.sourceId -and $service.sourceTag -ceq $expected.sourceTag) 'Content-addressed source identity changed.'
        $approved=if ($Mode -eq 'prod') {'dev-approved-'+$expected.sourceTag}else{$expected.sourceTag}
        Assert ($service.resolvedTag -ceq $approved) 'Approved/source tag selection changed.'
        Assert ($service.immutableRef -cmatch '@sha256:[0-9a-f]{64}$' -and $service.runtimeRef -cmatch '@sha256:[0-9a-f]{64}$') 'Mutable deployment reference.'
      }
    }
    $script:checks++
    return $directory
  }
  $successful=Run-Scenario 'success'
  $events=Read-Events $successful
  Assert (@($events | Where-Object program -eq 'cosign').Count -eq 7) 'Every production image needs its own exact-identity signature verification.'
  Assert (@($events | Where-Object { $_.arguments[0..2] -join ' ' -eq 'artifacts tags create' }).Count -eq 7) 'Promotion must publish using create-only tags.'
  foreach ($name in $names) {
    $verified=($events | Where-Object { $_.program -eq 'cosign' -and $_.image -eq $name }).finished
    $copy=($events | Where-Object { $_.program -eq 'docker' -and $_.image -eq $name -and $_.arguments[2] -eq 'create' }).started
    Assert ($verified -le $copy) 'A copy began before its signature verification completed.'
  }
  $timeline=@(foreach($event in $events) { @{time=$event.started;delta=1}; @{time=$event.finished;delta=-1} }) | Sort-Object time,delta
  $active=0; $peak=0
  foreach($point in $timeline) { $active+=$point.delta; $peak=[Math]::Max($peak,$active) }
  Assert ($peak -gt 1 -and $peak -le 3) "Expected bounded parallel command execution; observed $peak."
  $script:checks++
  foreach ($scenario in @('reuse','race-same','single-manifest','moving-source-tag')) {
    $directory=Run-Scenario $scenario
    if ($scenario -eq 'reuse') { Assert (@(Read-Events $directory | Where-Object { $_.arguments[2] -eq 'create' }).Count -eq 0) 'Existing identical promotion must not be rewritten.' }
  }
  $dev=Run-Scenario 'success' '' 'dev' 2
  Assert (@(Read-Events $dev | Where-Object { $_.program -eq 'cosign' -or $_.arguments[2] -eq 'create' }).Count -eq 0) 'Dev in the build registry must not verify/promote or retag.'
  foreach ($case in @(
    @('bad-signature','signature'),@('one-bad-signature','signature'),@('missing-source','Could not resolve'),@('malformed-source','Invalid registry digest'),
    @('conflict','Promotion tag conflict'),@('target-denied','Could not resolve'),@('malformed-target','Invalid registry digest'),
    @('malformed-manifest','Malformed OCI'),@('missing-amd64','exactly one runnable'),@('ambiguous-amd64','exactly one runnable'),
    @('wrong-platform','not runnable'),@('copy-failed','Could not promote'),@('wrong-promoted','Promoted digest'),
    @('wrong-runtime','runtime digest differs'),@('missing-runtime','Could not inspect'),@('race-conflict','conflict after publication'),
    @('tag-denied','Could not create promotion tag')
  )) {
    $directory=Run-Scenario $case[0] $case[1]
    if ($case[0] -in @('bad-signature','conflict','target-denied','malformed-target')) {
      Assert (@(Read-Events $directory | Where-Object { $_.arguments[2] -eq 'create' }).Count -eq 0) 'Rejected signature/tag/permission caused registry writes.'
    }
  }
  $duplicate=@{include=@($entries[0],$entries[0])} | ConvertTo-Json -Depth 5
  $prior=$matrix; $matrix=$duplicate
  [void](Run-Scenario 'duplicate' 'Duplicate release'); $matrix=$prior
  [void](Run-Scenario 'invalid-concurrency' 'range' 'prod' 4)
  Write-Host "PASS: $checks mocked scenarios/assertion groups, all seven image/scan mappings, concurrency peak $peak, no cloud calls."
} finally {
  $env:PATH=$oldPath; $env:RELEASE_IMAGE_TEST_STATE=$oldState
  $resolved=[IO.Path]::GetFullPath($scratch)
  $allowed=[IO.Path]::GetFullPath((Join-Path $root 'artifacts'))+[IO.Path]::DirectorySeparatorChar
  if (!$resolved.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path.' }
  Remove-Item -LiteralPath $resolved -Recurse -Force
}

$ErrorActionPreference='Stop'
$file=Join-Path $PSScriptRoot '../run-dev-broadcast-acceptance.ps1'
$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($file,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Script parse failed'}
foreach($f in $ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst]},$false)){Invoke-Expression $f.Extent.Text}
$StudentId=9911152;$RunId='product-20260926-a1';$PolicyEvidenceSource='PublicManifest';$id='11111111-1111-4111-8111-111111111111';$cases=0
function Save-Journal {}
function Fixtures {
  $script:state=[ordered]@{fixtureWritesStarted=$true;guardianId='own-guardian';grantedConsentId='own-grant';broadcastdryrun=$id}
  $script:overview=[pscustomobject]@{schoolId=1;studentId=$StudentId;guardians=@([pscustomobject]@{id='own-guardian';fullName="Synthetic Guardian $RunId";email="$RunId@acceptance.invalid";relationship='GUARDIAN';primary=$true;receivesNotifications=$true;contactVerifiedAt='2026-09-26T12:00:00Z';status='ACTIVE';phone=$null});consents=@([pscustomobject]@{id='own-grant';guardianId='own-guardian';purpose='SCHOOL_COMMUNICATIONS';status='GRANTED';evidenceReference=$RunId;noticeVersion='synthetic-acceptance-v1'});consentHistory=@()}
  $script:preview=[pscustomobject]@{broadcastId=$id;eligible=1;duplicate=0;total=2;suppressed=1;fingerprint=('a'*64)};$script:paths=@()
}
function Api($Method,$Path,$Body){
  $script:paths+=,$Path
  if($Method -ceq 'GET' -and $Path -ceq "/students/$StudentId/guardians"){return $script:overview}
  if($Method -ceq 'POST' -and $Path -ceq "/notifications/broadcasts/$id/preview"){return $script:preview}
  throw 'Unexpected public policy route'
}
function Reject([scriptblock]$Action){$failed=$false;try{&$Action}catch{$failed=$true};if(-not $failed){throw 'Expected policy rejection'};$script:cases++}
Fixtures;Read-OwnedPolicy $id;$cases++
if($paths.Count -ne 2 -or $state.publicPolicyEvidence.Count -ne 1){throw 'Fresh public policy evidence was not recorded'}
foreach($bad in @('wrong-student','wrong-guardian','real-address','unverified','extra-guardian','foreign-consent','different-grant','withdrawn','extra-eligible','duplicate','wrong-broadcast')){
  Fixtures
  switch($bad){
    wrong-student {$overview.studentId=9}
    wrong-guardian {$overview.guardians[0].id='other'}
    real-address {$overview.guardians[0].email='someone@example.com'}
    unverified {$overview.guardians[0].contactVerifiedAt=$null}
    extra-guardian {$overview.guardians+= $overview.guardians[0]}
    foreign-consent {$overview.consents[0].evidenceReference='other-run'}
    different-grant {$overview.consents[0].id='other-grant'}
    withdrawn {$overview.consents[0].status='WITHDRAWN'}
    extra-eligible {$preview.eligible=2}
    duplicate {$preview.duplicate=1}
    wrong-broadcast {$preview.broadcastId='other'}
  }
  Reject {Read-OwnedPolicy $id}
}
Fixtures;$overview.consents[0].status='WITHDRAWN';$preview.eligible=0;$preview.suppressed=2
Read-OwnedPolicy $id $false $true;$cases++
$preview.eligible=1;Reject {Read-OwnedPolicy $id $false $true}
Fixtures;$state.fixtureWritesStarted=$false;$overview.guardians=@();$overview.consents=@()
Read-OwnedPolicy $id $false;$cases++
$overview.consentHistory=@(@{});Reject {Read-OwnedPolicy $id $false}
function Api($Method,$Path,$Body){
  if($Method -cne 'POST' -or $Path -cne "/notifications/broadcasts/$id/send" -or $Body -isnot [hashtable] -or $Body.Count -ne 0){throw 'Queue request must carry an explicit empty JSON object'}
  return @{broadcastId=$id;mode='DRY_RUN';delivered=0;status='QUEUED'}
}
Queue-Owned $id|Out-Null;$cases++
function Read-Gcloud($Arguments){if($Arguments -notcontains '--project=custoking-dev'){throw 'Missing dev project'};return 'MEMORY_ONLY_FAKE_CREDENTIAL'}
$script:cloudCount=0;$script:cleanupMode=$false;$deadline=[datetime]::UtcNow.AddMinutes(12)
$testFilter='timestamp>="2026-09-26T12:34:56Z" AND httpRequest.requestUrl="https://example.run.app/api/v1/internal/async/drain"'
function Invoke-RestMethod($Method,$Uri,$Headers,$Body,$ContentType,$TimeoutSec){
  $parsed=$Body|ConvertFrom-Json
  if($Method -cne 'POST' -or $Uri -cne 'https://logging.googleapis.com/v2/entries:list' -or $ContentType -cne 'application/json' -or @($parsed.resourceNames).Count -ne 1 -or $parsed.resourceNames[0] -cne 'projects/custoking-dev' -or $parsed.filter -cne $testFilter -or $parsed.orderBy -cne 'timestamp asc' -or $parsed.pageSize -ne 30){throw 'Logging request lost scope or quoted filter'}
  return @{entries=@(@{insertId='safe-fixture'})}
}
$entries=@(Read-DevLogEntries $testFilter);if($entries.Count -ne 1 -or $entries[0].insertId -cne 'safe-fixture'){throw 'Structured log evidence failed'};$cases++
function Invoke-RestMethod {throw 'MEMORY_ONLY_FAKE_CREDENTIAL'}
$safeFailure=$null;try{Read-DevLogEntries $testFilter}catch{$safeFailure=$_.Exception.Message}
if($safeFailure -notlike 'Dev log evidence read failed*' -or $safeFailure -like '*MEMORY_ONLY*'){throw 'Log error exposed a credential'};$cases++
Write-Output "PASS: $cases public policy binding and fresh-preview checks; no cloud or HTTP calls."

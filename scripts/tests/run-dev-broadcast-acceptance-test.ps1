$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$file=Join-Path $repoRoot 'scripts/run-dev-broadcast-acceptance.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($file,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Acceptance script parse failed'}
# Load the actual function definitions only. No main body, cloud command or HTTP client runs.
foreach($function in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)){Invoke-Expression $function.Extent.Text}
$StudentId=123;$RunId='product-20260926-qareview'+[guid]::NewGuid().ToString('N').Substring(0,8);$deadline=[datetime]::UtcNow.AddMinutes(12);$count=0
$state=[ordered]@{guardianId='guardian-fixture';grantedConsentId='grant-fixture';platformRevision='custoking-platform-service-dev-fixture';events=@()}
$policyHeaders=@{Authorization='fixture';'X-Broadcast-Policy-Token'='fixture'};$schoolUrl='https://custoking-school-core-service-dev-fixture.run.app'
$headers=@{};$session=New-Object Microsoft.PowerShell.Commands.WebRequestSession;$base='https://fixture.invalid/api/v1'
$id='11111111-1111-4111-8111-111111111111';$script:cases=0
function Save-Journal {}
function Start-Sleep {param($Seconds)}
function Expect-Rejected([scriptblock]$Action,[string]$Label){$failed=$false;try{&$Action}catch{$failed=$true};if(-not $failed){throw "Expected fail-closed: $Label"};$script:cases++}
function Policy-Row {return [pscustomobject]@{studentId=$StudentId;schoolId=1;channel='EMAIL';eventId="broadcast:$id`:$StudentId`:EMAIL";allowed=$true;reason='ALLOWED';guardianId=$state.guardianId;destination="$RunId@acceptance.invalid";policyEvidence=@{consentEventId=$state.grantedConsentId;guardianId=$state.guardianId}}}
$script:policyRows=@(Policy-Row)
function Invoke-WebRequest {
  param($Method,$Uri,$Headers,$Body,$ContentType,$TimeoutSec,$MaximumRedirection,[switch]$UseBasicParsing,$WebSession)
  if($Uri -like '*broadcast-recipients'){
    $parsed=$Body|ConvertFrom-Json
    if(@($parsed.studentIds).Count -ne 1 -or $parsed.studentIds[0] -ne $StudentId -or $parsed.schoolId -ne 1){throw 'Private read expanded beyond test student'}
    return [pscustomobject]@{StatusCode=200;Content=ConvertTo-Json -InputObject $script:policyRows -Depth 8}
  }
  if($script:transportFailure){throw 'FAKE_SECRET_MUST_NEVER_ESCAPE'}
  return [pscustomobject]@{StatusCode=204;Content=''}
}
Read-OwnedPolicy $id;$script:cases++
foreach($change in @('student','guardian','destination','consent','extra','denied')){
  $script:policyRows=@(Policy-Row)
  switch($change){
    student {$script:policyRows[0].studentId=999}
    guardian {$script:policyRows[0].guardianId='other'}
    destination {$script:policyRows[0].destination='real@example.com'}
    consent {$script:policyRows[0].policyEvidence.consentEventId='other'}
    extra {$script:policyRows+=Policy-Row}
    denied {$script:policyRows[0].allowed=$false}
  }
  Expect-Rejected {Read-OwnedPolicy $id} "policy $change"
}
$script:policyRows=@(Policy-Row)
Expect-Rejected {Read-OwnedPolicy $id $false $true} 'cleanup cannot accept still-allowed policy'
$script:policyRows[0].allowed=$false;$script:policyRows[0].reason='SCHOOL_COMMUNICATIONS_NOT_GRANTED'
Read-OwnedPolicy $id $false $true;$script:cases++
$script:policyRows=@(Policy-Row)
function Manifest {
  return [pscustomobject]@{broadcastId=$id;status='APPROVED';mode='OFF';delivered=0;total=2;recipients=@(
    [pscustomobject]@{studentId=$StudentId;channel='EMAIL';status='APPROVED';attempts=0},
    [pscustomobject]@{studentId=999;channel='EMAIL';status='SUPPRESSED';attempts=0}
  )}
}
Assert-OwnedManifest (Manifest) $id 'APPROVED';$script:cases++
foreach($change in @('ordinary-approved','ordinary-attempt','wrong-owned','wrong-broadcast','oversize','duplicate-owned')){
  $m=Manifest
  switch($change){
    ordinary-approved {$m.recipients[1].status='APPROVED'}
    ordinary-attempt {$m.recipients[1].attempts=1}
    wrong-owned {$m.recipients[0].studentId=1000}
    wrong-broadcast {$m.broadcastId='different'}
    oversize {$m.total=201}
    duplicate-owned {$m.recipients+= $m.recipients[0];$m.total=3}
  }
  Expect-Rejected {Assert-OwnedManifest $m $id 'APPROVED'} "manifest $change"
}
$script:transportFailure=$true
try{Api POST '/auth/login' @{password='FAKE_PASSWORD_MUST_NEVER_PERSIST'}}catch{if($_.Exception.Message -like '*FAKE_SECRET*'){throw 'Transport exception leaked'}}
if(($state|ConvertTo-Json -Depth 12) -match 'FAKE_PASSWORD|FAKE_SECRET'){throw 'Credential leaked to journal'};$script:cases++
$script:transportFailure=$false;$script:count=55;$script:cleanupMode=$false
Expect-Rejected {Api GET '/anything'} 'business request limit'
if($script:count -ne 55){throw 'Rejected request consumed cleanup reservation'}
$script:cleanupMode=$true;$script:cleanupDeadline=[datetime]::UtcNow.AddMinutes(3);$script:cleanupCount=9
$deadline=[datetime]::UtcNow.AddMinutes(-1)
Expect-Rejected {Api GET '/anything'} 'independent reserved cleanup logout slot'
Api POST '/auth/logout'|Out-Null
if($script:cleanupCount -ne 10 -or $script:count -ne 56){throw 'Cleanup logout could not use its independent deadline/budget'};$script:cases++
$script:cleanupMode=$false;$deadline=[datetime]::UtcNow.AddMinutes(12)

$platformUrl='https://custoking-platform-service-dev-fixture.run.app';$since=[datetime]::UtcNow.AddMinutes(-1).ToString('o')
$script:logs=@(
  [pscustomobject]@{timestamp=[datetime]::UtcNow.ToString('o');insertId='scheduler-fixture';resource=@{type='cloud_scheduler_job';labels=@{project_id='custoking-dev';location='asia-south1';job_id='ims-platform-service-async-relay-dev'}};jsonPayload=@{'@type'='type.googleapis.com/google.cloud.scheduler.logging.AttemptFinished';jobName='projects/custoking-dev/locations/asia-south1/jobs/ims-platform-service-async-relay-dev';url="$platformUrl/api/v1/internal/async/drain";targetType='HTTP'};httpRequest=@{status=200}},
  [pscustomobject]@{timestamp=[datetime]::UtcNow.ToString('o');insertId='request-fixture';resource=@{type='cloud_run_revision';labels=@{project_id='custoking-dev';service_name='custoking-platform-service-dev';revision_name=$state.platformRevision}};httpRequest=@{requestUrl="$platformUrl/api/v1/internal/async/drain";requestMethod='POST';userAgent='Google-Cloud-Scheduler';status=200}}
)
function Read-GcloudJson {param($Arguments) return $script:logs}
$tick=Wait-SchedulerTick $since
if($tick.schedulerInsertId -ne 'scheduler-fixture' -or $tick.requestInsertId -ne 'request-fixture'){throw 'Scheduler evidence mismatch'};$script:cases++
$script:logs[0].httpRequest.status=500
Expect-Rejected {Wait-SchedulerTick $since} 'failed Scheduler tick'
$script:logs[0].httpRequest.status=200;$script:logs[1].resource.labels.revision_name='old-revision'
Expect-Rejected {Wait-SchedulerTick $since} 'wrong serving revision'

$script:count=0;$script:eligible=1;$script:paths=@();$script:policyRows=@(Policy-Row);$script:approvedManifest=Manifest
function Api {
  param($Method,$Path,$Body)
  $script:paths+=,$Path
  if($Path -eq '/notifications/broadcasts'){return [pscustomobject]@{id=$id;schoolId=1;status='DRAFT';title="SYNTHETIC $RunId dryrun";channels=@('EMAIL')}}
  if($Path -like '*/preview'){return [pscustomobject]@{broadcastId=$id;eligible=$script:eligible;duplicate=0;total=2;suppressed=(2-$script:eligible);fingerprint=('a'*64)}}
  if($Path -like '*/approve'){return [pscustomobject]@{id=$id;schoolId=1;status='APPROVED'}}
  if($Path -like '*/delivery-status'){return $script:approvedManifest}
  throw 'Unexpected mocked mutation'
}
if((New-ReviewedBroadcast 'dryrun') -cne $id){throw 'Valid reviewed draft was rejected'};$script:cases++
$script:eligible=2;$script:paths=@()
Expect-Rejected {New-ReviewedBroadcast 'dryrun'} 'extra eligible recipient'
if(@($script:paths|Where-Object {$_ -like '*/approve'}).Count){throw 'Unsafe preview reached approval'}
$script:eligible=1;$script:policyRows[0].allowed=$false;$script:paths=@()
Expect-Rejected {New-ReviewedBroadcast 'dryrun'} 'own policy not allowed'
if(@($script:paths|Where-Object {$_ -like '*/approve'}).Count){throw 'Denied own policy reached approval'}
$script:policyRows=@(Policy-Row);$script:approvedManifest.recipients[1].status='APPROVED'
Expect-Rejected {New-ReviewedBroadcast 'dryrun'} 'stored approval contains another student'

# Exercise cleanup with server-owned overview responses. No real request/client or cloud read runs.
$script:cleanupPaths=@();$script:withdrawals=0;$script:policyDeniedChecks=0
function Cleanup-Fixtures {
  $script:cleanupStudent=[pscustomobject]@{id=$StudentId;schoolId=1;admissionNumber="QA-$RunId";fullName="Synthetic Student $RunId";sectionName="QA-$($RunId.ToUpperInvariant())"}
  $script:cleanupGuardian=[pscustomobject]@{id='guardian-owned';fullName="Synthetic Guardian $RunId";email="$RunId@acceptance.invalid";relationship='GUARDIAN';phone=$null}
  $script:cleanupConsent=[pscustomobject]@{id='grant-owned';purpose='SCHOOL_COMMUNICATIONS';guardianId='guardian-owned';status='GRANTED';evidenceReference=$RunId;noticeVersion='synthetic-acceptance-v1';lawfulBasis='CONSENT'}
  $script:cleanupPaths=@();$script:withdrawals=0;$script:policyDeniedChecks=0;$script:loseWithdrawalResponse=$false;$script:failWithdrawalBeforeCommit=$false;$script:cleanupPolicyStillAllowed=$false
  $script:state=[ordered]@{fixtureWritesStarted=$true;grantAttempted=$true;events=@();failure='ORIGINAL_FAILURE_PRESERVED'}
}
function Api {
  param($Method,$Path,$Body)
  $script:cleanupPaths+=,$Path
  if($Method -ceq 'GET' -and $Path -ceq "/students/$StudentId"){return $script:cleanupStudent}
  if($Method -ceq 'GET' -and $Path -ceq "/students/$StudentId/guardians"){
    return [pscustomobject]@{studentId=$StudentId;schoolId=1;guardians=@($script:cleanupGuardian);consents=@($script:cleanupConsent|Where-Object {$null -ne $_})}
  }
  if($Method -ceq 'POST' -and $Path -ceq "/students/$StudentId/consents"){
    if($Body.status -cne 'WITHDRAWN' -or $Body.guardianId -cne 'guardian-owned' -or $Body.idempotencyKey -cne "$RunId`:cleanup-withdraw"){throw 'Cleanup used wrong fixture or unstable withdrawal key'}
    $script:withdrawals++
    if($script:failWithdrawalBeforeCommit){throw 'Sanitized withdrawal failure'}
    $script:cleanupConsent.status='WITHDRAWN';$script:cleanupConsent.id='withdraw-owned'
    if($script:loseWithdrawalResponse){throw 'Sanitized lost withdrawal response'}
    return [pscustomobject]@{studentId=$StudentId;schoolId=1;consents=@($script:cleanupConsent)}
  }
  throw 'Unexpected cleanup route'
}
function Read-OwnedPolicy {
  param($Id,[bool]$RequireAllowed=$true,[bool]$RequireDenied=$false)
  if($RequireAllowed -or -not $RequireDenied){throw 'Cleanup did not require denied policy'}
  $script:policyDeniedChecks++
  if($script:cleanupPolicyStillAllowed){throw 'Synthetic fixture is still eligible after cleanup'}
}
Cleanup-Fixtures
$cleaned=Cleanup-OwnedFixture
if(-not $cleaned.confirmed -or $script:withdrawals -ne 1 -or $state.guardianId -cne 'guardian-owned' -or $cleaned.withdrawnConsentId -cne 'withdraw-owned' -or $script:policyDeniedChecks -ne 1 -or $state.failure -cne 'ORIGINAL_FAILURE_PRESERVED'){throw 'Uncertain creation/grant was not reconciled and withdrawn'};$script:cases++
# Exact ID is deliberately absent above, as after a lost guardian-create response.
Cleanup-Fixtures;$script:loseWithdrawalResponse=$true
$cleaned=Cleanup-OwnedFixture
if(-not $cleaned.confirmed -or $cleaned.withdrawalResponseConfirmed -ne $false -or $cleaned.withdrawnConsentId -cne 'withdraw-owned'){throw 'Lost withdrawal response did not reconcile authoritative state'};$script:cases++
Cleanup-Fixtures;$script:cleanupConsent.status='WITHDRAWN';$script:cleanupConsent.id='existing-withdrawal'
$cleaned=Cleanup-OwnedFixture
if(-not $cleaned.confirmed -or $script:withdrawals -ne 0 -or $script:policyDeniedChecks -ne 1){throw 'Already withdrawn cleanup should be read-only'};$script:cases++
foreach($case in @('wrong-student','wrong-guardian','changed-id','foreign-consent','missing-uncertain-grant','withdrawal-not-recorded','policy-still-allowed')){
  Cleanup-Fixtures
  switch($case){
    wrong-student {$script:cleanupStudent.fullName='Unrelated student'}
    wrong-guardian {$script:cleanupGuardian.email='someone@example.com'}
    changed-id {$state.guardianId='another-id'}
    foreign-consent {$script:cleanupConsent.evidenceReference='another-run'}
    missing-uncertain-grant {$script:cleanupConsent=$null}
    withdrawal-not-recorded {$script:failWithdrawalBeforeCommit=$true}
    policy-still-allowed {$script:cleanupPolicyStillAllowed=$true}
  }
  $cleaned=Cleanup-OwnedFixture
  if($cleaned.confirmed -or -not $cleaned.failure -or $state.failure -cne 'ORIGINAL_FAILURE_PRESERVED'){throw "Cleanup falsely completed or lost original error: $case"}
  if($case -notin @('withdrawal-not-recorded','policy-still-allowed') -and $script:withdrawals -ne 0){throw "Cleanup mutated an unowned/uncertain scope: $case"};$script:cases++
}
Cleanup-Fixtures;$state.grantAttempted=$false;$script:cleanupConsent=$null
$cleaned=Cleanup-OwnedFixture
if(-not $cleaned.confirmed -or $script:withdrawals -ne 0 -or $script:policyDeniedChecks -ne 1){throw 'Created guardian with no attempted grant did not confirm denied policy'};$script:cases++

# The real entry point must refuse an existing journal before credentials/network are accessed.
$journalDirectory=Join-Path $repoRoot 'artifacts/product-dev-release-2026-09-26'
if(-not(Test-Path -LiteralPath $journalDirectory)){New-Item -ItemType Directory -Path $journalDirectory|Out-Null}
$journal=Join-Path $journalDirectory "$RunId-broadcast-journal.json"
$stream=[IO.File]::Open($journal,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
try{$bytes=[Text.Encoding]::UTF8.GetBytes('fixture-no-overwrite');$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
try{
  $ErrorActionPreference='Continue'
  $result=& powershell -NoProfile -ExecutionPolicy Bypass -File $file -ProjectId custoking-dev -StudentId $StudentId -RunId $RunId -Apply 2>&1
  $ErrorActionPreference='Stop'
  if($LASTEXITCODE -eq 0 -or [IO.File]::ReadAllText($journal) -cne 'fixture-no-overwrite'){throw 'Existing journal was not protected'};$script:cases++
}finally{Remove-Item -LiteralPath $journal -Force}
$plan=& powershell -NoProfile -ExecutionPolicy Bypass -File $file -ProjectId custoking-dev -StudentId $StudentId -RunId $RunId|ConvertFrom-Json
if($LASTEXITCODE -ne 0 -or $plan.mode -ne 'DRY_RUN_ONLY' -or (Test-Path -LiteralPath $journal)){throw 'Plan-only entry point wrote a journal or failed'};$script:cases++
$ErrorActionPreference='Continue'
$rejected=& powershell -NoProfile -ExecutionPolicy Bypass -File $file -ProjectId custoking-prod -StudentId $StudentId -RunId $RunId -Apply 2>&1
$ErrorActionPreference='Stop'
if($LASTEXITCODE -eq 0 -or (Test-Path -LiteralPath $journal)){throw 'Production invocation was not rejected before work'};$script:cases++
Write-Output "PASS: $script:cases offline policy binding, recipient ownership, confidentiality, budget, Scheduler, and no-overwrite checks; no network calls."

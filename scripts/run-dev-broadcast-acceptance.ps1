param(
  [ValidateSet('custoking-dev')][string]$ProjectId='custoking-dev',
  [Parameter(Mandatory=$true)][ValidateRange(1,[long]::MaxValue)][long]$StudentId,
  [Parameter(Mandatory=$true)][ValidatePattern('^product-[0-9]{8}-[a-z0-9]{2,20}$')][string]$RunId,
  [ValidatePattern('^[a-z][a-z0-9-]+@custoking-dev\.iam\.gserviceaccount\.com$')][string]$PolicyInvokerServiceAccount,
  [ValidateSet('PrivatePolicy','PublicManifest')][string]$PolicyEvidenceSource='PrivatePolicy',
  [switch]$Apply
)
# No Apply: local plan only. Apply: bounded dev-only synthetic fixture mutations, verified policy
# reads/approved manifests, and natural Scheduler ticks. Never invokes a drain or external provider.
$ErrorActionPreference='Stop'
$project=$ProjectId; $base='https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app/api/v1'
$repoRoot=Split-Path -Parent $PSScriptRoot
$journalDirectory=Join-Path $repoRoot 'artifacts/product-dev-release-2026-09-26'
$journalPath=Join-Path $journalDirectory "$RunId-broadcast-journal.json"
if (-not $Apply) { [ordered]@{project=$project;schoolId=1;studentId=$StudentId;runId=$RunId;mode='DRY_RUN_ONLY';policyEvidenceSource=$PolicyEvidenceSource;guardianEmail="$($RunId.ToLowerInvariant())@acceptance.invalid";phases=@('verify owned student and empty guardian scope','verify selected policy evidence path for the owned fixture','create synthetic-only verified guardian and test consent','approve exactly one eligible recipient and validate its stored manifest','observe DRY_RUN with zero delivered and an actual natural Scheduler tick','replay queue without additional attempt','withdraw consent before second queue and observe suppression plus a natural Scheduler tick','reconcile exact owned fixture, withdraw any remaining test grant, verify denied policy, and log out');maxRequests=65;businessRequestLimit=55;cleanupRequestReserve=10;cleanupMinutes=3;maxCloudReads=30;maxRecipients=200;maxMinutes=12} | ConvertTo-Json; exit }
if (Test-Path -LiteralPath $journalPath) {throw 'Journal already exists. Reconcile recorded identifiers before any retry; no automatic restart.'}
if (-not (Test-Path -LiteralPath $journalDirectory)) {New-Item -ItemType Directory -Path $journalDirectory -Force|Out-Null}
$state=[ordered]@{project=$project;schoolId=1;studentId=$StudentId;runId=$RunId;startedAt=[datetime]::UtcNow.ToString('o');events=@();completed=$false;deliveryMode='DRY_RUN_ONLY'}
$session=New-Object Microsoft.PowerShell.Commands.WebRequestSession; $headers=@{}; $policyHeaders=@{}; $authenticated=$false; $count=0; $cloudCount=0; $deadline=[datetime]::UtcNow.AddMinutes(12)
$cleanupMode=$false;$cleanupCount=0;$cleanupDeadline=$null
# Hold the newly created journal open exclusively for writing for the entire run. A competing
# invocation cannot race the existence check, overwrite it, or proceed with its own mutations.
$journalStream=[IO.File]::Open($journalPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
function Save-Journal {
  $bytes=[Text.Encoding]::UTF8.GetBytes(($state|ConvertTo-Json -Depth 14))
  $journalStream.Position=0;$journalStream.SetLength(0);$journalStream.Write($bytes,0,$bytes.Length);$journalStream.Flush($true)
}
function Remaining-Seconds {
  $until=if($script:cleanupMode){$script:cleanupDeadline}else{$deadline}
  return [int][Math]::Floor(($until-[datetime]::UtcNow).TotalSeconds)
}
function Reserve-HttpRequest([bool]$Logout=$false) {
  if($script:cleanupMode){
    $limit=if($Logout){10}else{9}
    Assert ($script:cleanupCount -lt $limit -and $script:count -lt 65 -and (Remaining-Seconds) -gt $(if($Logout){0}else{12})) 'Bounded cleanup request or duration limit reached'
    $script:cleanupCount++
  } else {
    Assert ($script:count -lt 55 -and (Remaining-Seconds) -gt 12) 'Bounded request or duration limit reached'
  }
  $script:count++
}
function Read-Gcloud([string[]]$Arguments) {
  Assert ($script:cloudCount -lt 30 -and (Remaining-Seconds) -gt 15) 'Bounded cloud-read or duration limit reached';$script:cloudCount++
  Assert ($Arguments -contains '--project=custoking-dev') 'Every cloud read must explicitly name custoking-dev'
  $job=$null
  try {
    $job=Start-Job -ScriptBlock { param($a) $env:CLOUDSDK_CORE_HTTP_TIMEOUT='15';$output=(& gcloud.cmd @a 2>$null)-join "`n";[pscustomobject]@{code=$LASTEXITCODE;output=$output} } -ArgumentList (,$Arguments)
    $finished=Wait-Job $job -Timeout ([Math]::Min(30,(Remaining-Seconds)-10))
    Assert ($null -ne $finished) 'Dev cloud read timed out; acceptance stopped'
    $answer=Receive-Job $job -ErrorAction SilentlyContinue
    Assert ($answer.code -eq 0 -and -not [string]::IsNullOrWhiteSpace($answer.output)) 'Dev cloud evidence or credential unavailable'
    return [string]$answer.output
  } finally { if($job){Stop-Job $job -ErrorAction SilentlyContinue;Remove-Job $job -Force -ErrorAction SilentlyContinue} }
}
function Api([string]$Method,[string]$Path,$Body=$null) {
  $logout=$Path -eq '/auth/logout'
  Reserve-HttpRequest $logout
  $event=[ordered]@{method=$Method;path=$Path;at=[datetime]::UtcNow.ToString('o');status='PENDING'}
  if ($Method -ne 'GET' -and $Path -notlike '/auth/*') {$event.body=$Body}
  if($Path -eq '/auth/login'){$state.loginAttempted=$true}
  $state.events+=,$event; Save-Journal
  $args=@{Method=$Method;Uri="$base$Path";WebSession=$session;Headers=$headers;TimeoutSec=([Math]::Min(30,(Remaining-Seconds)-$(if($logout){0}else{10})));MaximumRedirection=0;UseBasicParsing=$true}
  if ($null -ne $Body) {$args.Body=ConvertTo-Json -InputObject $Body -Depth 8 -Compress;$args.ContentType='application/json'}
  try {$response=Invoke-WebRequest @args;$event.status=[int]$response.StatusCode;Save-Journal; if($response.Content){return($response.Content|ConvertFrom-Json)}}
  catch {$code=0;if($_.Exception.Response){$code=[int]$_.Exception.Response.StatusCode};$event.status=$code;Save-Journal;throw "Acceptance stopped at $Method $Path (HTTP $code). No response body or credential is reported."}
}
function Secret([string]$Name) {return (Read-Gcloud @('secrets','versions','access','latest',"--secret=$Name",'--project=custoking-dev')).Trim()}
function Assert([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message}}
function Assert-DevServiceUrl([string]$Url,[ValidateSet('platform','school-core')][string]$Service){
  # Cloud Run exposes both hash-based *.a.run.app and project-number regional URLs.
  # Metadata is read from the explicit dev project; never accept another service or a URL suffix/path.
  $pattern='^https://custoking-'+[regex]::Escape($Service)+'-service-dev-(?:[a-z0-9]+-[a-z0-9]+\.a|1087017280590\.asia-south2)\.run\.app$'
  Assert ($Url -cmatch $pattern) "Unexpected $Service runtime URL"
}
function Read-GcloudJson([string[]]$Arguments) {
  $raw=Read-Gcloud $Arguments
  try { return ($raw|ConvertFrom-Json) } catch { throw 'Dev cloud read returned malformed JSON; no raw contents are reported' }
}
function Read-OwnedPublicPolicy([string]$Id,[bool]$RequireAllowed,[bool]$RequireDenied) {
  $overview=Api GET "/students/$StudentId/guardians"
  Assert ($overview.studentId -eq $StudentId -and $overview.schoolId -eq 1 -and $null -ne $overview.guardians) 'Public policy fixture scope mismatch'
  if(-not $state.fixtureWritesStarted){
    Assert (-not $RequireAllowed -and -not $RequireDenied -and @($overview.guardians).Count -eq 0 -and @($overview.consentHistory).Count -eq 0) 'Public policy preflight requires an empty owned fixture'
    return
  }
  $guardians=@($overview.guardians)
  Assert ($guardians.Count -eq 1 -and $guardians[0].id -ceq $state.guardianId -and $guardians[0].fullName -ceq "Synthetic Guardian $RunId" -and $guardians[0].email -ceq "$($RunId.ToLowerInvariant())@acceptance.invalid" -and $guardians[0].relationship -ceq 'GUARDIAN' -and $guardians[0].primary -eq $true -and $guardians[0].receivesNotifications -eq $true -and $guardians[0].contactVerifiedAt -and $guardians[0].status -ceq 'ACTIVE' -and -not $guardians[0].phone) 'Public policy guardian binding is not exclusively synthetic'
  $current=@($overview.consents|Where-Object {$_.purpose -ceq 'SCHOOL_COMMUNICATIONS'})
  Assert ($current.Count -eq 1 -and $current[0].guardianId -ceq $state.guardianId -and $current[0].evidenceReference -ceq $RunId -and $current[0].noticeVersion -ceq 'synthetic-acceptance-v1') 'Public policy consent binding differs from the owned fixture'
  if($RequireAllowed){Assert ($current[0].status -ceq 'GRANTED' -and $current[0].id -ceq $state.grantedConsentId) 'Public policy grant is not current'}
  if($RequireDenied){Assert ($current[0].status -ceq 'WITHDRAWN') 'Public policy withdrawal is not current'}
  Assert ($RequireAllowed -xor $RequireDenied) 'Public policy evidence must require an explicit decision'
  $proofId=if($RequireDenied){if($state.broadcastrevoked){$state.broadcastrevoked}else{$state.broadcastdryrun}}else{$Id}
  Assert ($proofId -and $proofId -in @($state.broadcastdryrun,$state.broadcastrevoked)) 'Public policy needs an already journaled owned broadcast; cleanup remains unresolved without one'
  $preview=Api POST "/notifications/broadcasts/$proofId/preview"
  $expectedEligible=if($RequireAllowed){1}else{0}
  Assert ($preview.broadcastId -ceq $proofId -and $preview.eligible -eq $expectedEligible -and $preview.duplicate -eq 0 -and $preview.total -ge 1 -and $preview.total -le 200 -and $preview.suppressed -eq ($preview.total-$expectedEligible) -and $preview.fingerprint -match '^[0-9a-f]{64}$') 'Fresh public preview does not prove the required synthetic policy outcome'
  # Approval still requires Assert-OwnedManifest before queueing: aggregates alone never authorize a send.
  $state.publicPolicyEvidence+=,@{broadcastId=$proofId;eligible=$preview.eligible;fixtureGuardianAndConsentBound=$true};Save-Journal
}
function Read-OwnedPolicy([string]$Id,[bool]$RequireAllowed=$true,[bool]$RequireDenied=$false) {
  if($PolicyEvidenceSource -ceq 'PublicManifest'){Read-OwnedPublicPolicy $Id $RequireAllowed $RequireDenied;return}
  Reserve-HttpRequest
  $body=@{schoolId=1;broadcastId=$Id;communicationCategory='SCHOOL_NOTICE';audienceType='ALL_PARENTS';channels=@('EMAIL');studentIds=@($StudentId)}
  $event=[ordered]@{method='POST';path='/api/v1/internal/notifications/broadcast-recipients';studentId=$StudentId;at=[datetime]::UtcNow.ToString('o');status='PENDING'}
  $state.events+=,$event;Save-Journal
  try {
    $response=Invoke-WebRequest -Method POST -Uri "$schoolUrl/api/v1/internal/notifications/broadcast-recipients" -Headers $policyHeaders -Body ($body|ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec ([Math]::Min(20,(Remaining-Seconds)-10)) -MaximumRedirection 0 -UseBasicParsing
    $event.status=[int]$response.StatusCode;Save-Journal
    $rows=@($response.Content|ConvertFrom-Json|ForEach-Object {$_})
  } catch {$event.status='FAILED';Save-Journal;throw 'Scoped private policy read failed. No response body or credentials are reported; approval is blocked.'}
  Assert ($rows.Count -eq 1 -and $rows[0].studentId -eq $StudentId -and $rows[0].schoolId -eq 1 -and $rows[0].channel -ceq 'EMAIL' -and $rows[0].eventId -ceq "broadcast:$Id`:$StudentId`:EMAIL") 'Private policy response is outside the exact synthetic scope'
  if($RequireAllowed){
    $r=$rows[0]
    Assert ($r.allowed -is [bool] -and $r.allowed -and $r.reason -ceq 'ALLOWED' -and $r.guardianId -ceq $state.guardianId -and $r.destination -ceq "$($RunId.ToLowerInvariant())@acceptance.invalid") 'Synthetic guardian is not the eligible policy recipient'
    Assert ($r.policyEvidence.consentEventId -ceq $state.grantedConsentId -and $r.policyEvidence.guardianId -ceq $state.guardianId) 'Current policy consent binding differs from the test grant'
  }
  if($RequireDenied){Assert ($rows[0].allowed -is [bool] -and -not $rows[0].allowed) 'Synthetic fixture is still eligible after cleanup; manual reconciliation is required'}
  # Only booleans/known test identifiers enter the journal; no policy destination/evidence is retained.
}
function Consent([string]$Status,[string]$Key){
  if($Status -ceq 'GRANTED'){$state.grantAttempted=$true;Save-Journal}
  $answer=Api POST "/students/$StudentId/consents" @{guardianId=$state.guardianId;purpose='SCHOOL_COMMUNICATIONS';status=$Status;noticeVersion='synthetic-acceptance-v1';lawfulBasis='CONSENT';evidenceSource='OTHER';evidenceReference=$RunId;notes='Synthetic fixture only. Invalid email domain. No real person or external delivery.';idempotencyKey="$RunId`:$Key"}
  $consent=@($answer.consents|Where-Object {$_.purpose -ceq 'SCHOOL_COMMUNICATIONS'})
  Assert ($answer.studentId -eq $StudentId -and $answer.schoolId -eq 1 -and $consent.Count -eq 1 -and $consent[0].status -ceq $Status -and $consent[0].guardianId -ceq $state.guardianId -and $consent[0].evidenceReference -ceq $RunId -and $consent[0].id) 'Consent result does not confirm the exact requested synthetic decision'
  if($Status -ceq 'GRANTED'){$state.grantedConsentId=$consent[0].id}else{$state.withdrawnConsentId=$consent[0].id};Save-Journal
}
function Cleanup-OwnedFixture {
  $cleanupKey=if($state.cleanupConsentKey){[string]$state.cleanupConsentKey}else{'cleanup-withdraw'}
  $cleanup=[ordered]@{attempted=[bool]$state.fixtureWritesStarted;confirmed=$false;withdrawalKey="$RunId`:$cleanupKey"}
  if(-not $state.fixtureWritesStarted){$cleanup.confirmed=$true;$cleanup.result='NO_FIXTURE_WRITES_ATTEMPTED';return $cleanup}
  try {
    $student=Api GET "/students/$StudentId/workspace"
    Assert ($student.id -eq $StudentId -and $student.schoolId -eq 1 -and $student.admissionNumber -ceq "QA-$RunId" -and $student.fullName -ceq "Synthetic Student $RunId" -and $student.sectionName -ceq "QA-$($RunId.ToUpperInvariant())") 'Cleanup refused: student ownership could not be verified'
    $overview=Api GET "/students/$StudentId/guardians"
    Assert ($overview.studentId -eq $StudentId -and $overview.schoolId -eq 1 -and $null -ne $overview.guardians) 'Cleanup refused: guardian scope could not be verified'
    $owned=@($overview.guardians|Where-Object {$_.fullName -ceq "Synthetic Guardian $RunId" -and $_.email -ceq "$($RunId.ToLowerInvariant())@acceptance.invalid" -and $_.relationship -ceq 'GUARDIAN' -and -not $_.phone})
    Assert (@($overview.guardians).Count -eq 1 -and $owned.Count -eq 1 -and $owned[0].id) 'Cleanup unresolved: exact synthetic guardian creation could not be reconciled; no other guardian was changed'
    if($state.guardianId){Assert ($owned[0].id -ceq $state.guardianId) 'Cleanup refused: guardian identifier changed'}
    $state.guardianId=$owned[0].id;$cleanup.guardianId=$state.guardianId;Save-Journal
    $current=@($overview.consents|Where-Object {$_.purpose -ceq 'SCHOOL_COMMUNICATIONS'})
    Assert ($current.Count -le 1) 'Cleanup refused: ambiguous current communication decision'
    if($current.Count -eq 0){
      Assert (-not $state.grantAttempted) 'Cleanup unresolved: grant response was uncertain and no current decision was found; retain the journal for reconciliation'
      Read-OwnedPolicy ([guid]::NewGuid().ToString()) $false $true
      $cleanup.confirmed=$true;$cleanup.result='NO_GRANT_ATTEMPTED_POLICY_DENIED';return $cleanup
    }
    $decision=$current[0]
    Assert ($decision.guardianId -ceq $state.guardianId -and $decision.evidenceReference -ceq $RunId -and $decision.noticeVersion -ceq 'synthetic-acceptance-v1' -and $decision.lawfulBasis -ceq 'CONSENT') 'Cleanup refused: current consent is not the exact synthetic decision'
    Assert ($decision.status -in @('GRANTED','WITHDRAWN')) 'Cleanup unresolved: unexpected synthetic consent status'
    if($decision.status -ceq 'GRANTED'){
      # A timed-out grant may have committed; the authoritative overview, not the response, decides.
      # Stable key also makes a lost withdrawal response safe for explicit later reconciliation.
      $cleanup.withdrawalAttempted=$true
      try{Consent 'WITHDRAWN' $cleanupKey;$cleanup.withdrawalResponseConfirmed=$true}
      catch{$cleanup.withdrawalResponseConfirmed=$false}
      $overview=Api GET "/students/$StudentId/guardians"
      Assert ($overview.studentId -eq $StudentId -and $overview.schoolId -eq 1) 'Cleanup withdrawal scope could not be verified'
      $current=@($overview.consents|Where-Object {$_.purpose -ceq 'SCHOOL_COMMUNICATIONS'})
      Assert ($current.Count -eq 1) 'Cleanup withdrawal has no authoritative current decision'
      $decision=$current[0]
    }
    Assert ($decision.status -ceq 'WITHDRAWN' -and $decision.guardianId -ceq $state.guardianId -and $decision.evidenceReference -ceq $RunId -and $decision.noticeVersion -ceq 'synthetic-acceptance-v1' -and $decision.id) 'Synthetic grant withdrawal was not confirmed; manual reconciliation is required'
    $cleanup.withdrawnConsentId=$decision.id
    Read-OwnedPolicy ([guid]::NewGuid().ToString()) $false $true
    $cleanup.confirmed=$true;$cleanup.policyDenied=$true;$cleanup.result='WITHDRAWN_AND_POLICY_DENIED'
  } catch {
    # Only fixed assertions and the API's sanitized errors enter this report.
    $cleanup.failure=$_.Exception.Message
  }
  return $cleanup
}
function New-ReviewedBroadcast([string]$Suffix){
  $draft=Api POST '/notifications/broadcasts' @{title="SYNTHETIC $RunId $Suffix";message='Development dry-run acceptance only. Do not deliver.';schoolId=1;audienceType='ALL_PARENTS';channels=@('EMAIL');communicationCategory='SCHOOL_NOTICE'}
  Assert ($draft.id -match '^[0-9a-f-]{36}$' -and $draft.schoolId -eq 1 -and $draft.status -ceq 'DRAFT' -and $draft.title -ceq "SYNTHETIC $RunId $Suffix" -and @($draft.channels).Count -eq 1 -and $draft.channels[0] -ceq 'EMAIL') 'Draft scope or identifier mismatch'
  $state["broadcast$Suffix"]=$draft.id; Save-Journal
  $preview=Api POST "/notifications/broadcasts/$($draft.id)/preview"
  Assert ($preview.broadcastId -ceq $draft.id -and $preview.eligible -eq 1 -and $preview.duplicate -eq 0 -and $preview.total -ge 1 -and $preview.total -le 200 -and $preview.suppressed -eq ($preview.total-1) -and $preview.fingerprint -match '^[0-9a-f]{64}$') 'Exactly one eligible recipient and at most 200 total are required; other eligible or duplicate recipients block approval.'
  Read-OwnedPolicy ([string]$draft.id)
  $approved=Api POST "/notifications/broadcasts/$($draft.id)/approve" @{previewFingerprint=$preview.fingerprint}
  Assert ($approved.id -ceq $draft.id -and $approved.schoolId -eq 1 -and $approved.status -ceq 'APPROVED') 'Broadcast approval response mismatch'
  $manifest=Api GET "/notifications/broadcasts/$($draft.id)/delivery-status"
  Assert-OwnedManifest $manifest ([string]$draft.id) 'APPROVED'
  return [string]$draft.id
}
function Assert-OwnedManifest($Result,[string]$Id,[string]$OwnedStatus) {
  $owned=@($Result.recipients|Where-Object {$_.studentId -eq $StudentId -and $_.channel -ceq 'EMAIL'})
  Assert ($Result.broadcastId -ceq $Id -and $Result.total -eq @($Result.recipients).Count -and $Result.total -ge 1 -and $Result.total -le 200 -and $Result.delivered -eq 0 -and $owned.Count -eq 1 -and $owned[0].status -ceq $OwnedStatus) 'Approved/terminal manifest does not bind exactly the synthetic recipient'
  if($OwnedStatus -ceq 'APPROVED'){Assert ($Result.status -ceq 'APPROVED' -and $owned[0].attempts -eq 0) 'Unexpected prior attempt in the approved manifest'}
  Assert (@($Result.recipients|Where-Object {$_.studentId -ne $StudentId -and ($_.status -cne 'SUPPRESSED' -or $_.attempts -ne 0)}).Count -eq 0) 'An ordinary recipient was approved or attempted; queuing is blocked'
  Assert (@($Result.recipients|Where-Object {$_.studentId -eq $StudentId -and $_.channel -cne 'EMAIL'}).Count -eq 0) 'Unexpected synthetic delivery channel'
}
function Wait-Outcome([string]$Id,[string]$Expected){
  for($i=0;$i -lt 10;$i++){
    $result=Api GET "/notifications/broadcasts/$Id/delivery-status"
    Assert ($result.broadcastId -ceq $Id -and $result.mode -ceq 'DRY_RUN' -and $result.delivered -eq 0) 'Unexpected broadcast, mode or delivery claim'
    $owned=@($result.recipients|Where-Object {$_.studentId -eq $StudentId -and $_.channel -eq 'EMAIL'})
    if($result.status -eq 'DRY_RUN_COMPLETE'){
      Assert ($owned.Count -eq 1 -and $owned[0].status -ceq $Expected -and $owned[0].attempts -eq 1 -and $owned[0].dryRun -is [bool] -and $owned[0].dryRun) 'Synthetic recipient outcome/attempt count mismatch'
      Assert-OwnedManifest $result $Id $Expected
      Assert ($Expected -cne 'DRY_RUN' -or $owned[0].provider -ceq 'logging') 'Unexpected dry-run provider'
      return [ordered]@{broadcastId=$Id;status=$result.status;mode=$result.mode;delivered=$result.delivered;counts=$result.counts;syntheticStatus=$owned[0].status;syntheticAttempts=$owned[0].attempts;syntheticReason=$owned[0].reason}
    }
    Assert (@($result.recipients|Where-Object {$_.status -in @('FAILED','DEAD_LETTER')}).Count -eq 0) 'Dry-run worker failed; inspect safe server evidence before retry'
    Assert ((Remaining-Seconds) -gt 24) 'Duration guard reached before Scheduler observation completed'
    Start-Sleep -Seconds 12
  }
  throw 'Scheduler did not complete the bounded dry-run observation window'
}
function Read-DevLogEntries([string]$Filter) {
  # Windows native argument passing can strip the quotes in timestamp/URL filters.
  # A structured Logging request preserves the filter and the explicit dev resource scope.
  $logAccessToken=Read-Gcloud @('auth','print-access-token','--project=custoking-dev')
  Assert ($script:cloudCount -lt 30 -and (Remaining-Seconds) -gt 15) 'Bounded cloud-read or duration limit reached';$script:cloudCount++
  try {
    $body=@{resourceNames=@('projects/custoking-dev');filter=$Filter;orderBy='timestamp asc';pageSize=30}|ConvertTo-Json -Compress
    $answer=Invoke-RestMethod -Method POST -Uri 'https://logging.googleapis.com/v2/entries:list' -Headers @{Authorization="Bearer $($logAccessToken.Trim())"} -Body $body -ContentType 'application/json' -TimeoutSec ([Math]::Min(20,(Remaining-Seconds)-10))
    return @($answer.entries)
  } catch {throw 'Dev log evidence read failed; no response body or credential is reported'}
  finally {$logAccessToken=$null;$body=$null;$answer=$null}
}
function Wait-SchedulerTick([string]$Since) {
  $drainUrl="$platformUrl/api/v1/internal/async/drain"
  $jobName='projects/custoking-dev/locations/asia-south1/jobs/ims-platform-service-async-relay-dev'
  $filter='timestamp>="'+$Since+'" AND ((resource.type="cloud_scheduler_job" AND resource.labels.job_id="ims-platform-service-async-relay-dev") OR (resource.type="cloud_run_revision" AND resource.labels.service_name="custoking-platform-service-dev" AND httpRequest.requestUrl="'+$drainUrl+'" AND httpRequest.userAgent:"Google-Cloud-Scheduler"))'
  for($i=0;$i -lt 4;$i++){
    $logs=@(Read-DevLogEntries $filter)
    $scheduler=@($logs|Where-Object {$_.resource.type -ceq 'cloud_scheduler_job' -and $_.resource.labels.project_id -ceq 'custoking-dev' -and $_.resource.labels.location -ceq 'asia-south1' -and $_.resource.labels.job_id -ceq 'ims-platform-service-async-relay-dev' -and $_.jsonPayload.'@type' -ceq 'type.googleapis.com/google.cloud.scheduler.logging.AttemptFinished' -and $_.jsonPayload.jobName -ceq $jobName -and $_.jsonPayload.url -ceq $drainUrl -and $_.jsonPayload.targetType -ceq 'HTTP' -and $_.httpRequest.status -ge 200 -and $_.httpRequest.status -lt 300 -and [datetime]$_.timestamp -ge [datetime]$Since}|Select-Object -First 1)
    $requests=@($logs|Where-Object {$_.resource.type -ceq 'cloud_run_revision' -and $_.resource.labels.project_id -ceq 'custoking-dev' -and $_.resource.labels.service_name -ceq 'custoking-platform-service-dev' -and $_.resource.labels.revision_name -ceq $state.platformRevision -and $_.httpRequest.requestUrl -ceq $drainUrl -and $_.httpRequest.userAgent -like '*Google-Cloud-Scheduler*' -and $_.httpRequest.requestMethod -ceq 'POST' -and $_.httpRequest.status -ge 200 -and $_.httpRequest.status -lt 300 -and [datetime]$_.timestamp -ge [datetime]$Since}|Select-Object -First 1)
    if($scheduler.Count -eq 1 -and $requests.Count -eq 1){return [ordered]@{after=$Since;job=$jobName;schedulerInsertId=$scheduler[0].insertId;schedulerAt=$scheduler[0].timestamp;schedulerStatus=$scheduler[0].httpRequest.status;requestInsertId=$requests[0].insertId;requestAt=$requests[0].timestamp;requestStatus=$requests[0].httpRequest.status;revision=$requests[0].resource.labels.revision_name}}
    Assert ((Remaining-Seconds) -gt 30) 'Duration guard reached before natural Scheduler evidence arrived'
    if($i -lt 3){Start-Sleep -Seconds 20}
  }
  throw 'No paired successful natural Scheduler tick after queue acceptance; result remains incomplete'
}
function Queue-Owned([string]$Id) {
  $answer=Api POST "/notifications/broadcasts/$Id/send" @{}
  Assert ($answer.broadcastId -ceq $Id -and $answer.mode -ceq 'DRY_RUN' -and $answer.delivered -eq 0 -and $answer.status -in @('QUEUED','DRY_RUN_COMPLETE')) 'Queue response does not confirm the expected dry-run broadcast'
  return [datetime]::UtcNow.ToString('o')
}
Save-Journal
try {
  $runtime=Read-GcloudJson @('run','services','describe','custoking-platform-service-dev','--project=custoking-dev','--region=asia-south2','--format=json')
  $state.platformRevision=[string]$runtime.status.latestReadyRevisionName
  Assert ($state.platformRevision -like 'custoking-platform-service-dev-*' -and $state.platformRevision -ceq $runtime.status.latestCreatedRevisionName -and @($runtime.status.traffic).Count -eq 1 -and $runtime.status.traffic[0].revisionName -ceq $state.platformRevision -and $runtime.status.traffic[0].percent -eq 100) 'Platform must have a single fully ready serving revision'
  $platformUrl=[string]$runtime.status.url
  Assert-DevServiceUrl $platformUrl 'platform'
  $revision=Read-GcloudJson @('run','revisions','describe',$state.platformRevision,'--project=custoking-dev','--region=asia-south2','--format=json')
  $envs=@{};foreach($e in $revision.spec.containers[0].env){$envs[$e.name]=$e.value}
  Assert ($envs.BROADCAST_DISPATCH_MODE -eq 'DRY_RUN' -and $envs.BROADCAST_WORKER_READY -eq 'true' -and $envs.NOTIFICATION_DELIVERY_PROVIDER -eq 'logging' -and $envs.MSG91_DRY_RUN -eq 'true') 'Runtime must explicitly enable verified dry-run with logging'
  $scheduler=Read-GcloudJson @('scheduler','jobs','describe','ims-platform-service-async-relay-dev','--project=custoking-dev','--location=asia-south1','--format=json')
  Assert ($scheduler.state -ceq 'ENABLED' -and $scheduler.httpTarget.httpMethod -ceq 'POST' -and $scheduler.httpTarget.uri -ceq "$platformUrl/api/v1/internal/async/drain" -and $scheduler.httpTarget.oidcToken.audience -ceq $platformUrl -and $scheduler.httpTarget.oidcToken.serviceAccountEmail -ceq 'ims-async-scheduler-dev@custoking-dev.iam.gserviceaccount.com') 'Scheduler target, authentication or enabled state mismatch'
  $state.policyEvidenceSource=$PolicyEvidenceSource;Save-Journal
  if($PolicyEvidenceSource -ceq 'PrivatePolicy'){
    $schoolRuntime=Read-GcloudJson @('run','services','describe','custoking-school-core-service-dev','--project=custoking-dev','--region=asia-south2','--format=json')
    $schoolUrl=[string]$schoolRuntime.status.url;Assert-DevServiceUrl $schoolUrl 'school-core'
    $identityArgs=@('auth','print-identity-token','--project=custoking-dev')
    if($PolicyInvokerServiceAccount){$identityArgs+=@("--impersonate-service-account=$PolicyInvokerServiceAccount","--audiences=$schoolUrl",'--include-email')}
    $policyHeaders.Authorization='Bearer '+(Read-Gcloud $identityArgs).Trim();$policyHeaders['X-Broadcast-Policy-Token']=Secret 'broadcast-policy-token-dev'
  }
  $seed=Secret 'seed-superadmin-sql';$emails=@([regex]::Matches($seed,'[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}')|ForEach-Object {$_.Value.ToLowerInvariant()}|Sort-Object -Unique);$seed=$null
  Assert ($emails.Count -eq 1) 'Bootstrap account ambiguous'
  $password=Secret 'superadmin-password-dev';$login=Api POST '/auth/login' @{email=$emails[0];password=$password};$password=$null;$emails=$null
  $authenticated=$true
  Assert ($login.role -eq 'SUPERADMIN' -and $login.accessToken) 'Unexpected bootstrap actor'
  $headers.Authorization="Bearer $($login.accessToken)";$login=$null
  $cap=Api GET '/notifications/broadcasts/capabilities';Assert ($cap.mode -ceq 'DRY_RUN' -and $cap.canQueue -eq $true -and $cap.canSend -eq $false -and $cap.canApprove -eq $true -and $cap.canPreview -eq $true) 'Capabilities do not allow dry-run-only queuing'
  $student=Api GET "/students/$StudentId/workspace";Assert ($student.id -eq $StudentId -and $student.schoolId -eq 1 -and $student.admissionNumber -ceq "QA-$RunId" -and $student.fullName -ceq "Synthetic Student $RunId" -and $student.sectionName -ceq "QA-$($RunId.ToUpperInvariant())") 'Student is not owned by this acceptance run';$student=$null
  Read-OwnedPolicy ([guid]::NewGuid().ToString()) $false
  $guardians=Api GET "/students/$StudentId/guardians";Assert ($guardians.studentId -eq $StudentId -and $guardians.schoolId -eq 1 -and $null -ne $guardians.guardians -and @($guardians.guardians).Count -eq 0 -and @($guardians.consentHistory).Count -eq 0) 'Existing guardian or consent scope blocks fixture creation'
  $state.fixtureWritesStarted=$true;Save-Journal
  $created=Api POST "/students/$StudentId/guardians" @{fullName="Synthetic Guardian $RunId";relationship='GUARDIAN';email="$($RunId.ToLowerInvariant())@acceptance.invalid";primary=$true;receivesNotifications=$true;contactVerified=$true}
  $owned=@($created.guardians|Where-Object {$_.fullName -ceq "Synthetic Guardian $RunId"});Assert ($created.studentId -eq $StudentId -and $created.schoolId -eq 1 -and @($created.guardians).Count -eq 1 -and $owned.Count -eq 1 -and $owned[0].id -and $owned[0].email -ceq "$($RunId.ToLowerInvariant())@acceptance.invalid" -and $owned[0].primary -eq $true -and $owned[0].receivesNotifications -eq $true -and $owned[0].contactVerifiedAt -and $owned[0].status -ceq 'ACTIVE' -and -not $owned[0].phone) 'Synthetic guardian creation uncertain'
  $state.guardianId=$owned[0].id; Save-Journal
  Consent 'GRANTED' 'grant'
  $first=New-ReviewedBroadcast 'dryrun';$firstQueuedAt=Queue-Owned $first
  $state.dryRun=Wait-Outcome $first 'DRY_RUN';Save-Journal
  $state.dryRunScheduler=Wait-SchedulerTick $firstQueuedAt;Save-Journal
  Queue-Owned $first | Out-Null
  $state.replay=Wait-Outcome $first 'DRY_RUN';Save-Journal
  $second=New-ReviewedBroadcast 'revoked';Consent 'WITHDRAWN' 'withdraw'
  $secondQueuedAt=Queue-Owned $second
  $state.revoked=Wait-Outcome $second 'SUPPRESSED';Assert ($state.revoked.syntheticReason -eq 'SCHOOL_COMMUNICATIONS_NOT_GRANTED') 'Unexpected revocation reason';Save-Journal
  $state.revokedScheduler=Wait-SchedulerTick $secondQueuedAt;$state.completed=$true;Save-Journal
} catch {$state.failure=$_.Exception.Message;Save-Journal;throw}
finally {
  # Business exhaustion never consumes the cleanup deadline or its reserved logout slot.
  $script:cleanupMode=$true;$script:cleanupDeadline=[datetime]::UtcNow.AddMinutes(3)
  $state.fixtureCleanup=Cleanup-OwnedFixture
  if(-not $state.fixtureCleanup.confirmed){$state.completed=$false;$state.cleanupRequired=$true}
  if($authenticated -or $state.loginAttempted){try {Api POST '/auth/logout'|Out-Null;$state.logoutConfirmed=$true}catch{$state.logoutConfirmed=$false;$state.completed=$false;$state.logoutFailure='Logout was not confirmed; server session cleanup remains required.'}}
  $headers.Clear();$policyHeaders.Clear();$session=$null;$seed=$null;$password=$null;$login=$null;$emails=$null;$state.httpRequests=$count;$state.cloudReads=$cloudCount;try{Save-Journal}finally{$journalStream.Dispose()}
}
[ordered]@{completed=$state.completed;runId=$RunId;dryRun=$state.dryRun;replay=$state.replay;revoked=$state.revoked;fixtureCleanup=$state.fixtureCleanup;logoutConfirmed=$state.logoutConfirmed;journal=$journalPath}|ConvertTo-Json -Depth 8
if(-not $state.completed){throw 'Broadcast acceptance remains incomplete; inspect the safe journal before any retry.'}

param([ValidateRange(5,30)][int]$MaxMinutes=20,
      [string]$SyntheticFixtureProof='')
$ErrorActionPreference='Stop'
$project='custoking-dev'; $region='asia-south2'; $source='custoking-db-dev'
$stamp=[datetime]::UtcNow.ToString('yyyyMMddHHmmss'); $nonce=[guid]::NewGuid().ToString('N').Substring(0,8)
$clone="custoking-dev-security-restore-$stamp-$nonce"; $job="ims-dev-security-restore-$stamp-$nonce"
$evidencePath=Join-Path $PSScriptRoot "../docs/security-remediation/dev-pitr-$stamp-$nonce.json"
$jobFile=Join-Path $PSScriptRoot "../tmp/$job.json"
$started=[datetime]::UtcNow; $point=$started.AddMinutes(-5).ToString('yyyy-MM-ddTHH:mm:ssZ')
$evidence=[ordered]@{scope='isolated dev schema-only PITR; no agreed RPO/RTO certification';project=$project;source=$source;clone=$clone;job=$job;pointInTimeUtc=$point;startedUtc=$started.ToString('o');success=$false;cleanup=[ordered]@{jobRemoved=$false;cloneRemoved=$false};schemaCatalog=@()}
$expectedFixtureSha=$null
if($SyntheticFixtureProof){
  $fixture=Get-Content -LiteralPath $SyntheticFixtureProof -Raw|ConvertFrom-Json
  $verifiedAt=([datetimeoffset]$fixture.checkedAtUtc).UtcDateTime
  if($fixture.project -ne $project -or $fixture.marker -cne 'SEC-ACPT-20261007' -or $fixture.completed -ne $true -or $fixture.reservedOnly -ne $true -or (@($fixture.schoolIds)-join ',') -cne '990007101,990007102' -or $verifiedAt -lt $started.AddHours(-1) -or $verifiedAt -gt ([datetimeoffset]$point).UtcDateTime){throw 'Require fresh exact reserved fixture proof established before PITR point'}
  $sha=[Security.Cryptography.SHA256]::Create()
  try{$expectedFixtureSha=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes('990007101:SEC-ACPT-20261007-1|990007102:SEC-ACPT-20261007-2')))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
  $evidence.scope='isolated dev catalog and exact reserved synthetic marker PITR; no business rows or agreed RPO/RTO certification'
  $evidence.syntheticFixture=@{sourceVerifiedCount=2;sourceProvenance=$fixture.marker;sourceVerifiedAtUtc=$fixture.checkedAtUtc;expectedSha256=$expectedFixtureSha;restoredVerified=$false}
}
$cloneAttempted=$false; $jobAttempted=$false; $cloneOperation=$null
function Cloud([string[]]$Arguments,[int]$Seconds=60) {
  $worker=Start-Job -ScriptBlock { param($argsList)
    $ErrorActionPreference='Continue'; $stderr=[IO.Path]::GetTempFileName()
    try {$output=@(& gcloud.cmd @argsList 2>$stderr);$code=$LASTEXITCODE;$errorText=Get-Content -LiteralPath $stderr -Raw;@{code=$code;output=@($output|ForEach-Object{[string]$_});error=$errorText}}
    finally {Remove-Item -LiteralPath $stderr -ErrorAction SilentlyContinue}
  } -ArgumentList (,$Arguments)
  try {
    $finished=Wait-Job $worker -Timeout $Seconds
    if(-not $finished){Stop-Job $worker;throw 'Cloud CLI exceeded bounded request deadline'}
    $result=Receive-Job $worker
    if($result.code -ne 0){throw ($result.error + "`n" + ($result.output -join "`n"))}
    return ($result.output -join "`n")
  } finally {Remove-Job $worker -Force -ErrorAction SilentlyContinue}
}
function AwaitSqlOperation([string]$Operation,[int]$Minutes) {
  $deadline=[datetime]::UtcNow.AddMinutes($Minutes)
  do {
    $state=Cloud @('sql','operations','describe',$Operation,"--project=$project",'--format=json')|ConvertFrom-Json
    if($state.status -eq 'DONE') {
      if($state.error){throw ('SQL operation failed: '+($state.error|ConvertTo-Json -Compress -Depth 8))}
      return
    }
    Write-Host "Bounded dev SQL operation pending ($($state.status))"
    Start-Sleep -Seconds 15
  } while([datetime]::UtcNow -lt $deadline)
  throw 'Isolated dev SQL operation exceeded bounded deadline'
}
function AssertAbsent([string[]]$Arguments) {
  try { $null=Cloud $Arguments; return $false }
  catch {if($_.Exception.Message -match '(?i)(404|not found|does not exist|cannot find job)'){return $true};throw}
}
try {
  $principal=(Cloud @('auth','list','--filter=status:ACTIVE','--format=value(account)')).Trim()
  if($principal -match 'gserviceaccount.com' -or -not $principal){throw 'Drill requires active authorized personal principal'}
  $evidence.principalKind='personal'
  $metadata=Cloud @('sql','instances','describe',$source,"--project=$project",'--format=json')|ConvertFrom-Json
  if($metadata.name -ne $source -or $metadata.region -ne $region -or -not $metadata.settings.backupConfiguration.enabled -or -not $metadata.settings.backupConfiguration.pointInTimeRecoveryEnabled){throw 'Exact dev source backup/PITR prerequisites missing'}
  if($metadata.settings.ipConfiguration.ipv4Enabled -or $metadata.settings.ipConfiguration.sslMode -ne 'ENCRYPTED_ONLY'){throw 'Dev source must remain private-only with required encryption'}
  $evidence.sourceReadiness=$metadata.state; $evidence.sourceSslMode=$metadata.settings.ipConfiguration.sslMode
  $null=Cloud @('iam','service-accounts','describe',"ims-db-migration-dev@$project.iam.gserviceaccount.com","--project=$project",'--format=value(email)')
  if(-not (AssertAbsent @('sql','instances','describe',$clone,"--project=$project",'--format=value(name)'))){throw 'Unique clone name already exists'}
  $cloneAttempted=$true
  Write-Host "Starting isolated dev PITR: $source -> $clone at $point; no source restore or row export"
  $operation=Cloud @('sql','instances','clone',$source,$clone,"--point-in-time=$point","--project=$project",'--async','--quiet','--format=json')|ConvertFrom-Json
  $cloneOperation=[string]$operation.name
  if(-not $cloneOperation){throw 'Clone request did not return an operation identifier'}
  $evidence.cloneOperation=$cloneOperation
  AwaitSqlOperation $cloneOperation $MaxMinutes
  $restored=Cloud @('sql','instances','describe',$clone,"--project=$project",'--format=json')|ConvertFrom-Json
  if($restored.name -ne $clone -or $restored.state -ne 'RUNNABLE' -or $restored.settings.ipConfiguration.ipv4Enabled -or $restored.settings.ipConfiguration.sslMode -ne 'ENCRYPTED_ONLY'){throw 'Isolated clone readiness or privacy validation failed'}
  $private=@($restored.ipAddresses|Where-Object{$_.type -eq 'PRIVATE'})
  if($private.Count -ne 1 -or [string]$private[0].ipAddress -notmatch '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)'){throw 'Clone does not have exactly one RFC1918 private address'}
  $evidence.cloneReadyUtc=[datetime]::UtcNow.ToString('o'); $evidence.cloneReadinessElapsedSeconds=[math]::Round(([datetime]::UtcNow-$started).TotalSeconds,3)
  $schemas=@('identity','tenant_school','student','attendance','fee','catalog','workflow','firefighting','reporting','notification','audit','billing')
  $values=($schemas|ForEach-Object{"('$_')"}) -join ','
  $sql="BEGIN READ ONLY; WITH expected(schema_name) AS (VALUES $values) SELECT 'SCHEMA_CATALOG|' || e.schema_name || '|' || (n.oid IS NOT NULL)::text || '|' || count(c.oid)::text FROM expected e LEFT JOIN pg_namespace n ON n.nspname=e.schema_name LEFT JOIN pg_class c ON c.relnamespace=n.oid AND c.relkind IN ('r','p') GROUP BY e.schema_name,n.oid ORDER BY e.schema_name; COMMIT;"
  if($expectedFixtureSha){
    $sql=$sql.Replace('COMMIT;',"SET LOCAL app.bypass_rls='on'; SELECT 'SYNTHETIC_MARKER|' || count(*)::text || '|' || encode(sha256(convert_to(string_agg(id::text || ':' || name, '|' ORDER BY id),'UTF8')),'hex') FROM tenant_school.schools WHERE (id=990007101 AND name='SEC-ACPT-20261007-1') OR (id=990007102 AND name='SEC-ACPT-20261007-2'); COMMIT;")
  }
  $image='docker.io/library/postgres@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b'
  $definition=@{apiVersion='run.googleapis.com/v1';kind='Job';metadata=@{name=$job;namespace=$project;labels=@{purpose='isolated-schema-only-recovery'}};spec=@{template=@{metadata=@{annotations=@{'run.googleapis.com/network-interfaces'='[{"network":"default","subnetwork":"default"}]';'run.googleapis.com/vpc-access-egress'='private-ranges-only'}};spec=@{taskCount=1;parallelism=1;template=@{spec=@{serviceAccountName="ims-db-migration-dev@$project.iam.gserviceaccount.com";maxRetries=0;timeoutSeconds=120;containers=@(@{image=$image;command=@('psql');args=@('-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-c',$sql);resources=@{limits=@{cpu='1';memory='512Mi'}};env=@(@{name='PGHOST';value=[string]$private[0].ipAddress},@{name='PGPORT';value='5432'},@{name='PGUSER';value='appuser'},@{name='PGDATABASE';value='custoking_dev'},@{name='PGSSLMODE';value='require'},@{name='PGCONNECT_TIMEOUT';value='10'},@{name='PGOPTIONS';value='-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000'},@{name='PGPASSWORD';valueFrom=@{secretKeyRef=@{name='db-password-dev';key='latest'}}})})}}}}}}
  $definition|ConvertTo-Json -Depth 30|Set-Content -LiteralPath $jobFile -Encoding UTF8
  $jobAttempted=$true; $null=Cloud @('run','jobs','replace',$jobFile,"--project=$project","--region=$region",'--quiet','--format=json')
  $execution=Cloud @('run','jobs','execute',$job,"--project=$project","--region=$region",'--async','--quiet','--format=json')|ConvertFrom-Json
  $executionName=[string]$execution.metadata.name
  $deadline=[datetime]::UtcNow.AddMinutes(5)
  do {
    $state=Cloud @('run','jobs','executions','describe',$executionName,"--project=$project","--region=$region",'--format=json')|ConvertFrom-Json
    $completed=@($state.status.conditions|Where-Object{$_.type -eq 'Completed'})
    if($completed.Count -eq 1 -and $completed[0].status -eq 'True'){break}
    if($completed.Count -eq 1 -and $completed[0].status -eq 'False'){throw ('Schema-only validation job failed: '+$completed[0].message)}
    Start-Sleep -Seconds 10
  } while([datetime]::UtcNow -lt $deadline)
  if($completed.Count -ne 1 -or $completed[0].status -ne 'True'){throw 'Schema-only validation job exceeded bounded execution deadline'}
  $logDeadline=[datetime]::UtcNow.AddSeconds(60)
  do {
    $logs=Cloud @('logging','read',"resource.type=cloud_run_job AND resource.labels.job_name=$job AND textPayload:SCHEMA_CATALOG","--project=$project",'--limit=50','--format=json')|ConvertFrom-Json
    $catalog=@($logs|ForEach-Object{if([string]$_.textPayload -match '^SCHEMA_CATALOG\|([a-z_]+)\|(true|false)\|([0-9]+)$'){@{schema=$Matches[1];present=($Matches[2] -eq 'true');tableCount=[int]$Matches[3]}}})
    if($catalog.Count -eq $schemas.Count){break};Start-Sleep -Seconds 5
  } while([datetime]::UtcNow -lt $logDeadline)
  if($catalog.Count -ne $schemas.Count -or @($catalog|Where-Object{-not $_.present -or $_.tableCount -le 0}).Count -gt 0){throw 'Restored schema catalog validation incomplete'}
  if($expectedFixtureSha){
    $markerLogs=Cloud @('logging','read',"resource.type=cloud_run_job AND resource.labels.job_name=$job AND textPayload:SYNTHETIC_MARKER","--project=$project",'--limit=10','--format=json')|ConvertFrom-Json
    $markers=@($markerLogs|ForEach-Object{if([string]$_.textPayload -match '^SYNTHETIC_MARKER\|([0-9]+)\|([a-f0-9]{64})$'){@{count=[int]$Matches[1];sha256=$Matches[2]}}})
    if($markers.Count -ne 1 -or $markers[0].count -ne 2 -or $markers[0].sha256 -cne $expectedFixtureSha){throw 'Exact synthetic marker recovery checksum/count mismatch'}
    $evidence.syntheticFixture.restoredVerified=$true;$evidence.syntheticFixture.restoredCount=2;$evidence.syntheticFixture.restoredSha256=$markers[0].sha256
  }
  $evidence.schemaCatalog=$catalog; $evidence.image=$image; $evidence.validationServiceAccount="ims-db-migration-dev@$project.iam.gserviceaccount.com"
  $evidence.validationCompleteElapsedSeconds=[math]::Round(([datetime]::UtcNow-$started).TotalSeconds,3); $evidence.success=$true
} catch { $evidence.failure=$_.Exception.Message; Write-Host "Dev schema-only recovery drill failed: $($_.Exception.Message)" }
finally {
  if($jobAttempted) {
    try {if($job -notmatch '^ims-dev-security-restore-[0-9]{14}-[a-f0-9]{8}$'){throw 'Exact job cleanup guard rejected name'};if(-not (AssertAbsent @('run','jobs','describe',$job,"--project=$project","--region=$region",'--format=value(metadata.name)'))){$null=Cloud @('run','jobs','delete',$job,"--project=$project","--region=$region",'--quiet')};$evidence.cleanup.jobRemoved=AssertAbsent @('run','jobs','describe',$job,"--project=$project","--region=$region",'--format=value(metadata.name)')}
    catch {$evidence.cleanup.jobError=$_.Exception.Message}
  } else {$evidence.cleanup.jobRemoved=$true}
  if($cloneAttempted) {
    try {
      if($clone -eq $source -or $clone -notmatch '^custoking-dev-security-restore-[0-9]{14}-[a-f0-9]{8}$'){throw 'Exact isolated clone cleanup guard rejected name'}
      if($cloneOperation){try{AwaitSqlOperation $cloneOperation $MaxMinutes}catch{Write-Host 'Clone operation failed or cleanup wait expired; attempting exact clone removal'}}
      if(-not (AssertAbsent @('sql','instances','describe',$clone,"--project=$project",'--format=value(name)'))){
        $cleanupMetadata=Cloud @('sql','instances','describe',$clone,"--project=$project",'--format=json')|ConvertFrom-Json
        if($cleanupMetadata.settings.deletionProtectionEnabled){$patch=Cloud @('sql','instances','patch',$clone,'--no-deletion-protection',"--project=$project",'--async','--quiet','--format=json')|ConvertFrom-Json;AwaitSqlOperation ([string]$patch.name) $MaxMinutes;$evidence.cleanup.cloneProtectionDisabledForDeletion=$true}
        $deletion=Cloud @('sql','instances','delete',$clone,"--project=$project",'--async','--quiet','--format=json')|ConvertFrom-Json;AwaitSqlOperation ([string]$deletion.name) $MaxMinutes
      }
      $evidence.cleanup.cloneRemoved=AssertAbsent @('sql','instances','describe',$clone,"--project=$project",'--format=value(name)')
    } catch {$evidence.cleanup.cloneError=$_.Exception.Message}
  } else {$evidence.cleanup.cloneRemoved=$true}
  $evidence.finishedUtc=[datetime]::UtcNow.ToString('o');$evidence|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $evidencePath -Encoding UTF8
  if(Test-Path -LiteralPath $jobFile){Remove-Item -LiteralPath $jobFile}
  Write-Host "Dev recovery evidence: $evidencePath"
}
if(-not $evidence.success -or -not $evidence.cleanup.jobRemoved -or -not $evidence.cleanup.cloneRemoved){exit 1}

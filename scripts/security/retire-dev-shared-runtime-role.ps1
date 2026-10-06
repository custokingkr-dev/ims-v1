param([switch]$Apply,[string]$OutputDirectory='artifacts/security-shared-runtime-retirement')
$ErrorActionPreference='Stop'
$project='custoking-dev';$region='asia-south2'
$expected=@{'identity-service'='ims_identity_rt';'school-core-service'='ims_school_core_rt';'operations-service'='ims_operations_rt';'platform-service'='ims_platform_rt';'billing-service'='ims_billing_rt'}
function Cloud([string[]]$Arguments){
 $previous=$ErrorActionPreference
 try{$ErrorActionPreference='Continue';$output=@(& gcloud.cmd @Arguments 2>$null);if($LASTEXITCODE -ne 0){throw 'Shared role retirement cloud command failed.'};return ($output -join "`n")}finally{$ErrorActionPreference=$previous}
}
foreach($service in $expected.Keys){
 $data=Cloud @('run','services','describe',"custoking-$service-dev","--project=$project","--region=$region",'--format=json')|ConvertFrom-Json
 $latest=[string]$data.status.latestReadyRevisionName
 $traffic=@($data.status.traffic)
 if([string]::IsNullOrWhiteSpace($latest) -or $latest -ne [string]$data.status.latestCreatedRevisionName -or
    @($traffic|Where-Object{$_.revisionName -eq $latest -and [int]$_.percent -eq 100}).Count -ne 1 -or
    @($traffic|Where-Object{($_.revisionName -ne $latest -and ([int]$_.percent -gt 0 -or -not [string]::IsNullOrWhiteSpace([string]$_.tag)))}).Count -gt 0){throw 'All dedicated runtime revisions must be ready with100percent traffic and no old revision tags before shared role retirement.'}
 $revision=Cloud @('run','revisions','describe',$latest,"--project=$project","--region=$region",'--format=json')|ConvertFrom-Json
 if([string]$revision.metadata.name -ne $latest -or @($revision.status.conditions|Where-Object{$_.type -eq 'Ready' -and $_.status -eq 'True'}).Count -ne 1){throw 'Serving revision must be independently verified ready.'}
 $stem=$service.Replace('-service','')
 if($revision.spec.serviceAccountName -ne "ims-$stem-dev@custoking-dev.iam.gserviceaccount.com" -or @($revision.spec.containers).Count -ne 1){throw 'Serving revision runtime identity isolation failed.'}
 $envValues=@{};foreach($entry in $revision.spec.containers[0].env){if($envValues.ContainsKey($entry.name)){throw 'Duplicate runtime environment declaration.'};$envValues[$entry.name]=$entry}
 if($envValues.RUNTIME_DB_ROLE.value -ne $expected[$service] -or $envValues.SPRING_DATASOURCE_USERNAME.value -ne $expected[$service] -or $envValues.APP_MIGRATIONS_ENABLED.value -ne 'false' -or @($revision.spec.containers[0].env|Where-Object{$_.name -match '^(FLYWAY_|SPRING_FLYWAY_)'}).Count -gt 0){throw 'Runtime database isolation preflight failed; shared role unchanged.'}
 $password=$envValues.SPRING_DATASOURCE_PASSWORD
 if($null -eq $password -or $null -ne $password.value -or $password.valueFrom.secretKeyRef.name -ne "$stem-runtime-db-password-dev" -or [string]::IsNullOrWhiteSpace([string]$password.valueFrom.secretKeyRef.key)){throw 'Serving revision must use its dedicated runtime database secret.'}
}
$job='ims-dev-shared-runtime-retire-'+[datetime]::UtcNow.ToString('yyyyMMddHHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$sql=@'
BEGIN;
SET LOCAL statement_timeout='15s';
SET LOCAL lock_timeout='5s';
DO $check$
DECLARE expected text;
BEGIN
 FOREACH expected IN ARRAY ARRAY['ims_identity_rt','ims_school_core_rt','ims_operations_rt','ims_platform_rt','ims_billing_rt'] LOOP
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=expected AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolinherit AND NOT rolreplication) THEN RAISE EXCEPTION 'Dedicated safe role prerequisite missing';END IF;
  IF EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=expected))
   OR EXISTS(SELECT 1 FROM pg_class WHERE relowner=(SELECT oid FROM pg_roles WHERE rolname=expected))
   OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspowner=(SELECT oid FROM pg_roles WHERE rolname=expected))
   OR EXISTS(SELECT 1 FROM pg_database WHERE datdba=(SELECT oid FROM pg_roles WHERE rolname=expected))
   OR EXISTS(SELECT 1 FROM pg_proc WHERE proowner=(SELECT oid FROM pg_roles WHERE rolname=expected))
  THEN RAISE EXCEPTION 'Dedicated role membership or ownership prerequisite failed';END IF;
 END LOOP;
END $check$;
ALTER ROLE app_rt NOLOGIN;
COMMIT;
SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE usename='app_rt' AND pid<>pg_backend_pid();
SELECT jsonb_build_object('sharedRole','app_rt','canLogin',(SELECT rolcanlogin FROM pg_roles WHERE rolname='app_rt'),'remainingSessions',(SELECT count(*) FROM pg_stat_activity WHERE usename='app_rt'))::text;
'@
New-Item -ItemType Directory -Force -Path $OutputDirectory|Out-Null
$jobFile=Join-Path $OutputDirectory 'retirement-job.json'
$definition=@{apiVersion='run.googleapis.com/v1';kind='Job';metadata=@{name=$job};spec=@{template=@{metadata=@{annotations=@{'run.googleapis.com/network-interfaces'='[{"network":"default","subnetwork":"default"}]';'run.googleapis.com/vpc-access-egress'='private-ranges-only'}};spec=@{taskCount=1;template=@{spec=@{serviceAccountName='ims-db-migration-dev@custoking-dev.iam.gserviceaccount.com';maxRetries=0;timeoutSeconds=60;containers=@(@{image='docker.io/library/postgres@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b';command=@('psql');args=@('-X','-A','-t','-v','ON_ERROR_STOP=1','-c',$sql);resources=@{limits=@{cpu='1';memory='512Mi'}};env=@(@{name='PGHOST';value='10.92.0.3'},@{name='PGDATABASE';value='custoking_dev'},@{name='PGUSER';value='appuser'},@{name='PGSSLMODE';value='require'},@{name='PGCONNECT_TIMEOUT';value='10'},@{name='PGPASSWORD';valueFrom=@{secretKeyRef=@{name='db-password-dev';key='latest'}}})})}}}}}}
$definition|ConvertTo-Json -Depth 25|Set-Content -LiteralPath $jobFile -Encoding UTF8
if(-not $Apply){@{project=$project;preflightPassed=$true;apply=$false;operation='Disable only app_rt LOGIN and terminate only app_rt sessions; retain baseline ACLs for migration callbacks';jobFile=$jobFile}|ConvertTo-Json;exit 0}
$attempted=$false
try{
 $attempted=$true
 $null=Cloud @('run','jobs','replace',$jobFile,"--project=$project","--region=$region",'--quiet')
 $execution=Cloud @('run','jobs','execute',$job,"--project=$project","--region=$region",'--wait','--format=json','--quiet')|ConvertFrom-Json
 if(@($execution.status.conditions|Where-Object{$_.type -eq 'Completed' -and $_.status -eq 'True'}).Count -ne 1){throw 'Shared runtime retirement execution did not complete.'}
 $filter='resource.type="cloud_run_job" AND resource.labels.job_name="'+$job+'"'
 $proof=$null
 for($attempt=0;$attempt -lt 6 -and -not $proof;$attempt++){
  $logs=Cloud @('logging','read',$filter,"--project=$project",'--limit=50','--format=json')|ConvertFrom-Json
  $entry=@($logs|Where-Object{$_.jsonPayload.sharedRole -eq 'app_rt'}|Select-Object -First 1)
  if($entry.Count -gt 0){$proof=$entry[0].jsonPayload}
  if(-not $proof){Start-Sleep -Seconds 5}
 }
 if(-not $proof -or $proof.canLogin -ne $false -or [int]$proof.remainingSessions -ne 0){throw 'Shared role retirement requires verified NOLOGIN and zero remaining sessions.'}
 @{project=$project;sharedRoleCanLogin=$false;sharedRoleSessions=0;baselineAclRetained=$true;checkedAtUtc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content (Join-Path $OutputDirectory 'proof.json') -Encoding UTF8
}finally{
 if($attempted){if($job -notmatch '^ims-dev-shared-runtime-retire-[0-9]{14}-[a-f0-9]{8}$'){throw 'Unsafe retirement cleanup name.'};$null=Cloud @('run','jobs','delete',$job,"--project=$project","--region=$region",'--quiet')}
}
exit 0

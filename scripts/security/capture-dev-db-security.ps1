param([string]$OutputDirectory = 'artifacts/security-db', [switch]$KeepJob,
 [ValidateSet('app_rt','ims_identity_rt','ims_school_core_rt','ims_operations_rt','ims_platform_rt','ims_billing_rt')]
 [string]$Role = 'app_rt')
$ErrorActionPreference = 'Stop'
function Invoke-InspectionGcloud {
 param([string[]]$Arguments)
 $previousPreference=$ErrorActionPreference
 try {
  $ErrorActionPreference='Continue'
  $nativeOutput=@(& gcloud.cmd @Arguments 2>$null)
  $nativeExit=$LASTEXITCODE
  if($nativeExit -ne 0){throw 'Database inspection cloud command failed.'}
  return $nativeOutput
 } finally {$ErrorActionPreference=$previousPreference}
}
# Deliberately fixed to dev. Captures catalog metadata, never application rows/passwords.
$project = 'custoking-dev'; $region = 'asia-south2'
$roleCredentials = @{
 app_rt=@{secret='app-rt-password-dev';account='ims-school-core-dev'}
 ims_identity_rt=@{secret='identity-runtime-db-password-dev';account='ims-identity-dev'}
 ims_school_core_rt=@{secret='school-core-runtime-db-password-dev';account='ims-school-core-dev'}
 ims_operations_rt=@{secret='operations-runtime-db-password-dev';account='ims-operations-dev'}
 ims_platform_rt=@{secret='platform-runtime-db-password-dev';account='ims-platform-dev'}
 ims_billing_rt=@{secret='billing-runtime-db-password-dev';account='ims-billing-dev'}
}
$credentials = $roleCredentials[$Role]
$jobName = 'ims-security-db-inspection-' + [datetime]::UtcNow.ToString('yyyyMMddHHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$sql = @'
BEGIN READ ONLY;
SET LOCAL statement_timeout='10s';
SELECT jsonb_build_object(
 'currentRole',current_user,
 'ssl',(SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()),
 'maxConnections',current_setting('max_connections'),
 'connectionCount',(SELECT count(*) FROM pg_stat_activity),
 'roles',(SELECT jsonb_agg(jsonb_build_object('name',rolname,'canLogin',rolcanlogin,'superuser',rolsuper,'bypassRls',rolbypassrls,'createRole',rolcreaterole,'createDb',rolcreatedb,'inherit',rolinherit,'replication',rolreplication)) FROM pg_roles WHERE rolname='app_rt' OR rolname LIKE 'ims_%_rt'),
 'memberships',(SELECT coalesce(jsonb_agg(jsonb_build_object('member',m.rolname,'role',r.rolname)), '[]') FROM pg_auth_members a JOIN pg_roles m ON m.oid=a.member JOIN pg_roles r ON r.oid=a.roleid WHERE m.rolname='app_rt' OR m.rolname LIKE 'ims_%_rt'),
 'tables',(SELECT jsonb_agg(jsonb_build_object('schema',n.nspname,'table',c.relname,'owner',pg_get_userbyid(c.relowner),'rls',c.relrowsecurity,'forced',c.relforcerowsecurity,'runtimeSelect',has_table_privilege(current_user,c.oid,'SELECT'),'runtimeInsert',has_table_privilege(current_user,c.oid,'INSERT'),'runtimeUpdate',has_table_privilege(current_user,c.oid,'UPDATE'),'runtimeDelete',has_table_privilege(current_user,c.oid,'DELETE'))) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('identity','tenant_school','student','attendance','fee','catalog','workflow','firefighting','billing','reporting','notification','audit') AND c.relkind='r'),
 'policyCount',(SELECT count(*) FROM pg_policies WHERE schemaname IN ('identity','tenant_school','student','attendance','fee','catalog','workflow','firefighting','billing','reporting','notification','audit'))
)::text;
COMMIT;
'@
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$jobFile = Join-Path $OutputDirectory 'inspection-job.json'
$job = @{
 apiVersion='run.googleapis.com/v1'; kind='Job'; metadata=@{name=$jobName}
 spec=@{template=@{
  metadata=@{annotations=@{'run.googleapis.com/network-interfaces'='[{"network":"default","subnetwork":"default"}]';'run.googleapis.com/vpc-access-egress'='private-ranges-only'}}
  spec=@{taskCount=1;template=@{
  spec=@{serviceAccountName=($credentials.account+'@custoking-dev.iam.gserviceaccount.com');maxRetries=0;timeoutSeconds=60;containers=@(@{
   image='docker.io/library/postgres@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b'
   command=@('psql');args=@('-X','-A','-t','-v','ON_ERROR_STOP=1','-c',$sql)
   resources=@{limits=@{cpu='1';memory='512Mi'}}
   env=@(@{name='PGHOST';value='10.92.0.3'},@{name='PGPORT';value='5432'},@{name='PGDATABASE';value='custoking_dev'},@{name='PGUSER';value=$Role},@{name='PGSSLMODE';value='require'},@{name='PGCONNECT_TIMEOUT';value='10'},@{name='PGPASSWORD';valueFrom=@{secretKeyRef=@{name=$credentials.secret;key='latest'}}})
  })}
 }}}}
}
$job | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $jobFile -Encoding UTF8
$created=$false
try {
 $created=$true
 $null=Invoke-InspectionGcloud @('run','jobs','replace',$jobFile,"--project=$project","--region=$region",'--quiet')
 $null=Invoke-InspectionGcloud @('run','jobs','execute',$jobName,"--project=$project","--region=$region",'--wait','--quiet')
 $filter = 'resource.type="cloud_run_job" AND resource.labels.job_name="' + $jobName + '"'
 Invoke-InspectionGcloud @('logging','read',$filter,"--project=$project",'--limit=100','--format=json') | Set-Content -LiteralPath (Join-Path $OutputDirectory 'logs.json') -Encoding UTF8
 @{job=$jobName;project=$project;capturedAtUtc=[datetime]::UtcNow.ToString('o');readOnly=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding UTF8
} finally {
 if ($created -and -not $KeepJob) {
  if ($jobName -notmatch '^ims-security-db-inspection-[0-9]{14}-[a-f0-9]{8}$') { throw 'Unsafe cleanup name.' }
  $null=Invoke-InspectionGcloud @('run','jobs','delete',$jobName,"--project=$project","--region=$region",'--quiet')
 }
}
exit 0

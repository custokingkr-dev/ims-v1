param(
  [Parameter(Mandatory=$true)][string]$ProjectId,
  [Parameter(Mandatory=$true)][string]$Region,
  [Parameter(Mandatory=$true)][ValidateSet('dev','prod')][string]$Environment,
  [Parameter(Mandatory=$true)][ValidateSet('identity-service','school-core-service','operations-service','platform-service','billing-service')][string]$Service,
  [Parameter(Mandatory=$true)][string]$ImageRef,
  [Parameter(Mandatory=$true)][string]$CommitSha,
  [ValidateSet('CloudRun','CloudDeploy')][string]$ConfigSource='CloudRun',
  [string]$DatabaseUrl='',
  [string]$OutputDirectory='release-evidence/migrations',
  [ValidateRange(1,60)][int]$SchemaEvidenceTimeoutSeconds=30,
  [switch]$DryRun
)
$ErrorActionPreference='Stop'
if($ProjectId -ne "custoking-$Environment" -or $Region -ne 'asia-south2'){throw 'Migration project/region must match the explicit dev or prod environment.'}
if($CommitSha -notmatch '^[a-f0-9]{40}$'){throw 'Migration requires a full immutable commit SHA.'}
if($ImageRef -notmatch ('^'+[regex]::Escape("$Region-docker.pkg.dev/$ProjectId/")+'[A-Za-z0-9._/-]+@sha256:[a-f0-9]{64}$')){throw 'Migration requires an immutable SHA256 image in this environment release registry.'}
$GcloudCommand=if($env:OS -eq 'Windows_NT'){'gcloud.cmd'}else{'gcloud'}
function Invoke-MigrationGcloud {
  param([string[]]$Arguments,[int]$TimeoutSeconds=60)
  # Native progress on stderr is normal in PS5. Keep it private and check the exit status.
  $worker=Start-Job -ScriptBlock {
    param($command,$arguments)
    $ErrorActionPreference='Continue'
    $output=@(& $command @arguments 2>$null)
    @{ExitCode=$LASTEXITCODE;Output=$output}
  } -ArgumentList $GcloudCommand,$Arguments
  try {
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    do { $done=Wait-Job $worker -Timeout ([Math]::Min(30,[Math]::Max(1,[int]($deadline-(Get-Date)).TotalSeconds))) } while(-not $done -and (Get-Date) -lt $deadline)
    if(-not $done){Stop-Job $worker;throw 'Migration CLI deadline exceeded.'}
    $result=Receive-Job $worker -ErrorAction Stop
    if($result.ExitCode -ne 0){throw 'Migration CLI failed; credentials and database diagnostics are intentionally omitted.'}
    return ($result.Output -join "`n")
  } finally { Remove-Job $worker -Force -ErrorAction SilentlyContinue }
}
if(-not $DatabaseUrl){
  if($DryRun){throw 'DryRun requires an explicit DatabaseUrl; it never discovers cloud configuration.'}
  if($ConfigSource -eq 'CloudDeploy'){
    $target=Invoke-MigrationGcloud @('deploy','targets','describe',"$Service-$Environment","--project=$ProjectId","--region=$Region",'--format=json') | ConvertFrom-Json
    # gcloud renders the Target alongside its Active Pipeline, unlike the bare REST resource.
    if($target.PSObject.Properties.Name -contains 'Target') {
      $target=$target.Target
      if($null -eq $target -or $target -is [string] -or $target -is [array]){throw 'Cloud Deploy Target wrapper is malformed.'}
    }
    $hostName=[string]$target.deployParameters.db_host;$databaseName=[string]$target.deployParameters.db_name
    if($hostName -notmatch '^[A-Za-z0-9.-]+$' -or $databaseName -notmatch '^[A-Za-z0-9_]+$'){throw 'Cloud Deploy target lacks explicit valid database host/name.'}
    $DatabaseUrl="jdbc:postgresql://$hostName/$databaseName`?sslmode=require"
  } else {
    $runtime=Invoke-MigrationGcloud @('run','services','describe',"custoking-$Service-$Environment","--project=$ProjectId","--region=$Region",'--format=json') | ConvertFrom-Json
    $urls=@($runtime.spec.template.spec.containers[0].env | Where-Object {$_.name -eq 'SPRING_DATASOURCE_URL'})
    if($urls.Count -ne 1 -or -not $urls[0].value){throw 'Existing runtime lacks exactly one explicit datasource URL.'}
    $DatabaseUrl=[string]$urls[0].value
  }
}
if($DatabaseUrl -notmatch '^jdbc:postgresql://[A-Za-z0-9.-]+(?::5432)?/[A-Za-z0-9_]+\?sslmode=require$' -or $DatabaseUrl -match 'placeholder'){throw 'Migration requires an explicit PostgreSQL URL with required TLS and no credentials/query overrides.'}
$schemaMap=@{'identity-service'=@('identity');'school-core-service'=@('tenant_school','student','attendance','fee','catalog');'operations-service'=@('workflow','firefighting');'platform-service'=@('reporting','notification','audit');'billing-service'=@('billing')}
$jobName="ims-migrate-$($Service.Replace('-service',''))-$Environment-"+(Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$job=@{apiVersion='run.googleapis.com/v1';kind='Job';metadata=@{name=$jobName;namespace=$ProjectId;labels=@{purpose='owner-schema-migration';environment=$Environment}};spec=@{template=@{metadata=@{annotations=@{'run.googleapis.com/network-interfaces'='[{"network":"default","subnetwork":"default"}]';'run.googleapis.com/vpc-access-egress'='private-ranges-only'}};spec=@{taskCount=1;parallelism=1;template=@{spec=@{serviceAccountName="ims-db-migration-$Environment@$ProjectId.iam.gserviceaccount.com";maxRetries=0;timeoutSeconds=300;containers=@(@{image=$ImageRef;command=@('java');args=@('-XX:+UseContainerSupport','-XX:MaxRAMPercentage=75.0','-XX:+ExitOnOutOfMemoryError','-Dloader.main=com.custoking.ims.migration.MigrationOnlyMain','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher');resources=@{limits=@{cpu='1';memory='768Mi'}};env=@(@{name='APP_MIGRATION_SERVICE';value=$Service},@{name='FLYWAY_URL';value=$DatabaseUrl},@{name='FLYWAY_USERNAME';value='appuser'},@{name='FLYWAY_PASSWORD';valueFrom=@{secretKeyRef=@{name="db-password-$Environment";key='latest'}}})})}}}}}}
$null=New-Item -ItemType Directory -Force -Path $OutputDirectory
$jobFile=Join-Path $OutputDirectory "$jobName.job.json"
$job|ConvertTo-Json -Depth 30|Set-Content -Encoding UTF8 -LiteralPath $jobFile
if($DryRun){@{dryRun=$true;service=$Service;jobFile=$jobFile;imageDigest=($ImageRef -split '@')[1];schemas=$schemaMap[$Service]}|ConvertTo-Json -Depth 5;return}
$attempted=$false;$started=Get-Date;$evidence=$null
try {
  $attempted=$true
  $null=Invoke-MigrationGcloud @('run','jobs','replace',$jobFile,"--project=$ProjectId","--region=$Region",'--quiet')
  $execution=Invoke-MigrationGcloud -Arguments @('run','jobs','execute',$jobName,"--project=$ProjectId","--region=$Region",'--wait','--format=json','--quiet') -TimeoutSeconds 600 | ConvertFrom-Json
  if(@($execution.status.conditions|Where-Object {$_.type -eq 'Completed' -and $_.status -eq 'True'}).Count -ne 1){throw 'Migration execution did not report successful completion.'}
  $filter="resource.type=cloud_run_job AND resource.labels.job_name=$jobName AND textPayload:OWNER_MIGRATION_RESULT"
  $results=@{}
  $schemaDeadline=(Get-Date).AddSeconds($SchemaEvidenceTimeoutSeconds)
  do {
    $remainingSeconds=[Math]::Max(1,[int]($schemaDeadline-(Get-Date)).TotalSeconds)
    $logs=Invoke-MigrationGcloud -Arguments @('logging','read',$filter,"--project=$ProjectId",'--limit=100','--format=json') -TimeoutSeconds $remainingSeconds | ConvertFrom-Json
    foreach($entry in $logs){if([string]$entry.textPayload -match ('^OWNER_MIGRATION_RESULT service='+[regex]::Escape($Service)+' schema=([a-z_]+) version=([A-Za-z0-9_.-]+) migrationsExecuted=([0-9]+) success=true$')){$results[$Matches[1]]=@{schema=$Matches[1];version=$Matches[2];migrationsExecuted=[int]$Matches[3]}}}
    $missing=@($schemaMap[$Service]|Where-Object {-not $results.ContainsKey($_)}).Count -gt 0
    if($missing -and (Get-Date) -lt $schemaDeadline){Start-Sleep -Milliseconds 500}
  } while($missing -and (Get-Date) -lt $schemaDeadline)
  if(@($schemaMap[$Service]|Where-Object {-not $results.ContainsKey($_)}).Count -gt 0){throw 'Migration lacks validated schema-history evidence for every owned schema; release is blocked.'}
  $evidence=@{service=$Service;environment=$Environment;commitSha=$CommitSha;imageDigest=($ImageRef -split '@')[1];elapsedSeconds=[Math]::Round(((Get-Date)-$started).TotalSeconds,3);schemas=@($schemaMap[$Service]|ForEach-Object {$results[$_]});success=$true}
} finally {
  if($attempted){
    if($jobName -notmatch '^ims-migrate-(identity|school-core|operations|platform|billing)-(dev|prod)-[0-9]{14}-[a-f0-9]{8}$'){throw 'Unexpected migration job name; cleanup refused.'}
    $null=Invoke-MigrationGcloud @('run','jobs','delete',$jobName,"--project=$ProjectId","--region=$Region",'--quiet')
  }
}
$evidence|ConvertTo-Json -Depth 8|Set-Content -Encoding UTF8 -LiteralPath (Join-Path $OutputDirectory "$Service.json")
$evidence|ConvertTo-Json -Depth 8

param([switch]$Execute,[string]$OutputDirectory='tmp/dev-signed-transport-probe')
$ErrorActionPreference='Stop'
$project='custoking-dev';$region='asia-south2'
$roles=@{'identity-service'='ims_identity_rt';'school-core-service'='ims_school_core_rt';'operations-service'='ims_operations_rt';'platform-service'='ims_platform_rt';'billing-service'='ims_billing_rt'}
function Cloud([string[]]$Arguments){$old=$ErrorActionPreference;try{$ErrorActionPreference='Continue';$output=@(& gcloud.cmd @Arguments 2>$null);if($LASTEXITCODE -ne 0){throw 'Probe cloud operation failed'};return ($output -join "`n")}finally{$ErrorActionPreference=$old}}
$targets=@();$image=$null
foreach($service in @('identity-service','school-core-service','operations-service','platform-service','billing-service','api-gateway')) {
 $data=Cloud @('run','services','describe',"custoking-$service-dev","--project=$project","--region=$region",'--format=json')|ConvertFrom-Json
 $latest=[string]$data.status.latestReadyRevisionName
 if([string]::IsNullOrWhiteSpace($latest) -or $latest -ne $data.status.latestCreatedRevisionName -or @($data.status.traffic|Where-Object{$_.revisionName -eq $latest -and [int]$_.percent -eq 100}).Count -ne 1 -or @($data.status.traffic|Where-Object{$_.revisionName -ne $latest -and ([int]$_.percent -gt 0 -or $_.tag)}).Count -gt 0){throw 'Probe requires latest-ready-only traffic and no old tags'}
 $revision=Cloud @('run','revisions','describe',$latest,"--project=$project","--region=$region",'--format=json')|ConvertFrom-Json
 if($revision.metadata.name -ne $latest -or @($revision.status.conditions|Where-Object{$_.type -eq 'Ready' -and $_.status -eq 'True'}).Count -ne 1 -or @($revision.spec.containers).Count -ne 1){throw 'Probe serving revision not verified ready'}
 $stem=$service.Replace('-service','')
 if($revision.spec.serviceAccountName -ne "ims-$stem-dev@custoking-dev.iam.gserviceaccount.com"){throw 'Probe runtime service account preflight failed'}
 if($service -eq 'api-gateway'){
  $image=[string]$revision.spec.containers[0].image
  if($image -notmatch '^asia-south2-docker\.pkg\.dev/custoking-dev/custoking/custoking-api-gateway@sha256:[a-f0-9]{64}$'){throw 'Probe requires immutable reviewed gateway image'}
 }else{
  $vars=@{};foreach($entry in $revision.spec.containers[0].env){if($vars.ContainsKey($entry.name)){throw 'Duplicate serving environment'};$vars[$entry.name]=$entry}
  if($vars.RUNTIME_DB_ROLE.value -ne $roles[$service] -or $vars.SPRING_DATASOURCE_USERNAME.value -ne $roles[$service] -or $vars.APP_MIGRATIONS_ENABLED.value -ne 'false' -or @($revision.spec.containers[0].env|Where-Object{$_.name -match '^(FLYWAY_|SPRING_FLYWAY_)'}).Count -gt 0){throw 'Probe requires dedicated runtime cutover'}
  if($vars.SPRING_DATASOURCE_PASSWORD.valueFrom.secretKeyRef.name -ne "$stem-runtime-db-password-dev" -or $null -ne $vars.SPRING_DATASOURCE_PASSWORD.value){throw 'Probe requires dedicated runtime secret reference'}
  $url=[string]$data.status.url
  if($url -notmatch ('^https://custoking-'+[regex]::Escape($service)+'-dev-[a-z0-9]+-em\.a\.run\.app$')){throw 'Probe service audience not exact dev host'}
  $targets+=@{service=$service;url=$url}
 }
}
$job='ims-dev-carrier-probe-'+[datetime]::UtcNow.ToString('yyyyMMddHHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$code=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dev-signed-transport-probe.js') -Raw
New-Item -ItemType Directory -Force -Path $OutputDirectory|Out-Null
$jobFile=Join-Path $OutputDirectory 'probe-job.json'
$definition=@{apiVersion='run.googleapis.com/v1';kind='Job';metadata=@{name=$job};spec=@{template=@{metadata=@{annotations=@{'run.googleapis.com/network-interfaces'='[{"network":"default","subnetwork":"default"}]';'run.googleapis.com/vpc-access-egress'='private-ranges-only'}};spec=@{taskCount=1;template=@{spec=@{serviceAccountName='ims-api-gateway-dev@custoking-dev.iam.gserviceaccount.com';maxRetries=0;timeoutSeconds=120;containers=@(@{image=$image;command=@('node');args=@('-e',$code);resources=@{limits=@{cpu='1';memory='512Mi'}};env=@(@{name='PROBE_TARGETS';value=($targets|ConvertTo-Json -Compress)})})}}}}}}
$definition|ConvertTo-Json -Depth 25|Set-Content -LiteralPath $jobFile -Encoding UTF8
if(-not $Execute){@{execute=$false;project=$project;jobFile=$jobFile;maximumRequests=15;principal='ims-api-gateway-dev';noCredentialsOrResponseBodies=$true}|ConvertTo-Json;exit 0}
$attempted=$false
try{
 $attempted=$true;$null=Cloud @('run','jobs','replace',$jobFile,"--project=$project","--region=$region",'--quiet')
 $execution=Cloud @('run','jobs','execute',$job,"--project=$project","--region=$region",'--wait','--format=json','--quiet')|ConvertFrom-Json
 if(@($execution.status.conditions|Where-Object{$_.type -eq 'Completed' -and $_.status -eq 'True'}).Count -ne 1){throw 'Signed transport probe job did not pass; inspect status-only probe logs'}
 @{completed=$true;job=$job;project=$project}|ConvertTo-Json
}finally{
 if($attempted){if($job -notmatch '^ims-dev-carrier-probe-[0-9]{14}-[a-f0-9]{8}$'){throw 'Unsafe probe cleanup name'};$null=Cloud @('run','jobs','delete',$job,"--project=$project","--region=$region",'--quiet')}
}

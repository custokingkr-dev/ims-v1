param(
  [string]$ImagesJson,
  [Parameter(Mandatory=$true)][string]$OutputJson,
  [ValidateRange(5,30)][int]$WindowMinutes=10
)
# Development-only metadata/Monitoring GETs. No workload, SQL session, job creation,
# service traffic update, IAM operation, credentials or raw service environment output.
$ErrorActionPreference='Stop'
$project='custoking-dev';$region='asia-south2';$database='custoking-db-dev'
$started=[datetime]::UtcNow;$deadline=$started.AddMinutes(8)
if(Test-Path -LiteralPath $OutputJson){throw 'Evidence output exists; overwrite prohibited.'}
$names=@('api-gateway','identity-service','frontend','school-core-service','operations-service','billing-service','platform-service')
$expected=@{}
if($ImagesJson){
  foreach($entry in @((Get-Content -Raw -LiteralPath $ImagesJson|ConvertFrom-Json).services)){
    if($entry.service -notin $names -or $expected.ContainsKey([string]$entry.service)){throw 'Invalid or duplicate expected service.'}
    $digest=if($entry.runtimeRef){[string]$entry.runtimeRef}else{[string]$entry.immutableRef}
    if($digest -notmatch '^asia-south2-docker.pkg.dev/custoking-dev/[^/]+/[^/@]+@sha256:[a-f0-9]{64}$'){throw 'Require exact immutable dev runtime image digest.'}
    $expected[[string]$entry.service]=$digest
  }
  if($expected.Count -ne 7){throw 'Exact seven-service image evidence is required.'}
}
function Read-Cloud([string[]]$Arguments,[switch]$Token){
  if([datetime]::UtcNow -gt $deadline){throw 'Read-only collection overall deadline exceeded.'}
  $worker=Start-Job -ScriptBlock {
    param($argv)
    $ErrorActionPreference='Continue'
    $command=if($env:OS -eq 'Windows_NT'){'gcloud.cmd'}else{'gcloud'}
    $raw=& $command @argv 2>$null
    @{ExitCode=$LASTEXITCODE;Data=($raw -join "`n")}
  } -ArgumentList (,$Arguments)
  try {
    $completed=Wait-Job $worker -Timeout 30
    if(-not $completed){Stop-Job $worker;throw 'Read-only inventory CLI deadline exceeded.'}
    $result=Receive-Job $worker -ErrorAction Stop
    if($result.ExitCode -ne 0){throw 'Read-only cloud inventory failed; evidence remains unknown.'}
    if($Token){return [string]$result.Data}
    return ($result.Data|ConvertFrom-Json)
  } finally {Remove-Job $worker -Force -ErrorAction SilentlyContinue}
}
function Read-Monitoring([string]$Metric,[string]$ExtraFilter){
  $series=@();$next='';$pages=0
  do {
    if([datetime]::UtcNow -gt $deadline){throw 'Read-only collection overall deadline exceeded.'}
    $pages++;if($pages -gt 3){throw 'Bounded Monitoring pagination exceeded; evidence unknown.'}
    $filter='metric.type="'+$Metric+'"'+$ExtraFilter
    $uri='https://monitoring.googleapis.com/v3/projects/custoking-dev/timeSeries?filter='+[uri]::EscapeDataString($filter)+
      '&interval.startTime='+[uri]::EscapeDataString($started.AddMinutes(-$WindowMinutes).ToString('o'))+
      '&interval.endTime='+[uri]::EscapeDataString($started.ToString('o'))+'&view=FULL&pageSize=1000'
    if($next){$uri+='&pageToken='+[uri]::EscapeDataString($next)}
    try {$response=Invoke-RestMethod -Method Get -Uri $uri -Headers @{Authorization="Bearer $accessToken"} -TimeoutSec 20}
    catch {throw 'Read-only Monitoring retrieval failed; evidence remains unknown.'}
    $series+=@($response.timeSeries|Where-Object {$null -ne $_});$next=[string]$response.nextPageToken
  }while($next)
  return @($series)
}
function Summarize-Gauge($Series,[datetime]$Now){
  $rows=@()
  foreach($item in @($Series)){
    $points=@($item.points|Where-Object {$null -ne $_}|Sort-Object {[datetimeoffset]$_.interval.endTime} -Descending)
    if(-not $points.Count){continue}
    $point=$points[0];$at=([datetimeoffset]$point.interval.endTime).UtcDateTime
    $value=if($null -ne $point.value.doubleValue){[double]$point.value.doubleValue}else{[double]$point.value.int64Value}
    $rows+=@([pscustomobject]@{at=$at.ToString('o');ageSeconds=[math]::Round(($Now-$at).TotalSeconds);fresh=($at -ge $Now.AddMinutes(-5) -and $at -le $Now);value=$value;resourceLabels=$item.resource.labels;metricLabels=$item.metric.labels;windowMaximum=(@($points|ForEach-Object {if($null -ne $_.value.doubleValue){[double]$_.value.doubleValue}else{[double]$_.value.int64Value}})|Measure-Object -Maximum).Maximum})
  }
  return @($rows)
}
$services=@()
foreach($name in $names){
  $serviceName="custoking-$name-dev"
  $data=Read-Cloud @('run','services','describe',$serviceName,"--project=$project","--region=$region",'--format=json')
  $ready=[string]$data.status.latestReadyRevisionName;$created=[string]$data.status.latestCreatedRevisionName
  if(-not $ready){throw "No ready revision exists for $name; post-cutover status unknown."}
  $revision=Read-Cloud @('run','revisions','describe',$ready,"--project=$project","--region=$region",'--format=json')
  $isReady=@($data.status.conditions|Where-Object {$_.type -eq 'Ready' -and $_.status -eq 'True'}).Count -gt 0
  $revReady=@($revision.status.conditions|Where-Object {$_.type -eq 'Ready' -and $_.status -eq 'True'}).Count -gt 0
  $latest100=@($data.status.traffic|Where-Object {$_.revisionName -eq $ready -and [int]$_.percent -eq 100}).Count -eq 1 -and @($data.status.traffic|Where-Object {[int]$_.percent -gt 0}).Count -eq 1
  $actual=[string]$revision.status.imageDigest
  $services+=@([pscustomobject]@{service=$name;ready=$isReady;revisionReady=$revReady;latestReady=$ready;latestCreated=$created;readyIsLatest=($ready -eq $created);latestTraffic100=$latest100;imageDigest=$actual;expectedDigest=$expected[$name];exactImageVerified=($expected.ContainsKey($name) -and $actual -eq $expected[$name]);serviceMaxScale=$data.metadata.annotations.'run.googleapis.com/maxScale';revisionMaxScale=$revision.metadata.annotations.'autoscaling.knative.dev/maxScale';traffic=@($data.status.traffic|ForEach-Object {@{revision=$_.revisionName;percent=$_.percent;tag=$_.tag}})})
}
$instance=Read-Cloud @('sql','instances','describe',$database,"--project=$project",'--format=json')
$accessToken=(Read-Cloud @('auth','print-access-token',"--project=$project") -Token).Trim()
if(-not $accessToken){throw 'Monitoring authentication unavailable.'}
$dbFilter=' AND resource.type="cloudsql_database" AND resource.labels.database_id="custoking-dev:custoking-db-dev"'
$cpu=@(Summarize-Gauge @(Read-Monitoring 'cloudsql.googleapis.com/database/cpu/utilization' $dbFilter) $started)
$memory=@(Summarize-Gauge @(Read-Monitoring 'cloudsql.googleapis.com/database/memory/utilization' $dbFilter) $started)
$memoryComponents=@(Summarize-Gauge @(Read-Monitoring 'cloudsql.googleapis.com/database/memory/components' $dbFilter) $started)
$connections=@(Summarize-Gauge @(Read-Monitoring 'cloudsql.googleapis.com/database/postgresql/num_backends' $dbFilter) $started)
$instances=@(Summarize-Gauge @(Read-Monitoring 'run.googleapis.com/container/instance_count' ' AND resource.type="cloud_run_revision" AND resource.labels.location="asia-south2"') $started)
$accessToken=$null
$instanceRows=@($instances|Where-Object {$_.resourceLabels.service_name -in @($names|ForEach-Object {"custoking-$_-dev"})})
$old=@($instanceRows|Where-Object {$row=$_;$match=@($services|Where-Object {"custoking-$($_.service)-dev" -eq $row.resourceLabels.service_name})[0];$row.resourceLabels.revision_name -ne $match.latestReady})
$max=@($instance.settings.databaseFlags|Where-Object name -eq 'max_connections')[0].value
$dbFresh=$cpu.Count -gt 0 -and $memory.Count -gt 0 -and $connections.Count -gt 0 -and @($cpu+$memory+$connections|Where-Object {-not $_.fresh}).Count -eq 0
$result=[ordered]@{mode='READ_ONLY_NO_LOAD';project=$project;region=$region;capturedAt=$started.ToString('o');completedAt=[datetime]::UtcNow.ToString('o');expectedSevenImagesProvided=($expected.Count -eq 7);allSevenReadyExactLatest=(@($services|Where-Object {-not($_.ready -and $_.revisionReady -and $_.readyIsLatest -and $_.latestTraffic100 -and $_.exactImageVerified)}).Count -eq 0);services=$services;database=@{state=$instance.state;maxConnectionsConfigured=$max;metricsFresh=$dbFresh;cpu=$cpu;memory=$memory;memoryComponents=$memoryComponents;memoryMeaning='utilization is fraction of quota; components are usage/cache/free percentages; conflicting gauges require investigation, no safety verdict';connections=$connections;connectionsLatestSum=($connections|Measure-Object value -Sum).Sum};revisionInstances=$instanceRows;oldRevisionInstances=$old;oldDrain=@{status=if(@($old|Where-Object {$_.fresh -and $_.value -gt 0}).Count){'OLD_INSTANCES_OBSERVED'}else{'UNKNOWN_NOT_PROVEN'};reason='Monitoring is sampled every60s and delayed up to120s. Missing/stale/zero series do not prove every old instance drained. Revision traffic and database gauges are different evidence.'};loadCertification=$false;source198ModelProven=$false;workloadStarted=$false;cloudMutations=$false;secretsPersisted=$false}
$json=$result|ConvertTo-Json -Depth 15
$absolute=[IO.Path]::GetFullPath($OutputJson);$parent=Split-Path -Parent $absolute
if(-not(Test-Path -LiteralPath $parent)){New-Item -ItemType Directory -Path $parent|Out-Null}
$stream=[IO.File]::Open($absolute,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
try{$bytes=[Text.Encoding]::UTF8.GetBytes($json);$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
Write-Output "Read-only evidence saved. Exact-seven-images=$($result.allSevenReadyExactLatest); database-metrics-fresh=$dbFresh; old-drain=$($result.oldDrain.status); no load certificate."

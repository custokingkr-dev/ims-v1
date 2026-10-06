$ErrorActionPreference='Stop'
$source=Get-Content -Raw (Join-Path $PSScriptRoot '../configure-async-relay-scheduler.ps1')
$start=$source.IndexOf('foreach ($target in $resolved) {')
$finish=$source.IndexOf('foreach ($target in $resolved) {',$start+1)
if($start -lt 0 -or $finish -le $start){throw 'Scheduler mutation block not found'}
$block=[scriptblock]::Create($source.Substring($start,$finish-$start))
$ProjectId='fixture-dev';$Region='fixture-region';$SchedulerLocation='fixture-location';$Schedule='* * * * *';$serviceAccount='fixture@example.test'
$resolved=@(@{service='fixture-service';job='fixture-job';uri='https://fixture.example.test/drain';audience='https://fixture.example.test'})
function Test-GcloudResource {param($Arguments);return $script:exists}
function Invoke-Gcloud {param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments);$script:calls.Add($Arguments)}
foreach($exists in @($false,$true)){
 $script:exists=$exists;$script:calls=[Collections.Generic.List[object]]::new()
 & $block
 $call=@($script:calls|Where-Object {$_[0] -eq 'scheduler'})
 if($call.Count -ne 1){throw 'Expected one scheduler mutation'}
 $args=$call[0];$verb=if($exists){'update'}else{'create'}
 if($args[2] -ne $verb){throw 'Wrong create/update branch'}
 $expected=if($exists){'--update-headers=Content-Type=application/json'}else{'--headers=Content-Type=application/json'}
 if($expected -notin $args -or @($args|Where-Object {$_ -match '^--(update-)?headers='}).Count -ne 1){throw 'Incorrect HTTP header flag'}
 foreach($required in @('--http-method=POST','--message-body={}','--attempt-deadline=300s',"--oidc-service-account-email=$serviceAccount","--oidc-token-audience=$($resolved[0].audience)")){if($required -notin $args){throw 'Required authenticated bounded argument absent'}}
}
'Scheduler create/update controlled argument tests passed (2 branches); no cloud commands executed.'

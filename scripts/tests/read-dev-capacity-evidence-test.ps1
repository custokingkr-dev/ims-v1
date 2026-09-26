$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$collector=Join-Path $repoRoot 'scripts/read-dev-capacity-evidence.ps1'
$global:capacityFixtureRawMode='fresh';$global:capacityFixtureMetricQueries=@();$global:capacityFixtureRawAt=[datetime]::UtcNow.AddMinutes(-70).ToString('o')
# These command/function mocks run the complete collector with disposable in-memory cloud data.
# An unexpected command fails; there is no fallback to an executable or network request.
function gcloud.cmd {
  $global:LASTEXITCODE=0;$command=$args -join ' '
  if($args -notcontains '--project=custoking-dev'){throw 'Missing explicit dev project'}
  if($command -like 'sql instances describe custoking-db-dev *'){return '{"state":"RUNNABLE","settings":{"tier":"db-f1-micro","databaseFlags":[{"name":"max_connections","value":"200"}]}}'}
  if($command -like 'run services list *'){return '[]'}
  if($command -like 'billing projects describe custoking-dev *'){return '{"billingAccountName":"billingAccounts/fixture"}'}
  if($command -like 'billing budgets list *'){return '[{"displayName":"custoking-dev-monthly","amount":{"specifiedAmount":{"currencyCode":"INR","units":"2000","nanos":0}}}]'}
  if($command -like 'auth print-access-token *'){return 'fixture-token'}
  throw 'Unexpected mocked cloud command'
}
function bq.cmd {
  $global:LASTEXITCODE=0
  if($args -notcontains '--project_id=custoking-dev' -or $args[-1] -notmatch '^SELECT'){throw 'Unexpected billing query'}
  return '[{"currency":"INR","matching_rows":"1","gross_cost_inr":"1966.31","latest_export_time":"2026-09-26 13:00:00","latest_usage_end":"2026-09-26 12:00:00"}]'
}
function Invoke-RestMethod {
  param($Uri,$Headers,$TimeoutSec)
  $decoded=[uri]::UnescapeDataString($Uri);$global:capacityFixtureMetricQueries+=,$decoded
  if($decoded -notlike 'https://monitoring.googleapis.com/v3/projects/custoking-dev/timeSeries?*'){throw 'Unexpected mock HTTP destination'}
  $at=[datetime]::UtcNow.AddMinutes(-1).ToString('o');$value=0.1
  if($decoded -like '*monthly_bytes_ingested*'){
    $value=1073741824
    if($decoded -notmatch 'aggregation'){
      if($global:capacityFixtureRawMode -eq 'empty'){return @{}}
      $at=$global:capacityFixtureRawAt
    }
  }
  return @{timeSeries=@(@{points=@(@{interval=@{endTime=$at};value=@{doubleValue=$value}});metric=@{labels=@{resource_type='fixture'}}})}
}
function Expect-Rejected([scriptblock]$Action,[string]$Expected){
  $rejected=$false;try{&$Action|Out-Null}catch{$rejected=$_.Exception.Message -match $Expected}
  if(-not $rejected){throw "Expected fail-closed: $Expected"}
}
$evidence=(& $collector)|ConvertFrom-Json
if($evidence.logging.observedAt -cne $global:capacityFixtureRawAt -or $evidence.logging.observedAt -ceq $evidence.capturedAt -or $evidence.logging.monthGib -ne 1){throw 'Logging timestamp was replaced by query time, or month total changed'}
$raw=@($global:capacityFixtureMetricQueries|Where-Object {$_ -like '*monthly_bytes_ingested*' -and $_ -notmatch 'aggregation'})
$aggregated=@($global:capacityFixtureMetricQueries|Where-Object {$_ -like '*monthly_bytes_ingested*' -and $_ -match 'ALIGN_MAX'})
if($raw.Count -ne 1 -or $aggregated.Count -ne 1){throw 'Require separate raw freshness and bounded month-total queries'}
$global:capacityFixtureRawMode='empty';Expect-Rejected {&$collector} 'Recent raw Logging samples'
$global:capacityFixtureRawMode='fresh';$global:capacityFixtureRawAt=[datetime]::UtcNow.AddHours(-3).ToString('o');Expect-Rejected {&$collector} 'stale or in the future'
$global:capacityFixtureRawAt=[datetime]::UtcNow.AddMinutes(5).ToString('o');Expect-Rejected {&$collector} 'stale or in the future'
Write-Output 'PASS: full mocked collector retains raw Logging freshness, separates aggregation, and rejects missing/stale/future samples; no cloud calls.'

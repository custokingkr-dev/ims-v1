# Strict local command double: unexpected commands fail; there is no native/network fallback.
param([string]$Program,[string[]]$CommandArguments)
$ErrorActionPreference='Stop'
$global:LASTEXITCODE=0
$state=$env:RELEASE_IMAGE_TEST_STATE
if (!$state) { throw 'Fixture state is required.' }
$scenario=Get-Content -LiteralPath (Join-Path $state 'scenario') -Raw
$joined=$CommandArguments -join ' '
$names=@('identity-service','school-core-service','operations-service','platform-service','billing-service','frontend','api-gateway')
$image=@($names | Where-Object { $joined -match "(?:/|=)$($_)(?:[:@ ]|$)" })[0]
if (!$image) { throw "Unexpected fixture command: $Program $joined" }
$index=[Array]::IndexOf($names,$image)
$digest='sha256:' + ([string]($index+1) * 64)
$runtime='sha256:' + ([string]'abcdef0'[$index] * 64)
$changed='sha256:' + ('f' * 64)
$started=[DateTime]::UtcNow.Ticks
$event=@{program=$Program;arguments=$CommandArguments;image=$image;started=$started}
$created=Join-Path $state "$image-created"
try {
  if ($Program -eq 'cosign') {
    if ($CommandArguments[0] -cne 'verify' -or $CommandArguments[1] -cnotmatch "custoking-dev/custoking/$image@$digest$" -or
        $CommandArguments -cnotcontains 'https://github.com/custokingkr-dev/ims-v1/.github/workflows/build-release.yml@refs/heads/dev' -or
        $CommandArguments -cnotcontains 'https://token.actions.githubusercontent.com' -or
        $CommandArguments -ccontains '--certificate-identity-regexp') { throw 'Unexpected signature policy.' }
    if ($scenario -eq 'bad-signature' -or ($scenario -eq 'one-bad-signature' -and $image -eq 'billing-service')) { $global:LASTEXITCODE=1; return 'verification failed' }
    [IO.File]::WriteAllText((Join-Path $state "$image-verified"),'yes')
    return 'verified'
  }
  if ($Program -eq 'gcloud') {
    if ($CommandArguments -notcontains '--project=custoking-dev' -and $CommandArguments -notcontains '--project=custoking-prod') { throw 'Missing explicit project.' }
    if ($joined -like 'artifacts tags create *') {
      if ($CommandArguments -notcontains '--project=custoking-prod' -or $CommandArguments -notcontains "--version=$digest" -or
          $CommandArguments -notcontains '--repository=custoking' -or $CommandArguments -notcontains '--location=asia-south2' -or
          $CommandArguments[3] -cnotmatch '^dev-approved-src-[0-9a-f]{64}$') { throw 'Unexpected tag creation.' }
      if (!(Test-Path (Join-Path $state "$image-copied"))) { throw 'Tag published before immutable copy.' }
      if ($scenario -eq 'race-conflict') { [IO.File]::WriteAllText($created,$changed); $global:LASTEXITCODE=1; return 'ALREADY_EXISTS' }
      [IO.File]::WriteAllText($created,$digest)
      if ($scenario -eq 'race-same') { $global:LASTEXITCODE=1; return 'ALREADY_EXISTS' }
      if ($scenario -eq 'tag-denied') { $global:LASTEXITCODE=1; return 'PERMISSION_DENIED' }
      return '{}'
    }
    if ($joined -notlike 'artifacts docker images describe *') { throw 'Unexpected gcloud command.' }
    $reference=$CommandArguments[4]
    if ($reference -like '*/custoking-dev/*') {
      if ($CommandArguments -notcontains '--project=custoking-dev') { throw 'Wrong source project.' }
      if ($reference -cnotmatch ':(dev-approved-)?src-[0-9a-f]{64}$') { throw 'Source resolution must use source tag.' }
      Start-Sleep -Milliseconds 100
      if ($scenario -eq 'missing-source') { $global:LASTEXITCODE=1; return 'NOT_FOUND' }
      if ($scenario -eq 'malformed-source') { return 'not-a-digest' }
      if ($scenario -eq 'moving-source-tag') {
        $read=Join-Path $state "$image-source-read"
        if (Test-Path $read) { return $changed }
        [IO.File]::WriteAllText($read,'The mutable tag now points to a different image')
      }
      return $digest
    }
    if ($reference -notlike '*/custoking-prod/*' -or $CommandArguments -notcontains '--project=custoking-prod') { throw 'Wrong runtime registry or project.' }
    if ($reference -like '*@sha256:*') {
      if ($scenario -eq 'wrong-promoted') { return $changed }
      return $digest
    }
    if ($scenario -eq 'reuse') { return $digest }
    if ($scenario -eq 'conflict') { return $changed }
    if ($scenario -eq 'target-denied') { $global:LASTEXITCODE=1; return 'PERMISSION_DENIED' }
    if ($scenario -eq 'malformed-target') { return 'not-a-digest' }
    if (Test-Path $created) { return Get-Content -LiteralPath $created -Raw }
    $global:LASTEXITCODE=1; return 'NOT_FOUND'
  }
  if ($Program -eq 'docker') {
    if ($joined -like 'buildx imagetools create *') {
      if (!(Test-Path (Join-Path $state "$image-verified"))) { throw 'Copy occurred before verified signature.' }
      if ($CommandArguments -notcontains '--prefer-index=false' -or $CommandArguments[-2] -cne "asia-south2-docker.pkg.dev/custoking-prod/custoking/$image@$digest" -or
          $CommandArguments[-1] -cne "asia-south2-docker.pkg.dev/custoking-dev/custoking/$image@$digest") { throw 'Copy must preserve format and use source/target digests, without mutable tags.' }
      if ($scenario -eq 'copy-failed') { $global:LASTEXITCODE=1; return 'copy failure' }
      [IO.File]::WriteAllText((Join-Path $state "$image-copied"),'yes')
      return 'copied'
    }
    if ($joined -notlike 'buildx imagetools inspect *' -or $CommandArguments[-1] -cnotmatch '@sha256:[0-9a-f]{64}$') { throw 'Manifest inspection followed a mutable tag.' }
    if ($CommandArguments -contains '--format') {
      if ($scenario -eq 'wrong-platform') { return '{"os":"windows","architecture":"amd64"}' }
      return '{"os":"linux","architecture":"amd64"}'
    }
    if ($scenario -eq 'malformed-manifest') { return 'not-json' }
    $leaf=@{schemaVersion=2;mediaType='application/vnd.oci.image.manifest.v1+json';config=@{digest=$changed};layers=@()}
    if ($scenario -in @('single-manifest','wrong-platform') -or $CommandArguments[-1].EndsWith("@$runtime")) {
      if ($scenario -eq 'missing-runtime') { $global:LASTEXITCODE=1; return 'missing manifest' }
      return $leaf | ConvertTo-Json -Depth 5 -Compress
    }
    $descriptor=@{mediaType='application/vnd.oci.image.manifest.v1+json';digest=$runtime;platform=@{os='linux';architecture='amd64'}}
    if ($scenario -eq 'wrong-runtime' -and $CommandArguments[-1] -like '*/custoking-prod/*') { $descriptor.digest=$changed }
    if ($scenario -eq 'missing-amd64') { $descriptor.platform.architecture='arm64' }
    $descriptors=@($descriptor)
    if ($scenario -eq 'ambiguous-amd64') { $descriptors+=@{mediaType=$descriptor.mediaType;digest=$changed;platform=$descriptor.platform} }
    return @{schemaVersion=2;mediaType='application/vnd.oci.image.index.v1+json';manifests=$descriptors} | ConvertTo-Json -Depth 5 -Compress
  }
  throw 'Unexpected program.'
} finally {
  $event.finished=[DateTime]::UtcNow.Ticks
  $event.exit=$LASTEXITCODE
  [IO.File]::WriteAllText((Join-Path $state ([guid]::NewGuid().ToString('N')+'.event.json')),($event | ConvertTo-Json -Depth 5 -Compress))
}

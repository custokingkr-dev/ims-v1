# Internal worker for resolve-release-images.ps1. All registry reads after tag resolution use digests.
#requires -Version 7.0
param([Parameter(Mandatory)][hashtable]$Work)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

function Invoke-ImageCommand([string]$Program, [string[]]$Arguments) {
  $global:LASTEXITCODE = 0
  $text = (& $Program @Arguments 2>&1 | ForEach-Object { [string]$_ }) -join "`n"
  return @{ ExitCode = $LASTEXITCODE; Text = $text.Trim() }
}

function Read-ImageDigest([string]$Reference, [string]$Project, [switch]$AllowMissing) {
  $result = Invoke-ImageCommand gcloud @('artifacts','docker','images','describe',$Reference,
    "--project=$Project",'--format=value(image_summary.digest)')
  if ($result.ExitCode -ne 0) {
    # Permission, quota and transport errors are never evidence that a tag is absent.
    if ($AllowMissing -and $result.Text -match '\bNOT_FOUND\b') { return $null }
    throw "Could not resolve image $Reference (exit $($result.ExitCode)); refusing promotion."
  }
  if ($result.Text -cnotmatch '^sha256:[0-9a-f]{64}$') { throw "Invalid registry digest for $Reference." }
  return $result.Text
}

function Read-OciManifest([string]$Reference) {
  if ($Reference -cnotmatch '@sha256:[0-9a-f]{64}$') { throw 'OCI inspection requires an immutable digest.' }
  $result = Invoke-ImageCommand docker @('buildx','imagetools','inspect','--raw',$Reference)
  if ($result.ExitCode -ne 0 -or !$result.Text) { throw "Could not inspect OCI manifest for $Reference." }
  try { $manifest = $result.Text | ConvertFrom-Json -AsHashtable } catch { throw "Malformed OCI manifest for $Reference." }
  if ($manifest.schemaVersion -ne 2) { throw "Unsupported OCI schema for $Reference." }
  return $manifest
}

function Get-RuntimeDigest([hashtable]$Manifest, [string]$Reference, [string]$Digest) {
  if ($Manifest.mediaType -in @('application/vnd.oci.image.index.v1+json','application/vnd.docker.distribution.manifest.list.v2+json')) {
    $descriptors = @($Manifest.manifests | Where-Object { $_.platform.os -ceq 'linux' -and $_.platform.architecture -ceq 'amd64' })
    if ($descriptors.Count -ne 1 -or [string]$descriptors[0].digest -cnotmatch '^sha256:[0-9a-f]{64}$' -or
        $descriptors[0].mediaType -notin @('application/vnd.oci.image.manifest.v1+json','application/vnd.docker.distribution.manifest.v2+json')) {
      throw "OCI index $Reference must contain exactly one runnable linux/amd64 manifest."
    }
    return [string]$descriptors[0].digest
  }
  Assert-RunnableManifest $Manifest $Reference
  $config = Invoke-ImageCommand docker @('buildx','imagetools','inspect','--format','{{json .Image}}',$Reference)
  if ($config.ExitCode -ne 0) { throw "Could not inspect runtime platform for $Reference." }
  $image = $config.Text | ConvertFrom-Json -AsHashtable
  if ($image.os -cne 'linux' -or $image.architecture -cne 'amd64') { throw "Image $Reference is not runnable linux/amd64." }
  return $Digest
}

function Assert-RunnableManifest([hashtable]$Manifest, [string]$Reference) {
  if ($Manifest.mediaType -notin @('application/vnd.oci.image.manifest.v1+json','application/vnd.docker.distribution.manifest.v2+json') -or
      [string]$Manifest.config.digest -cnotmatch '^sha256:[0-9a-f]{64}$' -or !$Manifest.ContainsKey('layers')) {
    throw "Invalid runtime image manifest for $Reference."
  }
}

try {
  $entry = $Work.Entry
  $options = $Work.Options
  $metadata = & (Join-Path $PSScriptRoot 'resolve-image-source-id.ps1') -CommitSha $options.CommitSha `
    -Context ([string]$entry.context) -SourcePaths ([string]$entry.source_paths) -BuildArgs ([string]$entry.build_args) | ConvertFrom-Json
  if ($metadata.sourceId -cnotmatch '^[0-9a-f]{64}$' -or $metadata.sourceTag -cne "src-$($metadata.sourceId)") { throw 'Invalid image source metadata.' }
  $resolvedTag = if ($options.Environment -eq 'prod') { "dev-approved-$($metadata.sourceTag)" } else { $metadata.sourceTag }
  $sourceImage = "$($options.SourceRegistry)/$($entry.image)"
  $targetImage = "$($options.RuntimeRegistry)/$($entry.image)"
  $digest = Read-ImageDigest "${sourceImage}:$resolvedTag" $options.SourceProjectId
  $sourceRef = "${sourceImage}@$digest"
  $immutableRef = "${targetImage}@$digest"

  # Every production digest must be signed by this exact workflow on dev, never a regex identity.
  if ($options.Environment -eq 'prod') {
    $verification = Invoke-ImageCommand cosign @('verify',$sourceRef,'--certificate-identity',
      "https://github.com/$($options.GitHubRepository)/.github/workflows/build-release.yml@refs/heads/dev",
      '--certificate-oidc-issuer','https://token.actions.githubusercontent.com','--output','text')
    if ($verification.ExitCode -ne 0) { throw "No valid dev-release signature for $($entry.name) at $digest; refusing promotion." }
  }
  # Describing a mutable tag and then inspecting that same tag was a TOCTOU race.
  $manifest = Read-OciManifest $sourceRef
  $runtimeDigest = Get-RuntimeDigest $manifest $sourceRef $digest

  if ($options.RuntimeRegistry -cne $options.SourceRegistry) {
    $targetTag = "${targetImage}:$resolvedTag"
    $existing = Read-ImageDigest $targetTag $options.ProjectId -AllowMissing
    if ($existing -and $existing -cne $digest) { throw "Promotion tag conflict for $($entry.image): $resolvedTag already points to $existing, not $digest." }
    if (!$existing) {
      # A digest-only destination never writes/repoints a tag. --prefer-index=false also preserves
      # single-manifest bytes instead of wrapping them in a newly hashed index.
      # Buildx ParseLocation/Push supports this; the optional localhost-registry test exercises
      # exact index + child + config bytes and verifies no target tag exists after the copy.
      $copy = Invoke-ImageCommand docker @('buildx','imagetools','create','--prefer-index=false','--tag',$immutableRef,$sourceRef)
      if ($copy.ExitCode -ne 0) { throw "Could not promote $sourceRef into $($options.RuntimeRegistry)." }
    }
    $promoted = Read-ImageDigest $immutableRef $options.ProjectId
    if ($promoted -cne $digest) { throw "Promoted digest does not match approved digest for $($entry.image)." }
    $targetManifest = Read-OciManifest $immutableRef
    if ((Get-RuntimeDigest $targetManifest $immutableRef $digest) -cne $runtimeDigest) { throw "Promoted runtime digest differs for $($entry.image)." }
    if ($runtimeDigest -cne $digest) { Assert-RunnableManifest (Read-OciManifest "${targetImage}@$runtimeDigest") "${targetImage}@$runtimeDigest" }
    if (!$existing) {
      # tags.create is create-only. A concurrent publisher cannot cause this helper to overwrite
      # an existing tag (unlike docker --tag <mutable-tag> or gcloud docker tags add).
      # Uses the existing artifactregistry.writer permission, not a new IAM grant.
      $tag = Invoke-ImageCommand gcloud @('artifacts','tags','create',$resolvedTag,"--version=$digest",
        "--package=$($entry.image)","--repository=$($options.Repository)","--location=$($options.Region)",
        "--project=$($options.ProjectId)",'--quiet','--format=json')
      if ($tag.ExitCode -ne 0 -and $tag.Text -notmatch '\bALREADY_EXISTS\b') { throw "Could not create promotion tag for $($entry.image)." }
    }
    if ((Read-ImageDigest $targetTag $options.ProjectId) -cne $digest) { throw "Promotion tag conflict after publication for $($entry.image)." }
  }

  return @{ Index=$Work.Index; Success=$true; Service=[ordered]@{
    service=[string]$entry.name; image=[string]$entry.image; context=[string]$entry.context
    sourceId=[string]$metadata.sourceId; sourceTag=[string]$metadata.sourceTag; resolvedTag=$resolvedTag
    digest=$digest; immutableRef=$immutableRef; runtimeDigest=$runtimeDigest; runtimeRef="${targetImage}@$runtimeDigest"
  } }
} catch {
  return @{ Index=$Work.Index; Success=$false; Error="$($Work.Entry.name): $($_.Exception.Message)" }
}

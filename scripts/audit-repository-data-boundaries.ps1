param(
    [int64]$MaximumTrackedFileBytes = 10MB
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $repositoryRoot
try {
    # Audit the committed blobs, not the runner's checkout filesystem. A tracked dotfile can be visible
    # to Test-Path and then unavailable to Get-Item on an overlay-backed Linux checkout; more importantly,
    # the repository boundary is the Git object that will be cloned, not a mutable worktree copy.
    $trackedEntries = @(git ls-files --stage)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not enumerate tracked repository files."
    }

    $trackedFiles = [Collections.Generic.List[string]]::new()
    $objectIds = [Collections.Generic.List[string]]::new()
    foreach ($entry in $trackedEntries) {
        $tab = $entry.IndexOf("`t")
        if ($tab -lt 0) {
            throw "Could not parse tracked repository metadata."
        }
        $metadata = $entry.Substring(0, $tab) -split '\s+'
        if ($metadata.Count -ne 3 -or $metadata[2] -ne "0") {
            throw "Repository index contains an unresolved or malformed entry."
        }
        $objectIds.Add($metadata[1])
        $trackedFiles.Add($entry.Substring($tab + 1))
    }

    $objectSizes = @($objectIds | git cat-file --batch-check='%(objectsize)')
    if ($LASTEXITCODE -ne 0 -or $objectSizes.Count -ne $trackedFiles.Count) {
        throw "Could not measure tracked repository blobs."
    }

    $sensitivePathCount = 0
    $oversizedFileCount = 0
    for ($index = 0; $index -lt $trackedFiles.Count; $index++) {
        $relativePath = $trackedFiles[$index]
        $normalized = $relativePath.Replace('\', '/')
        $sensitivePath =
            $normalized -match '(^|/)(outputs?|exports?|photographer-deliverables?|student-photos?)(/|$)' -or
            $normalized -match '(?i)(student|photographer)[-_ ]*(data|export|deliverable).*[.](csv|tsv|xls|xlsx|zip|pdf|jpe?g|png)$'
        if ($sensitivePath) {
            $sensitivePathCount++
        }

        [int64]$length = 0
        if (-not [int64]::TryParse([string]$objectSizes[$index], [ref]$length)) {
            throw "Could not parse a tracked repository blob size."
        }
        if ($length -gt $MaximumTrackedFileBytes) {
            $oversizedFileCount++
        }
    }

    if ($sensitivePathCount -gt 0 -or $oversizedFileCount -gt 0) {
        # Do not print filenames: an export filename can itself contain student information.
        throw "Repository data-boundary audit failed: $sensitivePathCount sensitive export path(s) and $oversizedFileCount oversized tracked file(s)."
    }

    Write-Host "Repository data-boundary audit passed: no tracked export paths and no files over $MaximumTrackedFileBytes bytes."
}
finally {
    Pop-Location
}

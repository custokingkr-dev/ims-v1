param(
    [string]$Repository = "custokingkr-dev/ims-v1",
    [string]$OutputJson = ""
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Test-SensitiveExportPath {
    param([string]$Path)

    $normalized = $Path.Replace('\', '/')
    return $normalized -match '(?i)(^|/)(outputs?|exports?|photographer-deliverables?|student-photos?)(/|$)' -or
        $normalized -match '(?i)(student|photographer)[-_ ]*(data|export|deliverable).*[.](csv|tsv|xls|xlsx|zip|pdf|jpe?g|png)$'
}

Push-Location $repositoryRoot
try {
    $branchSensitivePaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $refs = @(git for-each-ref --format='%(refname)' refs/heads refs/remotes/origin)
    if ($LASTEXITCODE -ne 0) { throw "Could not enumerate local and origin branch refs." }
    foreach ($ref in $refs) {
        $paths = @(git ls-tree -r --name-only $ref)
        if ($LASTEXITCODE -ne 0) { throw "Could not inspect branch tree without printing its paths." }
        foreach ($path in $paths) {
            if (Test-SensitiveExportPath $path) { [void]$branchSensitivePaths.Add($path) }
        }
    }

    $historySensitivePaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $objects = @(git rev-list --objects --all)
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect repository object history." }
    foreach ($object in $objects) {
        $separator = $object.IndexOf(' ')
        if ($separator -lt 0) { continue }
        $path = $object.Substring($separator + 1)
        if (Test-SensitiveExportPath $path) { [void]$historySensitivePaths.Add($path) }
    }

    if ([string]::IsNullOrWhiteSpace($env:GH_TOKEN)) {
        $authStatus = & gh auth status 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "GitHub authentication is required to inspect release assets."
        }
    }
    $releaseJson = (& gh api "repos/$Repository/releases" --paginate --slurp) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect GitHub release assets." }
    $releaseSensitiveAssetCount = 0
    $releaseCount = 0
    $releaseAssetCount = 0
    if (-not [string]::IsNullOrWhiteSpace($releaseJson)) {
        $releasePages = @($releaseJson | ConvertFrom-Json)
        foreach ($page in $releasePages) {
            foreach ($release in @($page)) {
                $releaseCount++
                foreach ($asset in @($release.assets)) {
                    $releaseAssetCount++
                    if (Test-SensitiveExportPath ([string]$asset.name)) { $releaseSensitiveAssetCount++ }
                }
            }
        }
    }

    $evidence = [ordered]@{
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        repository = $Repository
        inspectedBranchRefCount = $refs.Count
        branchSensitivePathCount = $branchSensitivePaths.Count
        historySensitivePathCount = $historySensitivePaths.Count
        inspectedReleaseCount = $releaseCount
        inspectedReleaseAssetCount = $releaseAssetCount
        releaseSensitiveAssetCount = $releaseSensitiveAssetCount
    }

    if ($branchSensitivePaths.Count -gt 0 -or
        $historySensitivePaths.Count -gt 0 -or
        $releaseSensitiveAssetCount -gt 0) {
        # Never print the names: a filename can itself contain student information.
        throw "Repository export-history audit failed: branch=$($branchSensitivePaths.Count), history=$($historySensitivePaths.Count), releases=$releaseSensitiveAssetCount."
    }

    if (-not [string]::IsNullOrWhiteSpace($OutputJson)) {
        $resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputJson))
        [System.IO.File]::WriteAllText($resolvedOutput, ($evidence | ConvertTo-Json -Depth 5), [System.Text.UTF8Encoding]::new($false))
    }
    $evidence | ConvertTo-Json -Depth 5
}
finally {
    Pop-Location
}

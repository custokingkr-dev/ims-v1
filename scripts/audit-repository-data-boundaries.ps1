param(
    [int64]$MaximumTrackedFileBytes = 10MB
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $repositoryRoot
try {
    $trackedFiles = @(git ls-files)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not enumerate tracked repository files."
    }

    $sensitivePathCount = 0
    $oversizedFileCount = 0
    foreach ($relativePath in $trackedFiles) {
        $normalized = $relativePath.Replace('\', '/')
        $sensitivePath =
            $normalized -match '(^|/)(outputs?|exports?|photographer-deliverables?|student-photos?)(/|$)' -or
            $normalized -match '(?i)(student|photographer)[-_ ]*(data|export|deliverable).*[.](csv|tsv|xls|xlsx|zip|pdf|jpe?g|png)$'
        if ($sensitivePath) {
            $sensitivePathCount++
        }

        $absolutePath = Join-Path $repositoryRoot $relativePath
        if (Test-Path -LiteralPath $absolutePath -PathType Leaf) {
            $length = (Get-Item -LiteralPath $absolutePath).Length
            if ($length -gt $MaximumTrackedFileBytes) {
                $oversizedFileCount++
            }
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

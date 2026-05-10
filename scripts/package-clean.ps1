# Creates a clean ZIP of the project source, excluding build outputs and secrets.
param(
    [string]$OutputPath = ""
)

$RootDir = Split-Path -Parent $PSScriptRoot
if (-not $OutputPath) {
    $OutputPath = Join-Path $RootDir "custoking-ims-clean.zip"
}

$TempDir = Join-Path ([System.IO.Path]::GetTempPath()) "custoking-ims-pkg-$(Get-Random)"
$SourceDir = Join-Path $TempDir "custoking-ims"

Write-Host "Packaging from: $RootDir"
Write-Host "Output:         $OutputPath"

$excludePatterns = @(
    '.git', 'node_modules', 'dist', 'target', '*.log', '*.tmp',
    '.env', '.env.*', 'uploads', 'custoking-ims-clean.zip',
    '.DS_Store', '.idea', 'coverage', 'playwright-report', 'test-results'
)

# Copy files excluding the patterns
New-Item -ItemType Directory -Force -Path $SourceDir | Out-Null

$items = Get-ChildItem -Path $RootDir -Force | Where-Object {
    $name = $_.Name
    $excluded = $false
    foreach ($p in $excludePatterns) {
        if ($name -like $p) { $excluded = $true; break }
    }
    -not $excluded
}

foreach ($item in $items) {
    $dest = Join-Path $SourceDir $item.Name
    if ($item.PSIsContainer) {
        Copy-Item -Path $item.FullName -Destination $dest -Recurse -Force
        # Remove nested excluded dirs
        foreach ($p in @('node_modules', 'target', 'dist', '.git', 'uploads')) {
            $nested = Join-Path $dest $p
            if (Test-Path $nested) { Remove-Item $nested -Recurse -Force }
        }
    } else {
        Copy-Item -Path $item.FullName -Destination $dest -Force
    }
}

if (Test-Path $OutputPath) { Remove-Item $OutputPath -Force }
Compress-Archive -Path $SourceDir -DestinationPath $OutputPath -CompressionLevel Optimal

Remove-Item $TempDir -Recurse -Force

$size = (Get-Item $OutputPath).Length
Write-Host ""
Write-Host "Done! Clean ZIP: $OutputPath ($([math]::Round($size/1MB,1)) MB)"

# package-clean.ps1 — create a clean source ZIP for distribution.
# Usage: .\package-clean.ps1 [-Output custoking-ims-clean.zip]
# Requires: 7-Zip (7z.exe) or the built-in Compress-Archive cmdlet (fallback).

param(
    [string]$Output = "custoking-ims-clean.zip"
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

Write-Host "==> Building clean source package: $Output" -ForegroundColor Cyan
Write-Host "    Source root: $ScriptDir"

# Remove previous ZIP if it exists.
if (Test-Path $Output) { Remove-Item $Output -Force }

# Patterns to exclude from the ZIP.
$Excludes = @(
    '.git',
    'frontend\node_modules',
    'frontend\dist',
    'backend\target',
    'target',          # catch any nested target directory
    'node_modules',
    'dist',
    'build',
    '.idea',
    '.vscode',
    'logs',
    'tmp',
    'uploads',
    'coverage',
    'playwright-report',
    'test-results',
    $Output
)

# Collect all items, excluding unwanted paths.
$AllItems = Get-ChildItem -Path $ScriptDir -Recurse -Force |
    Where-Object {
        $RelPath = $_.FullName.Substring($ScriptDir.Length + 1)
        $excluded = $false
        foreach ($ex in $Excludes) {
            if ($RelPath -like "$ex*" -or $RelPath -like "*\$ex\*" -or $RelPath -like "*\$ex") {
                $excluded = $true
                break
            }
        }
        # Also exclude .env files (but allow .env.example)
        if ($_.Name -like '.env' -or ($_.Name -like '.env.*' -and $_.Name -ne '.env.example')) {
            $excluded = $true
        }
        # Exclude *.log, *.tmp files
        if ($_.Name -like '*.log' -or $_.Name -like '*.tmp') {
            $excluded = $true
        }
        -not $excluded
    } |
    Where-Object { -not $_.PSIsContainer } |
    Select-Object -ExpandProperty FullName

Write-Host "    Packaging $($AllItems.Count) files..."

# Use Compress-Archive (built-in, no 7-Zip required).
$AllItems | ForEach-Object {
    $RelPath = $_.Substring($ScriptDir.Length + 1)
    [PSCustomObject]@{ Source = $_; Dest = $RelPath }
} | ForEach-Object -Begin {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open(
        (Join-Path $ScriptDir $Output),
        [System.IO.Compression.ZipArchiveMode]::Create
    )
} -Process {
    $entry = $zip.CreateEntry($_.Dest, [System.IO.Compression.CompressionLevel]::Optimal)
    $stream = $entry.Open()
    $fs = [System.IO.File]::OpenRead($_.Source)
    $fs.CopyTo($stream)
    $fs.Close()
    $stream.Close()
} -End {
    $zip.Dispose()
}

$Size = [math]::Round((Get-Item $Output).Length / 1MB, 2)
Write-Host ""
Write-Host "==> Done. Package: $Output ($Size MB)" -ForegroundColor Green
Write-Host ""
Write-Host "    Included:"
Write-Host "      - Source code (backend/src, frontend/src)"
Write-Host "      - Flyway migrations"
Write-Host "      - Dockerfiles + docker-compose.yml"
Write-Host "      - GitHub Actions (.github/)"
Write-Host "      - Cloud Build (cloudbuild.yaml)"
Write-Host "      - README files"
Write-Host "      - .env.example"
Write-Host ""
Write-Host "    Excluded:"
Write-Host "      - .git/, node_modules/, dist/, target/"
Write-Host "      - .env, .env.*, logs/, tmp/, uploads/"
Write-Host "      - IDE files (.idea/, .vscode/)"

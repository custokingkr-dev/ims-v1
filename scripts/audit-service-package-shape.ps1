$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$servicesRoot = Join-Path $repoRoot "services"
$violations = New-Object System.Collections.Generic.List[string]

function Get-JavaPackage {
    param([string]$Path)

    $line = Select-String -Path $Path -Pattern '^package\s+([^;]+);' -ErrorAction Stop | Select-Object -First 1
    if (-not $line) {
        return $null
    }
    return $line.Matches[0].Groups[1].Value
}

foreach ($file in Get-ChildItem -Path $servicesRoot -Recurse -Filter "*.java") {
    $relativePath = $file.FullName.Substring($repoRoot.Path.Length + 1).Replace("\", "/")
    if ($relativePath -notmatch '^services/[^/]+/src/main/java/') {
        continue
    }

    $package = Get-JavaPackage $file.FullName
    if (-not $package) {
        $violations.Add("Missing package declaration: $relativePath")
        continue
    }

    if ($file.Name -like "*CompatibilityController.java") {
        if ($relativePath -notmatch '/api/compat/' -or $package -notmatch '\.api\.compat$') {
            $violations.Add("Compatibility controller must live in api.compat: $relativePath package=$package")
        }
    }

    if ($file.Name -like "*PubSubPushController.java") {
        if ($relativePath -notmatch '/api/internal/' -or $package -notmatch '\.api\.internal$') {
            $violations.Add("Pub/Sub push controller must live in api.internal: $relativePath package=$package")
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Service package shape violations found:"
    $violations | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host "Service package shape audit passed: compatibility and internal push controllers are in normalized packages."

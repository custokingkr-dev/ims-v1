$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$servicesRoot = Join-Path $repoRoot "services"
$violations = New-Object System.Collections.Generic.List[string]

foreach ($file in Get-ChildItem -Path $servicesRoot -Recurse -Include "application.yml", "*.java" -File) {
    $relativePath = $file.FullName.Substring($repoRoot.Path.Length + 1).Replace("\", "/")
    if ($relativePath -match '/target/') {
        continue
    }

    $source = Get-Content -Raw -Path $file.FullName

    if ($source -match '\$\{[A-Z0-9_]*DB_SCHEMA:public\}') {
        $violations.Add("DB schema placeholder must not default to public: $relativePath")
    }

    if ($file.Extension -eq ".java" -and $source -match '\.db\.schema:public') {
        $violations.Add("Injected service schema must not default to public: $relativePath")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Service schema default violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Service schema default audit passed: DB schema defaults do not fall back to public."

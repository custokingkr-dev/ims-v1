param(
    [string]$ServicesRoot = "services"
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path $ServicesRoot
$violations = New-Object System.Collections.Generic.List[string]

Get-ChildItem -Path $root -Recurse -Filter *.java | ForEach-Object {
    $source = Get-Content -Raw $_.FullName
    if ($source -match "public\.") {
        $relative = Resolve-Path -Relative $_.FullName
        $violations.Add("Service runtime Java references public schema directly: $relative")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Microservice runtime boundary violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Microservice runtime boundary audit passed: service Java code does not reference public schema directly."

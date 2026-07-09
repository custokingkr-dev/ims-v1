param(
    [string[]]$Services
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-test-catalog.ps1")
. (Join-Path $PSScriptRoot "local-java.ps1")

$catalog = @(Get-MicroserviceTestCatalog)

if ($Services -and $Services.Count -gt 0) {
    $requested = @{}
    foreach ($serviceName in $Services) {
        $requested[$serviceName] = $true
    }
    $selected = @($catalog | Where-Object { $requested.ContainsKey($_.Name) })
    $selectedNames = @{}
    foreach ($service in $selected) {
        $selectedNames[$service.Name] = $true
    }
    $unknown = @($Services | Where-Object { -not $selectedNames.ContainsKey($_) })
    if ($unknown.Count -gt 0) {
        throw "Unknown test service(s): $($unknown -join ', ')."
    }
} else {
    $selected = $catalog
}

foreach ($service in $selected) {
    $servicePath = Join-Path $repoRoot $service.Path
    if (-not (Test-Path $servicePath)) {
        throw "Service test path does not exist for $($service.Name): $($service.Path)"
    }

    Write-Host ""
    Write-Host "==> test $($service.Name)"
    Push-Location $servicePath
    try {
        if ($service.Tool -eq "maven") {
            $mavenWrapper = Join-Path $repoRoot "mvnw.cmd"
            if (-not (Test-Path $mavenWrapper)) {
                throw "Root Maven wrapper was not found: $mavenWrapper"
            }
            $javaHome = Resolve-RequiredJavaHome -MinimumMajor 25
            $previousJavaHome = $env:JAVA_HOME
            $previousPath = $env:Path
            $env:JAVA_HOME = $javaHome
            $env:Path = "$javaHome\bin;$env:Path"
            Write-Host "Using JAVA_HOME=$javaHome"
            & $mavenWrapper -B -f (Join-Path $servicePath "pom.xml") test --no-transfer-progress
        } else {
            $command = @($service.Command)
            & $command[0] @($command | Select-Object -Skip 1)
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Test command failed for $($service.Name) with exit code $LASTEXITCODE."
        }
    } finally {
        if (Get-Variable -Name previousJavaHome -Scope Local -ErrorAction SilentlyContinue) {
            $env:JAVA_HOME = $previousJavaHome
            Remove-Variable -Name previousJavaHome -Scope Local -ErrorAction SilentlyContinue
        }
        if (Get-Variable -Name previousPath -Scope Local -ErrorAction SilentlyContinue) {
            $env:Path = $previousPath
            Remove-Variable -Name previousPath -Scope Local -ErrorAction SilentlyContinue
        }
        Pop-Location
    }
    Write-Host "OK: test $($service.Name)"
}

Write-Host ""
Write-Host "Microservice test verification completed for $($selected.Count) service(s)."

param(
    [string[]]$Services
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-test-catalog.ps1")

$catalog = @(Get-MicroserviceTestCatalog)

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javac.exe"))) {
        return $env:JAVA_HOME
    }

    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $candidate = Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
        if (Test-Path (Join-Path $candidate "bin/javac.exe")) {
            return $candidate
        }
    }

    $candidateRoots = @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Java",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\JetBrains"
    )

    foreach ($root in $candidateRoots) {
        if (-not (Test-Path $root)) {
            continue
        }
        $javac = Get-ChildItem -Path $root -Recurse -Filter javac.exe -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($javac) {
            return Split-Path -Parent (Split-Path -Parent $javac.FullName)
        }
    }

    return $null
}

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
        if ($service.Tool -eq "maven" -and -not (Get-Command mvn -ErrorAction SilentlyContinue)) {
            $mavenWrapper = Join-Path $repoRoot "mvnw.cmd"
            if (-not (Test-Path $mavenWrapper)) {
                throw "Maven is not on PATH and backend Maven wrapper was not found."
            }
            $javaHome = Resolve-JavaHome
            if (-not $javaHome) {
                throw "Java/JDK is not configured. Install JDK 21 or set JAVA_HOME to a JDK before running Maven service tests."
            }
            $previousJavaHome = $env:JAVA_HOME
            $env:JAVA_HOME = $javaHome
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
        Pop-Location
    }
    Write-Host "OK: test $($service.Name)"
}

Write-Host ""
Write-Host "Microservice test verification completed for $($selected.Count) service(s)."

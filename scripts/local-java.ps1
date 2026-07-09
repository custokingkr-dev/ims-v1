function Get-JavaMajorVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$JavaHome
    )

    $javaExe = Join-Path $JavaHome "bin\java.exe"
    if (-not (Test-Path $javaExe)) {
        $javaExe = Join-Path $JavaHome "bin/java"
    }
    if (-not (Test-Path $javaExe)) {
        return $null
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $process.StartInfo.FileName = $javaExe
    $process.StartInfo.Arguments = "-version"
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.UseShellExecute = $false
    [void]$process.Start()
    $versionOutput = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    if ($versionOutput -match 'version "([0-9]+)(?:\.|")') {
        return [int]$matches[1]
    }
    return $null
}

function Resolve-RequiredJavaHome {
    param(
        [int]$MinimumMajor = 25
    )

    $candidateMap = [ordered]@{}

    function Add-JavaCandidate {
        param([string]$Path)
        if (-not $Path) {
            return
        }
        $resolved = Resolve-Path $Path -ErrorAction SilentlyContinue
        if (-not $resolved) {
            return
        }
        $candidateHome = $resolved.Path.TrimEnd('\', '/')
        $javac = Join-Path $candidateHome "bin\javac.exe"
        if (-not (Test-Path $javac)) {
            $javac = Join-Path $candidateHome "bin/javac"
        }
        if (Test-Path $javac) {
            $candidateMap[$candidateHome.ToLowerInvariant()] = $candidateHome
        }
    }

    Add-JavaCandidate $env:JAVA_HOME

    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) {
        Add-JavaCandidate (Split-Path -Parent (Split-Path -Parent $javaCommand.Source))
    }

    $candidateRoots = @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\JetBrains"
    )

    foreach ($root in $candidateRoots) {
        if (-not (Test-Path $root)) {
            continue
        }
        Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Add-JavaCandidate $_.FullName }
        Get-ChildItem -Path $root -Recurse -Filter javac.exe -ErrorAction SilentlyContinue |
            ForEach-Object { Add-JavaCandidate (Split-Path -Parent (Split-Path -Parent $_.FullName)) }
    }

    $matches = foreach ($candidate in $candidateMap.Values) {
        $major = Get-JavaMajorVersion -JavaHome $candidate
        if ($major -and $major -ge $MinimumMajor) {
            [pscustomobject]@{ Home = $candidate; Major = $major }
        }
    }

    $selected = $matches | Sort-Object -Property Major, Home -Descending | Select-Object -First 1
    if (-not $selected) {
        throw "JDK $MinimumMajor or newer is required. Install JDK $MinimumMajor and set JAVA_HOME, or add it to PATH."
    }

    return $selected.Home
}

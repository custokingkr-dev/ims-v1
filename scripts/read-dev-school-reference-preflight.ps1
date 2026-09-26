param(
  [Parameter(Mandatory = $true)][ValidateSet("custoking-dev")][string]$ProjectId,
  [string]$OutputPath = "artifacts/product-dev-release-2026-09-26/school-1-reference-preflight.json"
)

# Read-only business preflight. Login/logout are its only POSTs. Credentials, tokens,
# raw API bodies, and bootstrap SQL remain in process memory and are never reported.
$ErrorActionPreference = "Stop"
$gcloud = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
$base = "https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app/api/v1"
$schoolId = 1
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$headers = @{}
$checks = New-Object System.Collections.Generic.List[object]
$authenticated = $false
$report = [ordered]@{ project=$ProjectId; schoolId=$schoolId; checkedAt=(Get-Date).ToUniversalTime().ToString("o"); businessWritesPerformed=$false }

function Read-Secret([string]$Name) {
  $value = (& $gcloud secrets versions access latest "--secret=$Name" "--project=$ProjectId" 2>$null) -join "`n"
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($value)) { throw "Required bootstrap credential could not be read; no credential content is reported." }
  return $value.Trim()
}
function Invoke-SafeApi([string]$Method, [string]$Path, [object]$Body = $null) {
  if ($Method -ne "GET" -and $Path -notin @("/auth/login", "/auth/logout")) { throw "Business writes are prohibited by this preflight." }
  $arguments = @{ Uri="$base$Path"; Method=$Method; WebSession=$session; Headers=$headers; TimeoutSec=58; MaximumRedirection=0; UseBasicParsing=$true }
  if ($null -ne $Body) { $arguments.Body=$Body | ConvertTo-Json -Compress -Depth 5; $arguments.ContentType="application/json" }
  try {
    $response = Invoke-WebRequest @arguments
    $data = if ([string]::IsNullOrWhiteSpace($response.Content)) { $null } else { $response.Content | ConvertFrom-Json }
    $checks.Add([ordered]@{ path=$Path; httpStatus=[int]$response.StatusCode; ok=$true })
    return [pscustomobject]@{ ok=$true; data=$data; status=[int]$response.StatusCode }
  } catch {
    $status = 0
    if ($_.Exception.Response) { try { $status=[int]$_.Exception.Response.StatusCode } catch { } }
    $checks.Add([ordered]@{ path=$Path; httpStatus=$status; ok=$false })
    return [pscustomobject]@{ ok=$false; data=$null; status=$status }
  }
}

try {
  $seed = Read-Secret "seed-superadmin-sql"
  $emails = @([regex]::Matches($seed, '[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}') | ForEach-Object { $_.Value.ToLowerInvariant() } | Sort-Object -Unique)
  $seed = $null
  if ($emails.Count -ne 1) { throw "Bootstrap seed did not contain exactly one distinct email; refusing to guess or print candidates." }
  $password = Read-Secret "superadmin-password-dev"
  $login = Invoke-SafeApi "POST" "/auth/login" @{ email=$emails[0]; password=$password }
  $password=$null; $emails=$null
  if (-not $login.ok -or $login.data.role -ne "SUPERADMIN" -or [string]::IsNullOrWhiteSpace($login.data.accessToken)) {
    throw "Dev bootstrap authentication did not return the expected role/token (HTTP $($login.status)); no response body is reported."
  }
  $authenticated=$true
  $headers.Authorization="Bearer $($login.data.accessToken)"
  $report.actorRole=$login.data.role; $login=$null

  $school = Invoke-SafeApi "GET" "/schools/1"
  if (-not $school.ok -or $school.data.id -ne 1) { throw "School 1 could not be verified; no further school queries were performed." }
  $report.school=[ordered]@{ id=1; name=$school.data.name; active=$school.data.active; syntheticLabel=([string]$school.data.name -match '(?i)demo|test|synthetic|local'); configuredClassCount=$school.data.configuredClassCount; configuredSectionCount=$school.data.configuredSectionCount; academicYearStartMonth=$school.data.academicYearStartMonth; timeZone=$school.data.timeZone }

  $modules = Invoke-SafeApi "GET" "/schools/1/modules/active"
  $report.enabledModules=if ($modules.ok) { @($modules.data | ForEach-Object { if ($_.moduleCode) { $_.moduleCode } elseif ($_.code) { $_.code } }) } else { $null }
  $classes = Invoke-SafeApi "GET" "/classes?schoolId=1"
  $report.classes=if ($classes.ok) { @($classes.data | ForEach-Object { [ordered]@{ id=$_.id; name=$_.name; sortOrder=$_.sortOrder } }) } else { $null }

  # The school-scoped academic-years GET can ensure a year row. Read global definitions instead
  # and calculate school 1's current year using the same configured-start-month rule.
  $years = Invoke-SafeApi "GET" "/academic-years"
  $today = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,"India Standard Time")
  $startMonth = [int]$school.data.academicYearStartMonth
  if ($startMonth -lt 1 -or $startMonth -gt 12) { $startMonth=4 }
  $startYear = if ($today.Month -ge $startMonth) { $today.Year } else { $today.Year-1 }
  $yearId = "ay_${startYear}_$(([string]($startYear+1)).Substring(2))"
  $current = @($years.data | Where-Object { $_.id -eq $yearId } | Select-Object -First 1)
  $report.currentAcademicYear=[ordered]@{ id=$yearId; calculatedFromSchoolStartMonth=$startMonth; definitionExists=($years.ok -and $current.Count -eq 1); label=$(if ($current.Count) { $current[0].label } else { $null }) }

  # Explicit existing year avoids feeStructure's fallback ensure/create behavior.
  if ($years.ok -and $current.Count -eq 1) {
    $fees = Invoke-SafeApi "GET" "/fees/structure?schoolId=1&academicYearId=$yearId"
    $report.feeStructure=if ($fees.ok) { [ordered]@{ academicYearId=$fees.data.academicYearId; bandCount=@($fees.data.bands).Count; bands=@($fees.data.bands | ForEach-Object { [ordered]@{ id=$_.id; status=$_.status; classFrom=$_.classFrom; classTo=$_.classTo; activeSchedules=@($_.activeSchedules); itemCount=@($_.items).Count; installmentCount=@($_.installments).Count; revision=$_.revision } }); publishedBandCount=@($fees.data.bands | Where-Object { $_.status -eq "PUBLISHED" }).Count } } else { $null }
  } else { $report.feeStructure=[ordered]@{ skipped=$true; reason="Current-year definition was not verified; no ensure-on-read endpoint was called." } }
  $annual = Invoke-SafeApi "GET" "/catalog/annual-plan/items?schoolId=1&academicYearId=$yearId&limit=500"
  $report.annualPlan=if ($annual.ok) { [ordered]@{ academicYearId=$yearId; currentItemCount=@($annual.data).Count; maximumReturned=500; countMayBeTruncated=(@($annual.data).Count -ge 500) } } else { $null }
  $admin = Invoke-SafeApi "GET" "/schools/1/admin"
  $report.schoolAdminReference=if ($admin.ok) { [ordered]@{ configured=(-not [string]::IsNullOrWhiteSpace($admin.data.email)); emailLooksSynthetic=([string]$admin.data.email -match '(?i)demo|test|synthetic|local|example\.(test|com|invalid)'); emailReported=$false; credentialsVerified=$false } } else { $null }
  $report.completed=$true
} catch {
  # Messages thrown here are fixed safe descriptions; raw HTTP/SQL/credential exceptions are not emitted.
  $report.completed=$false
  $report.failure="Preflight stopped before completing all required checks. Inspect safe HTTP status summaries; no credentials or raw bodies were saved."
} finally {
  if ($authenticated) { $logout = Invoke-SafeApi "POST" "/auth/logout"; $report.logoutConfirmed=$logout.ok }
  $headers.Clear(); $seed=$null; $password=$null; $emails=$null; $login=$null; $session=$null
}
$report.httpChecks=@($checks.ToArray())
$parent = Split-Path -Parent $OutputPath
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
$json=$report | ConvertTo-Json -Depth 9
Set-Content -LiteralPath $OutputPath -Value $json -Encoding UTF8
Write-Output $json
if (-not $report.completed) { exit 1 }

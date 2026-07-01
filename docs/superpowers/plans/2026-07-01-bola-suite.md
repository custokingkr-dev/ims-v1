# BOLA Regression Suite Implementation Plan (Phase 1, Task 1.5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A CI-required PowerShell gate that drives the running full split-service stack through the gateway with two real school-admin tokens and proves a tenant-A token can never read tenant-B data on the covered list/detail endpoints.

**Architecture:** Extend the existing local-dev seed to create a second tenant (school 2) with its own admin and known per-school objects, then add `scripts/audit-tenant-isolation.ps1` — a smoke-style gate (reusing the login/`Invoke` pattern from `scripts/smoke-microservice-features.ps1`) that logs in as admin-A/admin-B/superadmin, asserts a positive baseline (each admin sees only its own seeded object), then runs cross-tenant probes (list `?schoolId=B` and detail `/{B-id}`) asserting no B data leaks. Wired into `verify-microservice-migration.ps1` and CI as a required step.

**Tech Stack:** PowerShell 5.1 (Windows runner + the repo's existing `.ps1` smoke convention), Docker Compose `--profile full` stack, the gateway (`http://localhost`), `Invoke-RestMethod`, Postgres (psql via `docker exec`).

**Spec:** `docs/superpowers/specs/2026-07-01-bola-suite-design.md`

## Global Constraints

- **Harness = PowerShell against the running stack via the gateway.** Reuse the proven pattern in `scripts/smoke-microservice-features.ps1`: `POST /api/v1/auth/login {email,password}` → `$response.accessToken`; call endpoints with `Authorization: Bearer <token>`; send `X-Forwarded-For` on login (the gateway rate-limits by IP). PowerShell 5.1 syntax (no `&&`/ternary; `if/else`).
- **The gateway MUST run in `GATEWAY_AUTH_MODE=enforce`.** This is discovered-and-required: at `services/api-gateway/server.js:194` the gateway only introspects the JWT and injects `X-Authenticated-School-Id` when `AUTH_MODE !== 'permissive'`. The local compose default is `permissive` (no tenant header → with RLS active every school-scoped query returns 0 rows → the gate can't demonstrate isolation, and even the positive baseline fails). Bring the stack (or at least the gateway) up with the committed overlay `docker-compose.bola.yml` (`GATEWAY_AUTH_MODE: enforce`): `docker compose -f docker-compose.yml -f docker-compose.bola.yml up -d --force-recreate api-gateway`. enforce is also the faithful prod config. Verified: in enforce mode admin-A sees its own student (200) and is denied the other tenant's (404); in permissive both return empty.
- **Fresh-stack provisioning order (local + CI):** after `up`, run `scripts/ensure-app-rt-local.ps1` (grants `app_rt` USAGE+DML on all schemas — a fresh volume boots without these and login 500s "permission denied for schema identity") THEN `scripts/ensure-local-dev-users.ps1` (seed) THEN the gate. The gateway must be in enforce mode (overlay) before the gate runs.
- **The gate fails closed.** A login/setup failure is an **infra error** (exit code `2`, distinct message) — never a silent pass. A detected leak or a baseline miss is a **gate failure** (exit code `1`). All probes isolated + baselines pass → exit `0`.
- **Single source of truth for fixture ids.** The known ids the seed inserts and the gate probes MUST be identical constants: `SchoolA=1`, `SchoolB=2`, `StudentA=9000001`, `StudentB=9000002`, `OrderA='ord-bola-a'`, `OrderB='ord-bola-b'`, `FfA='ff-bola-a'`, `FfB='ff-bola-b'`. Define them in the gate script header; the seed SQL uses the same literals.
- **Oracle (per probe type):**
  - **detail** (`GET <path>/{B-id}` as token-A): pass iff HTTP `403`/`404`, OR `200` whose body does NOT contain the B object's id/marker. Fail iff `200` carrying B's marker.
  - **list — marker-backed** (a domain with a seeded B row: students, catalog orders, ff requests): `GET <path>?schoolId=B` as token-A must NOT contain the B marker id. Fail iff the B marker appears.
  - **list — own-scope-equivalence** (domains without a seeded B row): `GET <path>?schoolId=B` as token-A must return the SAME records as token-A's own `GET <path>?schoolId=A` (the server ignores the client param and scopes to A). Fail iff the two differ (the cross-tenant param changed the result → it leaked B's scope). A `403`/empty on the cross-tenant call also passes (deny).
- **Positive baseline (anti-false-green), asserted before probes:** token-A `GET /students/{StudentA}?schoolId=A` → `200` containing StudentA; token-B `GET /students/{StudentB}?schoolId=B` → `200` containing StudentB; `StudentA != StudentB`; superadmin sees both. A baseline miss fails the gate (the fixture or scoping is broken — probes would be meaningless).
- **Coverage is explicit, not silently capped.** The gate prints a summary: probed endpoints (with vector + oracle kind) and the endpoints deliberately excluded as non-tenant-scoped. The manifest is a flat in-script list — one line per endpoint.
- **Idempotent seed.** All seed additions use `ON CONFLICT … DO UPDATE/NOTHING` like the existing script; re-running is safe. Explicit-id inserts bump the relevant sequence with `setval(...)` as the existing script already does.
- **Excluded by design (logged, not probed):** `/api/v1/supply/catalog-categories`, `/api/v1/fee-structure`, `/api/v1/students/import/template`, and superadmin-only RBAC/zones/schools endpoints (guarded by `requireSuperAdmin`). Write-path BOLA is out of scope (RLS `WITH CHECK` backstops writes).

---

### Task 1: Two-tenant fixture — extend `scripts/ensure-local-dev-users.ps1`

**Files:**
- Modify: `scripts/ensure-local-dev-users.ps1` (add school 2 + section 2A + admin-2 + known per-school objects)

**Interfaces:**
- Produces (consumed by Tasks 2–3): seeded fixture with fixed ids — school `2`; user `local-admin2@custoking.local` (role `ADMIN`, scoped to school 2), password `password`; students `9000001`(school 1)/`9000002`(school 2); catalog orders `ord-bola-a`/`ord-bola-b`; ff requests `ff-bola-a`/`ff-bola-b`.

NOTE: the existing `$sql` here-string already seeds school 1, section `1A`, the roles/permissions, and a `dev_users` CTE that auto-creates each user's `app_users` row AND its scoped `user_role_assignments` row from a `VALUES` tuple `(full_name, email, role_name, school_id, school_name, zone_id, zone_name)`. Adding a school-2 admin is therefore just one more `VALUES` row — the CTE wires the scoped assignment identically to the school-1 admin (this is what makes admin-2's JWT carry `school_id=2`). Read the current here-string first to place each addition correctly.

- [ ] **Step 1: Add school 2 + a school-2 section**

In the here-string, after the existing `tenant_school.schools` upsert for school 1, add school 2; after the `school_sections` `1A` upsert, add `2A` (school 2). Insert:
```sql
INSERT INTO tenant_school.schools
    (id, name, short_code, city, state, contact_email, contact_phone, active,
     configured_class_count, configured_section_count, created_at)
VALUES
    (2, 'Local Demo School Two', 'LOCAL2', 'Bengaluru', 'KA',
     'local-demo-school-two@custoking.local', '9999999998', true, 1, 1, now())
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name, short_code = EXCLUDED.short_code, city = EXCLUDED.city,
    state = EXCLUDED.state, contact_email = EXCLUDED.contact_email,
    contact_phone = EXCLUDED.contact_phone, active = EXCLUDED.active,
    configured_class_count = EXCLUDED.configured_class_count,
    configured_section_count = EXCLUDED.configured_section_count;

INSERT INTO tenant_school.school_sections (id, name, teacher_name, active, school_class_id, school_id)
VALUES ('2A', 'A', 'Local Teacher Two', true, '1', 2)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name, teacher_name = EXCLUDED.teacher_name, active = EXCLUDED.active,
    school_class_id = EXCLUDED.school_class_id, school_id = EXCLUDED.school_id;
```
(`school_classes` has no `school_id` — class `'1'` is shared; only sections are school-scoped.)

- [ ] **Step 2: Add the school-2 admin to the `dev_users` CTE**

In the `dev_users(...) AS (VALUES …)` list, add one row after the existing `'Local Admin'` row:
```sql
        ('Local Admin Two', 'local-admin2@custoking.local', 'ADMIN', 2::bigint, 'Local Demo School Two'::varchar, NULL::bigint, NULL::varchar),
```
The existing `upserted`/`user_role_assignments` CTE then creates the app_user (role `ADMIN`, `branch_id=2`) and its scoped assignment (`school_id=2`) automatically — no other change needed. (Optionally also add a school-2 row for `local-admin@custoking.local`? No — keep `local-admin@…` = school 1; admin-2 is the new school-2 identity.)

- [ ] **Step 3: Seed known per-school objects (students, orders, ff requests)**

After the user/role block (before the `setval(...)` calls), append a cross-schema seed block. Read the actual NOT NULL columns of each table first (the tenant-key migrations made `school_id` NOT NULL): `student.students` (see the V1 student schema / the existing `StudentRlsIntegrationTest` seed), `catalog.catalog_orders` (V1 catalog schema), `firefighting.firefighting_requests` (V1 firefighting schema). Insert one known row per school per table with explicit ids, e.g.:
```sql
-- Students: known id per school for detail-by-id + list-marker probes.
INSERT INTO student.students (id, admission_no, full_name, school_id, class_id, section_id, academic_year_id)
VALUES (9000001, 'BOLA-A-001', 'BOLA Student A', 1, '1', '1A', 'local_2026'),
       (9000002, 'BOLA-B-001', 'BOLA Student B', 2, '1', '2A', 'local_2026')
ON CONFLICT (id) DO UPDATE SET school_id = EXCLUDED.school_id, section_id = EXCLUDED.section_id;
SELECT setval(pg_get_serial_sequence('student.students','id'), COALESCE((SELECT max(id) FROM student.students), 0) + 1, false);

-- Catalog orders: known id per school.
INSERT INTO catalog.catalog_orders (id, school_id, /* …NOT NULL cols… */ created_at)
VALUES ('ord-bola-a', 1, /* … */ now()), ('ord-bola-b', 2, /* … */ now())
ON CONFLICT (id) DO UPDATE SET school_id = EXCLUDED.school_id;

-- Firefighting requests: known code/id per school.
INSERT INTO firefighting.firefighting_requests (/* id/code col */, school_id, /* …NOT NULL cols… */ created_at)
VALUES ('ff-bola-a', 1, /* … */ now()), ('ff-bola-b', 2, /* … */ now())
ON CONFLICT (/* pk */) DO UPDATE SET school_id = EXCLUDED.school_id;
```
Fill the `/* … */` from each table's real NOT NULL columns (the migration files name them). If a table's required columns make a deterministic seed impractical, seed ONLY students + whichever of orders/ff are cheap, and record in the report which domains have a seeded marker (the gate uses own-scope-equivalence for the rest — see Task 3). Students are mandatory (the baseline depends on them).

- [ ] **Step 4: Run the seed against a live stack — verify it applies and admin-2 logs in**

```powershell
docker compose --profile full up -d
powershell -ExecutionPolicy Bypass -File scripts/ensure-local-dev-users.ps1
```
Expected: completes without psql error (idempotent). Then verify admin-2's token carries school 2:
```powershell
$b = @{ email='local-admin2@custoking.local'; password='password' } | ConvertTo-Json
$t = (Invoke-RestMethod -Uri http://localhost/api/v1/auth/login -Method Post -Headers @{'X-Forwarded-For'='127.0.0.11'} -ContentType application/json -Body $b).accessToken
(Invoke-RestMethod -Uri "http://localhost/api/v1/students/9000002?schoolId=2" -Headers @{Authorization="Bearer $t"}) # → returns student 9000002
(try { Invoke-RestMethod -Uri "http://localhost/api/v1/students/9000001?schoolId=1" -Headers @{Authorization="Bearer $t"} } catch { $_.Exception.Response.StatusCode }) # → 403/404 (admin-2 cannot see school-1 student)
```
Expected: admin-2 sees `9000002`, is denied `9000001`. (If admin-2 sees `9000001`, the scoped assignment is wrong — fix Step 2 before proceeding; this is the load-bearing fixture check.)

- [ ] **Step 5: Commit**

```bash
git add scripts/ensure-local-dev-users.ps1
git commit -m "test(fixture): seed a second tenant (school 2) + admin + known cross-tenant objects for BOLA"
```

---

### Task 2: BOLA gate core — login, baseline, detail-by-id probes

**Files:**
- Create: `scripts/audit-tenant-isolation.ps1`

**Interfaces:**
- Consumes: the Task 1 fixture ids.
- Produces (consumed by Task 3): the script's helper functions `Login`, `Invoke-Api`, `Body-ContainsId`, `Add-Failure`, and the `$failures` list + fixture-id constants; the baseline + detail-probe sections. Task 3 appends the list-probe section + coverage print to the SAME file.

- [ ] **Step 1: Create the gate skeleton — params, constants, helpers**

```powershell
param(
    [string]$GatewayBaseUrl = "http://localhost",
    [string]$AdminAEmail = "local-admin@custoking.local",
    [string]$AdminBEmail = "local-admin2@custoking.local",
    [string]$AdminPassword = "password",
    [string]$SuperEmail = "local-superadmin@custoking.local",
    [string]$SuperPassword = "password",
    [int]$TimeoutSeconds = 30
)
$ErrorActionPreference = "Stop"

# Fixture ids — MUST match scripts/ensure-local-dev-users.ps1
$SchoolA = 1; $SchoolB = 2
$StudentA = 9000001; $StudentB = 9000002
$OrderA = 'ord-bola-a'; $OrderB = 'ord-bola-b'
$FfA = 'ff-bola-a'; $FfB = 'ff-bola-b'

$loginIp = "127.0.0.$([int](Get-Date -Format 'ss') + 20)"
$failures = New-Object System.Collections.Generic.List[string]
$probeCount = 0

function Login {
    param([string]$Email, [string]$Password)
    $body = @{ email = $Email; password = $Password } | ConvertTo-Json
    try {
        $r = Invoke-RestMethod -Uri "$GatewayBaseUrl/api/v1/auth/login" -Method Post `
            -Headers @{ "X-Forwarded-For" = $loginIp } -ContentType "application/json" `
            -Body $body -TimeoutSec $TimeoutSeconds
    } catch {
        Write-Host "SETUP ERROR: login failed for $Email — is the stack up and seeded? $($_.Exception.Message)" -ForegroundColor Red
        exit 2
    }
    if (-not $r.accessToken) { Write-Host "SETUP ERROR: no accessToken for $Email" -ForegroundColor Red; exit 2 }
    return $r.accessToken
}

# Returns @{ Status = <int>; Body = <object|$null> }. Never throws on an HTTP error status.
function Invoke-Api {
    param([string]$Method, [string]$Path, [string]$Token)
    $headers = @{ Authorization = "Bearer $Token" }
    try {
        $resp = Invoke-WebRequest -Uri "$GatewayBaseUrl$Path" -Method $Method -Headers $headers `
            -TimeoutSec $TimeoutSeconds -UseBasicParsing
        $body = $null
        if ($resp.Content) { try { $body = $resp.Content | ConvertFrom-Json } catch { $body = $resp.Content } }
        return @{ Status = [int]$resp.StatusCode; Body = $body }
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        return @{ Status = $status; Body = $null }
    }
}

# True if the JSON body (any shape) contains the given id literal.
function Body-ContainsId {
    param([object]$Body, [object]$Id)
    if ($null -eq $Body) { return $false }
    return ($Body | ConvertTo-Json -Depth 12 -Compress) -match [regex]::Escape([string]$Id)
}

function Add-Failure { param([string]$Msg) $script:failures.Add($Msg); Write-Host "  LEAK: $Msg" -ForegroundColor Red }
function Pass { param([string]$Msg) Write-Host "  ok: $Msg" -ForegroundColor DarkGray }
```

- [ ] **Step 2: Log in and assert the positive baseline**

Append:
```powershell
Write-Host "BOLA tenant-isolation gate → $GatewayBaseUrl" -ForegroundColor Cyan
$tokenA = Login $AdminAEmail $AdminPassword
$tokenB = Login $AdminBEmail $AdminPassword
$tokenS = Login $SuperEmail $SuperPassword

Write-Host "Baseline (probes have teeth):"
$a = Invoke-Api GET "/api/v1/students/$StudentA?schoolId=$SchoolA" $tokenA
if ($a.Status -ne 200 -or -not (Body-ContainsId $a.Body $StudentA)) { Add-Failure "baseline: admin-A cannot see its own student $StudentA (status $($a.Status))" } else { Pass "admin-A sees student $StudentA" }
$b = Invoke-Api GET "/api/v1/students/$StudentB?schoolId=$SchoolB" $tokenB
if ($b.Status -ne 200 -or -not (Body-ContainsId $b.Body $StudentB)) { Add-Failure "baseline: admin-B cannot see its own student $StudentB (status $($b.Status))" } else { Pass "admin-B sees student $StudentB" }
if ($StudentA -eq $StudentB) { Add-Failure "baseline: StudentA and StudentB ids are not distinct" }
$sa = Invoke-Api GET "/api/v1/students/$StudentA?schoolId=$SchoolA" $tokenS
$sb = Invoke-Api GET "/api/v1/students/$StudentB?schoolId=$SchoolB" $tokenS
if ($sa.Status -ne 200 -or $sb.Status -ne 200) { Add-Failure "baseline: superadmin cannot see both students (A $($sa.Status) / B $($sb.Status))" } else { Pass "superadmin sees both" }
if ($failures.Count -gt 0) {
    Write-Host "Baseline failed — fixture or scoping is broken; aborting before probes." -ForegroundColor Red
    exit 1
}
```

- [ ] **Step 3: Detail-by-id cross-tenant probes**

Append a detail manifest + loop. Each probe = admin-A requesting a school-B object id; pass iff 403/404 OR a 200 that does not carry the B id.
```powershell
Write-Host "Detail-by-id probes (admin-A requests a school-B object):"
$detailProbes = @(
    @{ Key = 'student:detail'; Path = "/api/v1/students/$StudentB?schoolId=$SchoolB"; Marker = $StudentB },
    @{ Key = 'catalog:order-detail'; Path = "/api/v1/supply/orders/$OrderB?schoolId=$SchoolB"; Marker = $OrderB },
    @{ Key = 'firefighting:request-detail'; Path = "/api/v1/ff/requests/$FfB?schoolId=$SchoolB"; Marker = $FfB }
)
foreach ($p in $detailProbes) {
    $script:probeCount++
    $r = Invoke-Api GET $p.Path $tokenA
    if ($r.Status -eq 403 -or $r.Status -eq 404) { Pass "$($p.Key) denied ($($r.Status))" }
    elseif ($r.Status -eq 200 -and (Body-ContainsId $r.Body $p.Marker)) { Add-Failure "$($p.Key): admin-A read school-B object $($p.Marker) (HTTP 200)" }
    else { Pass "$($p.Key) no B data (status $($r.Status))" }
}
```
(If Task 1 could not seed `orders`/`ff` deterministically, drop those two probe rows and note it — students is mandatory.)

- [ ] **Step 4: Temporary exit + run against the live stack**

Temporarily append `if ($failures.Count) { exit 1 } else { Write-Host 'detail probes isolated'; exit 0 }` (Task 3 replaces this with the final report). Run (stack up + seeded):
```powershell
powershell -ExecutionPolicy Bypass -File scripts/audit-tenant-isolation.ps1
```
Expected: baseline ok, all detail probes "denied"/"no B data", exit 0. Then a **teeth check** — temporarily point `student:detail` at `$StudentA` with `$tokenA` and confirm it would be a 200-with-marker (proving the assertion can fail); revert.

- [ ] **Step 5: Commit**

```bash
git add scripts/audit-tenant-isolation.ps1
git commit -m "test(bola): tenant-isolation gate — login, positive baseline, detail-by-id probes"
```

---

### Task 3: List-param probes, oracle, and coverage summary

**Files:**
- Modify: `scripts/audit-tenant-isolation.ps1` (add the list-probe section + final report; replace the Task 2 temporary exit)

**Interfaces:**
- Consumes: Task 2's helpers (`Invoke-Api`, `Body-ContainsId`, `Add-Failure`, `Pass`, `$failures`, `$probeCount`, tokens, fixture ids).

- [ ] **Step 1: Add the list-probe manifest + oracle**

Append before the final report. Two oracle kinds: `marker` (a seeded B row — assert it's absent) and `ownscope` (assert cross-tenant `?schoolId=B` == own `?schoolId=A`, or a 403/empty).
```powershell
Write-Host "List probes (admin-A passes ?schoolId=$SchoolB):"
# marker-backed: a seeded school-B row must NOT appear in admin-A's cross-tenant list
$listMarker = @(
    @{ Key = 'student:list'; A = "/api/v1/students?schoolId=$SchoolA&page=0&size=50"; X = "/api/v1/students?schoolId=$SchoolB&page=0&size=50"; Marker = $StudentB },
    @{ Key = 'catalog:orders'; A = "/api/v1/supply/orders?schoolId=$SchoolA"; X = "/api/v1/supply/orders?schoolId=$SchoolB"; Marker = $OrderB },
    @{ Key = 'firefighting:requests'; A = "/api/v1/ff/requests?schoolId=$SchoolA"; X = "/api/v1/ff/requests?schoolId=$SchoolB"; Marker = $FfB }
)
foreach ($p in $listMarker) {
    $script:probeCount++
    $r = Invoke-Api GET $p.X $tokenA
    if ($r.Status -eq 403) { Pass "$($p.Key) denied (403)" }
    elseif (Body-ContainsId $r.Body $p.Marker) { Add-Failure "$($p.Key): admin-A's ?schoolId=$SchoolB list contained school-B marker $($p.Marker)" }
    else { Pass "$($p.Key) no B marker" }
}
# own-scope-equivalence: cross-tenant param must return the same as own-scope (server ignores client schoolId)
$listOwnScope = @(
    @{ Key = 'attendance:daily-summary'; A = "/api/v1/attendance/daily-summary?schoolId=$SchoolA&date=2026-02-02"; X = "/api/v1/attendance/daily-summary?schoolId=$SchoolB&date=2026-02-02" },
    @{ Key = 'fee:classes'; A = "/api/v1/classes?schoolId=$SchoolA"; X = "/api/v1/classes?schoolId=$SchoolB" },
    @{ Key = 'fee:report'; A = "/api/v1/fees/report?schoolId=$SchoolA&classId=1&sectionId=1A"; X = "/api/v1/fees/report?schoolId=$SchoolB&classId=1&sectionId=1A" },
    @{ Key = 'catalog:annual-plan'; A = "/api/v1/supply/annual-plan?schoolId=$SchoolA"; X = "/api/v1/supply/annual-plan?schoolId=$SchoolB" },
    @{ Key = 'workflow:pending'; A = "/api/v1/workflows/pending?schoolId=$SchoolA"; X = "/api/v1/workflows/pending?schoolId=$SchoolB" },
    @{ Key = 'firefighting:stats'; A = "/api/v1/ff/requests/stats?schoolId=$SchoolA"; X = "/api/v1/ff/requests/stats?schoolId=$SchoolB" },
    @{ Key = 'workspace:dashboard'; A = "/api/v1/dashboard?schoolId=$SchoolA"; X = "/api/v1/dashboard?schoolId=$SchoolB" }
)
foreach ($p in $listOwnScope) {
    $script:probeCount++
    $rx = Invoke-Api GET $p.X $tokenA
    if ($rx.Status -eq 403) { Pass "$($p.Key) denied (403)"; continue }
    $ra = Invoke-Api GET $p.A $tokenA
    $jx = if ($rx.Body) { $rx.Body | ConvertTo-Json -Depth 12 -Compress } else { '' }
    $ja = if ($ra.Body) { $ra.Body | ConvertTo-Json -Depth 12 -Compress } else { '' }
    # B's marker must not appear, and the cross-tenant result must equal own-scope.
    if ((Body-ContainsId $rx.Body $StudentB) -or (Body-ContainsId $rx.Body $OrderB) -or (Body-ContainsId $rx.Body $FfB)) {
        Add-Failure "$($p.Key): admin-A's ?schoolId=$SchoolB response carried a school-B marker"
    } elseif ($jx -ne $ja) {
        Add-Failure "$($p.Key): ?schoolId=$SchoolB differs from own ?schoolId=$SchoolA (client param widened scope)"
    } else { Pass "$($p.Key) == own-scope" }
}
```

- [ ] **Step 2: Replace the Task 2 temporary exit with the final report + coverage summary**

```powershell
$excluded = @('supply/catalog-categories (catalog-wide)','fee-structure (catalog-wide)','students/import/template (static)','rbac/* zones/* schools/* (superadmin-only)')
Write-Host ""
Write-Host "Coverage: $probeCount probes across detail + list vectors." -ForegroundColor Cyan
Write-Host "Excluded (non-tenant-scoped, by design): $($excluded -join '; ')" -ForegroundColor DarkGray
if ($failures.Count -gt 0) {
    Write-Host "BOLA gate FAILED — $($failures.Count) cross-tenant leak(s):" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "BOLA gate PASSED — no cross-tenant leaks across $probeCount probes." -ForegroundColor Green
exit 0
```

- [ ] **Step 3: Run the full gate — verify green and exit 0**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/audit-tenant-isolation.ps1; echo "exit=$LASTEXITCODE"
```
Expected: baseline ok, all detail + list probes isolated, `BOLA gate PASSED`, `exit=0`. If a real isolation bug exists, the gate prints the leaking endpoint and exits 1 — capture that output in the report if it occurs (it would be a genuine Phase-1 finding to escalate, not a gate bug).

- [ ] **Step 4: Negative-control check (the gate can fail)**

Temporarily flip one `ownscope` probe's `X` to deliberately request another tenant's data in a way you know leaks (or temporarily weaken the oracle), confirm the gate exits 1 and names the endpoint, then REVERT. This proves the gate is not vacuously green. Document the check in the report.

- [ ] **Step 5: Commit**

```bash
git add scripts/audit-tenant-isolation.ps1
git commit -m "test(bola): list-param probes (marker + own-scope oracle) + coverage report"
```

---

### Task 4: CI wiring + docs + tracker

**Files:**
- Modify: `scripts/verify-microservice-migration.ps1` (add a `-RunBolaAudit` switch)
- Modify: `.github/workflows/ci.yml` (`microservice-runtime-test` job: seed + run the gate as a required step)
- Modify: `ARCHITECTURE_REVIEW.md` (note the Phase-1 BOLA gate)
- Modify: `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` (Task 1.5 status)

- [ ] **Step 1: Add `-RunBolaAudit` to `verify-microservice-migration.ps1`**

Read the script's existing switch handling (it already has `-RunDbAudit` / `-RunSmoke`). Add a `[switch]$RunBolaAudit` param and, mirroring how `-RunSmoke` invokes its script, a block that runs the seed then the gate and fails the verifier on non-zero:
```powershell
if ($RunBolaAudit) {
    Write-Host "== BOLA tenant-isolation gate ==" -ForegroundColor Cyan
    & powershell -ExecutionPolicy Bypass -File "$PSScriptRoot/ensure-local-dev-users.ps1"
    & powershell -ExecutionPolicy Bypass -File "$PSScriptRoot/audit-tenant-isolation.ps1"
    if ($LASTEXITCODE -ne 0) { throw "BOLA tenant-isolation gate failed (exit $LASTEXITCODE)" }
}
```
(Match the script's actual style/variable names for the failure path — read it first.)

- [ ] **Step 2: Wire into CI (`.github/workflows/ci.yml`)**

In the `microservice-runtime-test` job (which already boots `docker compose --profile full`), the stack must be brought up (or the gateway recreated) with the `docker-compose.bola.yml` overlay so it runs in `enforce` mode, then provisioned in order. Add a required step:
```yaml
      - name: BOLA tenant-isolation gate
        shell: pwsh
        run: |
          docker compose -f docker-compose.yml -f docker-compose.bola.yml up -d --force-recreate api-gateway
          pwsh -File scripts/ensure-app-rt-local.ps1
          pwsh -File scripts/ensure-local-dev-users.ps1
          pwsh -File scripts/audit-tenant-isolation.ps1
```
(If the job already provisions app_rt / seeds elsewhere, don't duplicate — just ensure the gateway is in enforce mode via the overlay and the gate runs after seeding. `ensure-app-rt-local.ps1` / `ensure-local-dev-users.ps1` are idempotent.)
(Read the job's existing steps to match the runner shell — the repo uses PowerShell scripts; if the CI runner is Linux, `pwsh` is required and `ensure-local-dev-users.ps1`'s `docker exec custoking-postgres psql` path still works. Confirm the container name matches the compose service. If the job currently runs the gate via `verify-microservice-migration.ps1`, instead add `-RunBolaAudit` to that existing invocation rather than a new step — prefer reusing the verifier entrypoint.)

- [ ] **Step 3: Run the verifier end-to-end with the new switch**

```powershell
docker compose --profile full up -d --build
powershell -ExecutionPolicy Bypass -File scripts/verify-microservice-migration.ps1 -RunBolaAudit
```
Expected: the verifier seeds, runs the gate, prints `BOLA gate PASSED`, and the verifier exits success.

- [ ] **Step 4: Update `ARCHITECTURE_REVIEW.md` + the program tracker**

- `ARCHITECTURE_REVIEW.md`: near the MT-P0-1/BOLA entry, append a dated (2026-07-01) note: an automated BOLA gate (`scripts/audit-tenant-isolation.ps1`) now runs in CI, proving tenant-A cannot read tenant-B data across the covered list/detail endpoints with a two-school seeded fixture + positive baseline. Append only.
- `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md`: under Task 1.5, add a `Status (2026-07-01): done` line referencing the gate + this plan, and note that the **Phase 1 gate** (BOLA suite green AND RLS blocks cross-tenant) is now met. Do not rewrite existing content.

- [ ] **Step 5: Commit**

```bash
git add scripts/verify-microservice-migration.ps1 .github/workflows/ci.yml ARCHITECTURE_REVIEW.md docs/superpowers/plans/2026-06-28-architecture-remediation-program.md
git commit -m "ci(bola): wire tenant-isolation gate into the verifier + CI; mark Task 1.5 done"
```

---

## Self-Review

**Spec coverage:**
- PowerShell gate against the running stack via the gateway → Tasks 2–3 (`audit-tenant-isolation.ps1`). ✓
- Both BOLA vectors (cross-tenant list param + detail-by-id) → Task 2 (detail), Task 3 (list). ✓
- Positive baseline (anti-false-green) → Task 2 Step 2. ✓
- Two-school seeded fixture with known ids + scoped admin-2 → Task 1. ✓
- Oracle (detail = 403/404/empty; list = marker-absent OR own-scope-equivalent) → Global Constraints + Task 2/3. ✓
- Coverage summary (probed vs excluded, not silently capped) → Task 3 Step 2. ✓
- Infra-error vs leak exit codes (2 vs 1 vs 0) → Global Constraints + Task 2 `Login` + Task 3 report. ✓
- CI-required wiring (`verify-microservice-migration.ps1` + `ci.yml`) → Task 4. ✓
- Excluded non-tenant-scoped endpoints listed → Global Constraints + Task 3 `$excluded`. ✓
- Fixture-fidelity check (admin-2 JWT carries school 2) → Task 1 Step 4 (the load-bearing verification). ✓

**Placeholder scan:** the gate script is given in full (params, helpers, baseline, both probe loops, report). The one deliberately implementer-resolved spot is the exact NOT NULL column list for the `catalog_orders`/`firefighting_requests` seed rows (Task 1 Step 3 `/* … */`) — these must be read from each table's real schema to avoid drift, and students (the mandatory anchor) are fully specified. If orders/ff can't be seeded deterministically, the plan says to drop those probe rows and rely on students + own-scope-equivalence (Task 2 Step 3 / Task 3 note).

**Type/name consistency:** fixture-id constants (`StudentA/B`, `OrderA/B`, `FfA/B`, `SchoolA/B`) are identical across the seed (Task 1) and the gate (Tasks 2–3); helper names (`Invoke-Api`, `Body-ContainsId`, `Add-Failure`, `Pass`, `$failures`, `$probeCount`) defined in Task 2 and reused in Task 3; exit codes (0 pass / 1 leak / 2 infra) consistent. Endpoint paths match those already proven reachable in `smoke-microservice-features.ps1`.

## Risks

- **Fixture fidelity (admin-2 JWT must carry school 2).** Mitigated by reusing the existing `dev_users` CTE (identical scoped-assignment wiring) + the Task 1 Step 4 verification that admin-2 is denied school-1's student. The single most important check in the plan.
- **List own-scope-equivalence false-green if both sides are empty.** Mitigated by the marker-backed probes (students/orders/ff) carrying real teeth, the positive baseline, and the negative-control check (Task 3 Step 4).
- **Seeding known detail ids across services is uneven.** Students mandatory + fully specified; orders/ff best-effort with a documented fallback to own-scope-equivalence.
- **CI runner shell.** Task 4 Step 2 says to read the existing `microservice-runtime-test` job and match its shell (`pwsh`) + reuse the verifier entrypoint if the gate is already invoked there.
- **An actual leak is found.** That is a real Phase-1 finding, not a gate defect — the gate correctly exits 1 and names the endpoint; escalate it (it would mean a TenantContext gap to fix before Task 1.5 can close).

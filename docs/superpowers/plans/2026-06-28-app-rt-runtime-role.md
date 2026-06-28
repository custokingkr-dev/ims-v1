# `app_rt` Unprivileged Runtime Role — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce an unprivileged Postgres runtime role `app_rt` (subject to RLS) and cut the application runtime over to it, while `appuser` (prod) / `postgres` (local) remains the owner + Flyway role.

**Architecture:** A single idempotent SQL script creates `app_rt` with DML-only grants + default privileges; a SQL audit asserts it is unprivileged and subject to RLS. Local docker-compose mirrors prod (runtime → `app_rt`, Flyway → owner); prod is provisioned via the established one-off Cloud Run Job pattern before a cloudbuild cutover flips `_APP_DB_USER`.

**Tech Stack:** PostgreSQL 15/16, `psql` (`\gexec`, `\if`), docker-compose, Cloud Build / Cloud Run Jobs, PowerShell, Spring Boot services (config only).

**Spec:** `docs/superpowers/specs/2026-06-28-app-rt-runtime-role-design.md`

## Global Constraints

- Do not break the public `/api/v1/**` contract.
- Forward-only Flyway migrations; never edit an applied migration. (This task adds **no** migrations — role/grants are out-of-band DBA SQL, not service Flyway history.)
- Prod DB is shared Cloud SQL `custoking-db` / `custoking_ims_v1`, Postgres 15, private IP. Apply DDL via the one-off Cloud Run Job pattern (model: `scripts/audit-legacy-compatibility-cloudsql.ps1`).
- No secrets in code or logs. Secret Manager + WIF only. `db-password` = owner/Flyway role; new `app-rt-password` = `app_rt`.
- The 12 service schemas (single source of truth for grants): `identity, tenant_school, student, attendance, fee, catalog, workflow, firefighting, reporting, notification, audit, billing`.
- `app_rt` MUST be: `LOGIN, NOINHERIT, NOSUPERUSER, NOBYPASSRLS, NOCREATEROLE, NOCREATEDB`, own nothing, and **not** a member of `cloudsqlsuperuser`.
- Local test DB is the compose Postgres (container `custoking-postgres`, db `postgres`, superuser `postgres`/`root`).
- Scope: role + cutover only. No RLS policies, no `TenantContext` (Tasks 1.3 / 1.2).

---

### Task 1: `app_rt` role creation SQL + audit role-attribute checks

**Files:**
- Create: `scripts/create-app-rt-role.sql`
- Create: `scripts/audit-app-rt-privileges.sql`

**Interfaces:**
- Produces: `scripts/create-app-rt-role.sql` — psql vars: `app_rt_password` (required), `owner` (required), `set_superuser` (optional flag). Creates/repairs role `app_rt`.
- Produces: `scripts/audit-app-rt-privileges.sql` — psql var: `run_live_probes` (optional flag). Exits non-zero on any violation.

- [ ] **Step 1: Write the failing audit (role-attribute section only)**

Create `scripts/audit-app-rt-privileges.sql`:

```sql
-- Regression audit for the app_rt runtime role. Exits non-zero on any violation.
-- psql var: run_live_probes  -- if defined, also run the live DDL/RLS probes (needs a superuser connection)
\set ON_ERROR_STOP on

-- 1) Role exists with the correct unprivileged attributes.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
    RAISE EXCEPTION 'app_rt role does not exist';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt'
             AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolinherit OR NOT rolcanlogin)) THEN
    RAISE EXCEPTION 'app_rt attributes wrong (need LOGIN, NOINHERIT, NOSUPERUSER, NOBYPASSRLS, NOCREATEROLE, NOCREATEDB)';
  END IF;
END$$;

-- 2) app_rt must NOT be a member of cloudsqlsuperuser.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_auth_members m
    JOIN pg_roles grp ON grp.oid=m.roleid
    JOIN pg_roles mem ON mem.oid=m.member
    WHERE mem.rolname='app_rt' AND grp.rolname='cloudsqlsuperuser'
  ) THEN
    RAISE EXCEPTION 'app_rt must not be a member of cloudsqlsuperuser';
  END IF;
END$$;

\echo 'app_rt audit: role attribute + membership checks passed'
```

- [ ] **Step 2: Run it against a clean Postgres to verify it fails (role missing)**

Run:
```powershell
docker compose up -d postgres
docker cp scripts\audit-app-rt-privileges.sql custoking-postgres:/tmp/audit.sql
docker exec custoking-postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -f /tmp/audit.sql
```
Expected: FAIL — `ERROR: app_rt role does not exist`, non-zero exit.

- [ ] **Step 3: Write `scripts/create-app-rt-role.sql`**

```sql
-- Idempotent creation of the unprivileged runtime role app_rt.
-- psql vars:
--   app_rt_password : login password for app_rt        (required)
--   owner           : schema-owning / Flyway role       (required: appuser in prod, postgres in local)
--   set_superuser   : if defined, SET ROLE cloudsqlsuperuser for role creation (prod only)
-- Assumes target schemas already exist; grants apply only to schemas present in pg_namespace.
\set ON_ERROR_STOP on

-- Role creation/repair needs CREATEROLE; in prod the connecting appuser elevates via cloudsqlsuperuser.
\if :{?set_superuser}
SET ROLE cloudsqlsuperuser;
\endif

SELECT 'CREATE ROLE app_rt LOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt')
\gexec

ALTER ROLE app_rt LOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS PASSWORD :'app_rt_password';

-- Let the owner/admin SET ROLE app_rt (needed for the audit's live probes). Harmless: app_rt is unprivileged.
GRANT app_rt TO :"owner";

\if :{?set_superuser}
RESET ROLE;
\endif

-- Final assertions (run as owner; pg catalogs are world-readable).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt'
             AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolinherit)) THEN
    RAISE EXCEPTION 'app_rt has forbidden attributes after creation';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_auth_members m
    JOIN pg_roles grp ON grp.oid=m.roleid
    JOIN pg_roles mem ON mem.oid=m.member
    WHERE mem.rolname='app_rt' AND grp.rolname='cloudsqlsuperuser'
  ) THEN
    RAISE EXCEPTION 'app_rt must not be a member of cloudsqlsuperuser';
  END IF;
END$$;

\echo 'create-app-rt-role: role ready'
```

- [ ] **Step 4: Run the create script, then re-run the audit — verify it passes**

Run:
```powershell
docker cp scripts\create-app-rt-role.sql custoking-postgres:/tmp/create.sql
docker exec custoking-postgres psql -v ON_ERROR_STOP=1 -v app_rt_password=app_rt -v owner=postgres -U postgres -d postgres -f /tmp/create.sql
docker exec custoking-postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -f /tmp/audit.sql
```
Expected: create prints `create-app-rt-role: role ready`; audit prints `... checks passed`; both exit 0.

- [ ] **Step 5: Commit**

```powershell
git add scripts/create-app-rt-role.sql scripts/audit-app-rt-privileges.sql
git commit -m "feat(db): add app_rt role creation SQL + role-attribute audit"
```

---

### Task 2: Schema grants + default privileges, and audit privilege checks

**Files:**
- Modify: `scripts/create-app-rt-role.sql` (append grant/default-privilege blocks)
- Modify: `scripts/audit-app-rt-privileges.sql` (append schema-USAGE + table-SELECT checks)

**Interfaces:**
- Consumes: Task 1's `create-app-rt-role.sql` (`owner`, `app_rt_password` vars) and audit.
- Produces: `app_rt` granted USAGE + DML + sequence usage on the 12 schemas, plus default privileges for future owner-created objects.

- [ ] **Step 1: Extend the audit with schema-USAGE + table-SELECT checks (failing test)**

In `scripts/audit-app-rt-privileges.sql`, insert before the final `\echo`:

```sql
-- 3) For each target schema that exists, app_rt has USAGE.
DO $$
DECLARE s text;
BEGIN
  FOR s IN
    SELECT n.nspname FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),
      ('fee'),('catalog'),('workflow'),('firefighting'),('reporting'),
      ('notification'),('audit'),('billing')) v(name)
    JOIN pg_namespace n ON n.nspname=v.name
  LOOP
    IF NOT has_schema_privilege('app_rt', s, 'USAGE') THEN
      RAISE EXCEPTION 'app_rt lacks USAGE on schema %', s;
    END IF;
  END LOOP;
END$$;

-- 4) Where target schemas contain tables, app_rt has SELECT (proxy for DML grants).
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT n.nspname, c.relname
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE c.relkind='r'
      AND n.nspname IN ('identity','tenant_school','student','attendance','fee','catalog',
                        'workflow','firefighting','reporting','notification','audit','billing')
  LOOP
    IF NOT has_table_privilege('app_rt', format('%I.%I', r.nspname, r.relname), 'SELECT') THEN
      RAISE EXCEPTION 'app_rt lacks SELECT on %.%', r.nspname, r.relname;
    END IF;
  END LOOP;
END$$;
```

- [ ] **Step 2: Create a test fixture (schemas + a table) and run the audit — verify it fails**

Run:
```powershell
docker exec custoking-postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -c "CREATE SCHEMA IF NOT EXISTS identity; CREATE TABLE IF NOT EXISTS identity.app_users(id bigint primary key);"
docker cp scripts\audit-app-rt-privileges.sql custoking-postgres:/tmp/audit.sql
docker exec custoking-postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -f /tmp/audit.sql
```
Expected: FAIL — `ERROR: app_rt lacks USAGE on schema identity` (grants not added yet).

- [ ] **Step 3: Append grants + default privileges to `create-app-rt-role.sql`**

Insert immediately before the final assertion `DO $$` block:

```sql
-- Grants on existing target schemas (intersection with pg_namespace keeps this re-runnable
-- before all services have migrated, and harmless if a schema is absent).
SELECT format('GRANT USAGE ON SCHEMA %I TO app_rt', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec

SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO app_rt', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec

SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO app_rt', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec

-- Default privileges so FUTURE owner-created objects auto-grant DML to app_rt.
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt', :'owner', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec

SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO app_rt', :'owner', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec
```

- [ ] **Step 4: Re-run create + audit — verify it passes**

Run:
```powershell
docker cp scripts\create-app-rt-role.sql custoking-postgres:/tmp/create.sql
docker exec custoking-postgres psql -v ON_ERROR_STOP=1 -v app_rt_password=app_rt -v owner=postgres -U postgres -d postgres -f /tmp/create.sql
docker exec custoking-postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -f /tmp/audit.sql
```
Expected: both exit 0; audit prints the passed message. (A future table also auto-grants: `CREATE TABLE identity.t2(id int);` then `SELECT has_table_privilege('app_rt','identity.t2','SELECT');` → `t`.)

- [ ] **Step 5: Commit**

```powershell
git add scripts/create-app-rt-role.sql scripts/audit-app-rt-privileges.sql
git commit -m "feat(db): grant app_rt DML + default privileges; audit schema/table grants"
```

---

### Task 3: Live DDL-denied + subject-to-RLS probes

**Files:**
- Modify: `scripts/audit-app-rt-privileges.sql` (append guarded live probes)

**Interfaces:**
- Consumes: `app_rt` role from Tasks 1–2; runs only when `run_live_probes` is set (superuser connection).
- Produces: positive proof that `app_rt` cannot DDL and is subject to RLS while the owner bypasses.

- [ ] **Step 1: Append the guarded probes to the audit (failing until run with the flag)**

Insert before the final `\echo` in `scripts/audit-app-rt-privileges.sql`:

```sql
-- Live probes (require a superuser connection; set -v run_live_probes=1).
\if :{?run_live_probes}

-- 5) DDL is denied for app_rt (no CREATE privilege anywhere; PG15+ public is locked down).
DO $$
BEGIN
  SET LOCAL ROLE app_rt;
  BEGIN
    EXECUTE 'CREATE TABLE public._app_rt_ddl_probe (x int)';
    RESET ROLE;
    EXECUTE 'DROP TABLE IF EXISTS public._app_rt_ddl_probe';
    RAISE EXCEPTION 'app_rt was able to CREATE TABLE (DDL not denied)';
  EXCEPTION WHEN insufficient_privilege THEN
    RESET ROLE;
    RAISE NOTICE 'OK: DDL denied for app_rt';
  END;
END$$;

-- 6) app_rt is SUBJECT TO RLS; the table owner bypasses.
DO $$
DECLARE app_rt_count int; owner_count int;
BEGIN
  DROP TABLE IF EXISTS public._app_rt_rls_probe;
  CREATE TABLE public._app_rt_rls_probe (tenant text NOT NULL, val int NOT NULL);
  INSERT INTO public._app_rt_rls_probe VALUES ('A',1),('A',2),('B',3);
  GRANT SELECT ON public._app_rt_rls_probe TO app_rt;
  ALTER TABLE public._app_rt_rls_probe ENABLE ROW LEVEL SECURITY;
  CREATE POLICY _app_rt_rls_probe_pol ON public._app_rt_rls_probe USING (tenant = 'A');

  SET LOCAL ROLE app_rt;
  SELECT count(*) INTO app_rt_count FROM public._app_rt_rls_probe;
  RESET ROLE;
  SELECT count(*) INTO owner_count FROM public._app_rt_rls_probe;

  DROP TABLE public._app_rt_rls_probe;

  IF app_rt_count <> 2 THEN
    RAISE EXCEPTION 'RLS not enforced for app_rt: saw % rows, expected 2', app_rt_count;
  END IF;
  IF owner_count <> 3 THEN
    RAISE EXCEPTION 'owner unexpectedly constrained by RLS: saw % rows, expected 3', owner_count;
  END IF;
  RAISE NOTICE 'OK: app_rt subject to RLS (2 rows), owner bypasses (3 rows)';
END$$;

\endif
```

- [ ] **Step 2: Run the audit WITHOUT the flag — verify probes are skipped (still passes)**

Run:
```powershell
docker cp scripts\audit-app-rt-privileges.sql custoking-postgres:/tmp/audit.sql
docker exec custoking-postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -f /tmp/audit.sql
```
Expected: exit 0, no probe NOTICEs (skipped).

- [ ] **Step 3: Run the audit WITH the flag — verify probes pass**

Run:
```powershell
docker exec custoking-postgres psql -v ON_ERROR_STOP=1 -v run_live_probes=1 -U postgres -d postgres -f /tmp/audit.sql
```
Expected: exit 0; `NOTICE: OK: DDL denied for app_rt`; `NOTICE: OK: app_rt subject to RLS (2 rows), owner bypasses (3 rows)`.

- [ ] **Step 4: Sanity-check the negative case (optional, manual)**

Temporarily confirm the RLS probe would fail if `app_rt` could bypass: `ALTER ROLE app_rt BYPASSRLS;` then re-run with the flag → Expected FAIL `RLS not enforced for app_rt: saw 3 rows`. Then revert: `ALTER ROLE app_rt NOBYPASSRLS;`. (Do not commit this change.)

- [ ] **Step 5: Commit**

```powershell
git add scripts/audit-app-rt-privileges.sql
git commit -m "feat(db): add guarded DDL-denied and subject-to-RLS probes to app_rt audit"
```

---

### Task 4: Local docker-compose parity (runtime → app_rt) + audit wrapper

**Files:**
- Create: `deploy/local/initdb/00-app-rt.sql`
- Create: `scripts/ensure-app-rt-local.ps1`
- Create: `scripts/audit-app-rt-privileges.ps1`
- Modify: `docker-compose.yml` (postgres `volumes`; 12 service `SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD`)

**Interfaces:**
- Consumes: `scripts/create-app-rt-role.sql`, `scripts/audit-app-rt-privileges.sql`.
- Produces: `scripts/audit-app-rt-privileges.ps1` (runs the audit SQL with live probes against the compose DB); local stack where services connect as `app_rt`.

- [ ] **Step 1: Create the fresh-volume init script**

Create `deploy/local/initdb/00-app-rt.sql` (runs once on a fresh volume as the `postgres` superuser, before schemas exist, so services can connect as `app_rt` at startup):

```sql
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
    CREATE ROLE app_rt LOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS PASSWORD 'app_rt';
  END IF;
END$$;

GRANT app_rt TO postgres;

-- Global default privileges: future tables/sequences created by postgres (local Flyway owner)
-- auto-grant DML to app_rt. Per-schema USAGE is granted post-migration by ensure-app-rt-local.ps1.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres GRANT USAGE, SELECT ON SEQUENCES TO app_rt;
```

- [ ] **Step 2: Mount the init dir on the postgres service**

In `docker-compose.yml`, the `postgres` service `volumes:` becomes:

```yaml
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./deploy/local/initdb:/docker-entrypoint-initdb.d:ro
```

- [ ] **Step 3: Flip the 12 services' runtime datasource user to app_rt**

In `docker-compose.yml`, for every application service block (NOT the `postgres` service, and leaving every `FLYWAY_USERNAME`/`FLYWAY_PASSWORD` untouched), change the runtime datasource lines:

```yaml
      SPRING_DATASOURCE_USERNAME: app_rt
      SPRING_DATASOURCE_PASSWORD: app_rt
```

There are 12 occurrences of `SPRING_DATASOURCE_USERNAME: postgres` / `SPRING_DATASOURCE_PASSWORD: root`. `FLYWAY_USERNAME: postgres` / `FLYWAY_PASSWORD: root` stay as-is (owner runs migrations). Verify afterward:

```powershell
(Select-String -Path docker-compose.yml -Pattern 'SPRING_DATASOURCE_USERNAME: app_rt').Count   # expect 12
(Select-String -Path docker-compose.yml -Pattern 'FLYWAY_USERNAME: postgres').Count             # expect 12
(Select-String -Path docker-compose.yml -Pattern 'SPRING_DATASOURCE_USERNAME: postgres').Count  # expect 0
```

- [ ] **Step 4: Create `scripts/ensure-app-rt-local.ps1`**

```powershell
param(
    [string]$PostgresContainer = "custoking-postgres",
    [string]$Database = "postgres",
    [string]$DbUser = "postgres",
    [string]$AppRtPassword = "app_rt",
    [int]$TimeoutSeconds = 180
)
$ErrorActionPreference = "Stop"

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    $ready = docker exec $PostgresContainer psql -U $DbUser -d $Database -t -A -c "SELECT to_regclass('identity.app_users') IS NOT NULL AND to_regclass('tenant_school.schools') IS NOT NULL;" 2>$null
    if (($ready | Select-Object -Last 1) -eq "t") { break }
    Start-Sleep -Seconds 3
}

$sqlPath = Join-Path $PSScriptRoot "create-app-rt-role.sql"
docker cp $sqlPath "${PostgresContainer}:/tmp/create-app-rt-role.sql" | Out-Null
docker exec $PostgresContainer psql -v ON_ERROR_STOP=1 -v app_rt_password="$AppRtPassword" -v owner=postgres -U $DbUser -d $Database -f /tmp/create-app-rt-role.sql
if ($LASTEXITCODE -ne 0) { throw "Failed to ensure app_rt role/grants locally (exit $LASTEXITCODE)." }
Write-Host "Local app_rt role and grants are ready." -ForegroundColor Green
```

- [ ] **Step 5: Create `scripts/audit-app-rt-privileges.ps1`**

```powershell
param(
    [string]$PostgresContainer = "custoking-postgres",
    [string]$Database = "postgres",
    [string]$DbUser = "postgres"
)
$ErrorActionPreference = "Stop"

$sqlPath = Join-Path $PSScriptRoot "audit-app-rt-privileges.sql"
docker cp $sqlPath "${PostgresContainer}:/tmp/audit-app-rt-privileges.sql" | Out-Null
docker exec $PostgresContainer psql -v ON_ERROR_STOP=1 -v run_live_probes=1 -U $DbUser -d $Database -f /tmp/audit-app-rt-privileges.sql
if ($LASTEXITCODE -ne 0) { throw "app_rt privilege audit failed (exit $LASTEXITCODE)." }
Write-Host "app_rt privilege audit passed." -ForegroundColor Green
```

- [ ] **Step 6: Bring up a clean full stack and verify end to end**

Run:
```powershell
docker compose down -v
docker compose --profile full up -d --build
powershell -ExecutionPolicy Bypass -File scripts\ensure-app-rt-local.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-app-rt-privileges.ps1
```
Expected: services start healthy (runtime connects as `app_rt`); `ensure-app-rt-local` prints ready; audit prints "passed" with both probe NOTICEs. Spot-check a service serves data:
```powershell
powershell -ExecutionPolicy Bypass -File scripts\ensure-local-dev-users.ps1
# then exercise a read path through the gateway, e.g. login + a list endpoint, to confirm app_rt can query.
```

- [ ] **Step 7: Commit**

```powershell
git add deploy/local/initdb/00-app-rt.sql scripts/ensure-app-rt-local.ps1 scripts/audit-app-rt-privileges.ps1 docker-compose.yml
git commit -m "feat(local): run runtime as app_rt in compose; add local provisioning + audit wrappers"
```

---

### Task 5: cloudbuild cutover + secret catalog

**Files:**
- Modify: `cloudbuild.yaml` (`_APP_DB_USER`; per-service runtime password secret)
- Modify: `scripts/secret-manager-catalog.ps1` (add `app-rt-password`)

**Interfaces:**
- Consumes: prod role/secret provisioned in Task 6 (runbook enforces ordering — this config must NOT deploy before then).
- Produces: runtime deploys as `app_rt` with `app-rt-password`; Flyway stays `appuser` with `db-password`.

- [ ] **Step 1: Flip the runtime DB user substitution**

In `cloudbuild.yaml`, change:
```yaml
  _APP_DB_USER: appuser
```
to:
```yaml
  _APP_DB_USER: app_rt
```
Leave `_FLYWAY_DB_USER: appuser` unchanged. (`common_env` already wires `SPRING_DATASOURCE_USERNAME=${_APP_DB_USER}` and `FLYWAY_USERNAME=${_FLYWAY_DB_USER}`.)

- [ ] **Step 2: Split the runtime vs Flyway password secrets**

In `cloudbuild.yaml`, every `deploy_service` call's secrets string contains `SPRING_DATASOURCE_PASSWORD=db-password:latest,FLYWAY_PASSWORD=db-password:latest`. Change the runtime one only — replace all 12 occurrences of:
```
SPRING_DATASOURCE_PASSWORD=db-password:latest
```
with:
```
SPRING_DATASOURCE_PASSWORD=app-rt-password:latest
```
`FLYWAY_PASSWORD=db-password:latest` stays. Verify:
```powershell
(Select-String -Path cloudbuild.yaml -Pattern 'SPRING_DATASOURCE_PASSWORD=app-rt-password:latest').Count  # expect 12
(Select-String -Path cloudbuild.yaml -Pattern 'FLYWAY_PASSWORD=db-password:latest').Count                  # expect 12
(Select-String -Path cloudbuild.yaml -Pattern 'SPRING_DATASOURCE_PASSWORD=db-password:latest').Count       # expect 0
```

- [ ] **Step 3: Add the secret to the catalog**

In `scripts/secret-manager-catalog.ps1`, add after the `db-password` entry:
```powershell
        [pscustomobject]@{ Name = "app-rt-password"; Purpose = "Database password for app_rt (unprivileged runtime role; owner/Flyway uses db-password)" }
```

- [ ] **Step 4: Validate config integrity (no deploy)**

Run the existing config-shape gates that read cloudbuild:
```powershell
powershell -ExecutionPolicy Bypass -File scripts\audit-deployment-boundaries.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-service-schema-defaults.ps1
```
Expected: both exit 0. Also confirm the secret catalog parses:
```powershell
powershell -ExecutionPolicy Bypass -Command ". .\scripts\secret-manager-catalog.ps1; (Get-SecretManagerCatalog | Where-Object Name -eq 'app-rt-password').Count"
```
Expected: `1`. (If `audit-deployment-boundaries.ps1` or another gate asserts the old `_APP_DB_USER=appuser` value, update that assertion in the same commit and note it here.)

- [ ] **Step 5: Commit**

```powershell
git add cloudbuild.yaml scripts/secret-manager-catalog.ps1
git commit -m "feat(ops): cut runtime over to app_rt in cloudbuild; add app-rt-password secret"
```

---

### Task 6: Prod provisioning script + runbook

**Files:**
- Create: `scripts/invoke-create-app-rt-role-cloudsql.ps1`
- Create: `docs/MICROSERVICE-APP-RT-PROVISIONING-RUNBOOK.md`

**Interfaces:**
- Consumes: `scripts/create-app-rt-role.sql`, `scripts/audit-app-rt-privileges.sql`; Secret Manager `db-password` (appuser) + `app-rt-password`.
- Produces: a repeatable prod provisioning + verification path; the human runbook that orders it before the Task 5 deploy.

- [ ] **Step 1: Create the one-off Cloud Run Job runner**

Create `scripts/invoke-create-app-rt-role-cloudsql.ps1` (modeled on `audit-legacy-compatibility-cloudsql.ps1`):

```powershell
param(
    [string]$Project = "custoking-ims",
    [string]$Region = "asia-south2",
    [string]$HostAddress = "10.116.0.3",
    [int]$Port = 5432,
    [string]$Database = "custoking_ims_v1",
    [string]$ConnectUser = "appuser",
    [string]$Owner = "appuser",
    [string]$ConnectPasswordSecret = "db-password",
    [string]$AppRtPasswordSecret = "app-rt-password",
    [switch]$AuditOnly,
    [string]$Network = "default",
    [string]$Subnet = "default",
    [string]$Gcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
)
$ErrorActionPreference = "Stop"

$createSql = Get-Content (Join-Path $PSScriptRoot "create-app-rt-role.sql") -Raw
$auditSql  = Get-Content (Join-Path $PSScriptRoot "audit-app-rt-privileges.sql") -Raw

$createB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($createSql))
$auditB64  = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($auditSql))

# Runs as appuser; create-app-rt-role.sql elevates via SET ROLE cloudsqlsuperuser (set_superuser=1, owner=appuser).
# Live RLS/DDL probes are NOT run in prod (appuser is not a superuser): the audit asserts the structural
# attributes instead (NOSUPERUSER/NOBYPASSRLS/not-a-cloudsqlsuperuser-member), which guarantees RLS will bite.
$createStep = "printf '%s' '$createB64' | base64 -d > /tmp/create.sql && psql -v ON_ERROR_STOP=1 -v app_rt_password=`"`$APP_RT_PASSWORD`" -v owner=$Owner -v set_superuser=1 -h $HostAddress -p $Port -U $ConnectUser -d $Database -f /tmp/create.sql"
$auditStep  = "printf '%s' '$auditB64' | base64 -d > /tmp/audit.sql && psql -v ON_ERROR_STOP=1 -h $HostAddress -p $Port -U $ConnectUser -d $Database -f /tmp/audit.sql"
$script = if ($AuditOnly) { $auditStep } else { "$createStep && $auditStep" }

$job = "ims-app-rt-" + (Get-Date -Format "yyyyMMddHHmmss")
try {
    & $Gcloud run jobs create $job `
        --project=$Project --region=$Region `
        --image=postgres:16-alpine --command=sh --args=-c,$script `
        --set-env-vars=PGSSLMODE=disable `
        --set-secrets="PGPASSWORD=${ConnectPasswordSecret}:latest,APP_RT_PASSWORD=${AppRtPasswordSecret}:latest" `
        --network=$Network --subnet=$Subnet --vpc-egress=private-ranges-only `
        --max-retries=0 --tasks=1 | Write-Output

    & $Gcloud run jobs execute $job --project=$Project --region=$Region --wait | Write-Output
    Write-Host "app_rt provisioning job completed: $job" -ForegroundColor Green
} finally {
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { & $Gcloud run jobs delete $job --project=$Project --region=$Region --quiet *> $null }
    finally { $ErrorActionPreference = $prev }
}
```

- [ ] **Step 2: Verify the script parses and its help/params load**

Run:
```powershell
powershell -NoProfile -Command "Get-Command -Syntax .\scripts\invoke-create-app-rt-role-cloudsql.ps1"
```
Expected: prints the parameter syntax with no parse error. (No live execution — requires prod gcloud auth + VPC.)

- [ ] **Step 3: Write the runbook**

Create `docs/MICROSERVICE-APP-RT-PROVISIONING-RUNBOOK.md`:

```markdown
# Runbook — Provisioning the `app_rt` Runtime Role (Phase 1, Task 1.1)

Cuts the application runtime over to the unprivileged `app_rt` role. `appuser` stays the
owner + Flyway role. See `docs/superpowers/specs/2026-06-28-app-rt-runtime-role-design.md`.

## Order of operations (MUST be done before the cloudbuild cutover deploys)

1. **Create the secret** (one-time):
   ```
   <generate a strong value> | gcloud secrets create app-rt-password --data-file=- --project=custoking-ims
   ```
   Grant each runtime service account `roles/secretmanager.secretAccessor` on `app-rt-password`
   (same accessors that hold `db-password`).

2. **Create the role + grants in prod Cloud SQL** (idempotent):
   ```
   powershell -ExecutionPolicy Bypass -File scripts\invoke-create-app-rt-role-cloudsql.ps1
   ```
   This runs `create-app-rt-role.sql` (as `appuser`, elevating via `SET ROLE cloudsqlsuperuser`)
   then the structural audit. Confirm the job logs show `create-app-rt-role: role ready` and the
   audit's attribute/membership checks passing.

3. **Re-verify any time** without re-creating:
   ```
   powershell -ExecutionPolicy Bypass -File scripts\invoke-create-app-rt-role-cloudsql.ps1 -AuditOnly
   ```

4. **Deploy the cutover**: merge the `cloudbuild.yaml` change (`_APP_DB_USER=app_rt`,
   runtime secret `app-rt-password`) and run the normal pipeline. Runtime now connects as
   `app_rt`; Flyway still as `appuser`/`db-password`.

5. **Post-deploy check**: services healthy and serving reads/writes; `-AuditOnly` green
   against prod.

## Rollback

Revert `_APP_DB_USER` to `appuser` (and the runtime secret back to `db-password`) and
redeploy. `app_rt` and its grants are harmless and may stay; drop only if required:
`DROP OWNED BY app_rt; DROP ROLE app_rt;` (run as a `cloudsqlsuperuser` member).

## Notes

- `app_rt` is created via SQL (not `gcloud sql users create`) so it is **not** a member of
  `cloudsqlsuperuser` and is therefore subject to RLS.
- Live RLS/DDL probes are exercised locally (superuser); prod asserts the structural
  attributes, which are sufficient to guarantee RLS enforcement for `app_rt`.
```

- [ ] **Step 4: Commit**

```powershell
git add scripts/invoke-create-app-rt-role-cloudsql.ps1 docs/MICROSERVICE-APP-RT-PROVISIONING-RUNBOOK.md
git commit -m "feat(ops): add prod app_rt provisioning job + runbook"
```

---

### Task 7: Wire audit into the migration gate + update review/runbook docs

**Files:**
- Modify: `scripts/verify-microservice-migration.ps1` (add app_rt audit under `-RunDbAudit`)
- Modify: `ARCHITECTURE_REVIEW.md` (note the partial reversal of the single-`appuser` consolidation)
- Modify: `docs/CHANGELOG-architecture-hardening.md` (or the hardening note referenced by `harden-appuser-role.sql`)

**Interfaces:**
- Consumes: `scripts/audit-app-rt-privileges.ps1`.
- Produces: `verify-microservice-migration.ps1 -RunDbAudit` fails if `app_rt` is missing/over-privileged.

- [ ] **Step 1: Add the audit step under the existing DB-audit gate**

In `scripts/verify-microservice-migration.ps1`, inside `if ($RunDbAudit) {`, after the `microservice DB boundary audit` `Invoke-Step` block, add:

```powershell
        Invoke-Step "app_rt runtime role privilege audit" {
            & (Join-Path $PSScriptRoot "audit-app-rt-privileges.ps1") `
                -PostgresContainer $PostgresContainer `
                -Database $Database `
                -DbUser $DbUser
        }
```

- [ ] **Step 2: Run the gate (DB audit only) against the running local stack — verify it passes**

Run (stack from Task 4 still up, `ensure-app-rt-local` already applied):
```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 -RunDbAudit
```
Expected: all steps `OK`, including `app_rt runtime role privilege audit`; exit 0.

- [ ] **Step 3: Verify the gate FAILS when app_rt is wrong (negative test)**

Run:
```powershell
docker exec custoking-postgres psql -U postgres -d postgres -c "ALTER ROLE app_rt BYPASSRLS;"
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 -RunDbAudit
docker exec custoking-postgres psql -U postgres -d postgres -c "ALTER ROLE app_rt NOBYPASSRLS;"
```
Expected: the middle command FAILS at `app_rt runtime role privilege audit` (non-zero); after revert the gate passes again.

- [ ] **Step 4: Update the architecture docs**

In `ARCHITECTURE_REVIEW.md`, where the single-`appuser` consolidation is described (search `appuser`), add a note that Phase 1 Task 1.1 **intentionally re-introduces a separate unprivileged runtime role `app_rt`** so RLS can be enforced; `appuser` remains the owner/Flyway role. In `docs/CHANGELOG-architecture-hardening.md`, append a dated entry recording the `app_rt` introduction and the runtime cutover (do not edit historical entries).

- [ ] **Step 5: Commit**

```powershell
git add scripts/verify-microservice-migration.ps1 ARCHITECTURE_REVIEW.md docs/CHANGELOG-architecture-hardening.md
git commit -m "feat(ops): gate app_rt privilege audit in migration verifier; document app_rt reversal"
```

---

## Self-Review

**Spec coverage:**
- Role with correct attributes / not `cloudsqlsuperuser` member → Tasks 1, 3 (audit), assertions in `create-app-rt-role.sql`. ✓
- USAGE + DML + sequence grants on 12 schemas → Task 2. ✓
- Default privileges for future objects → Task 2 (`ALTER DEFAULT PRIVILEGES`). ✓
- `create-app-rt-role.sql` parameterized + `SET ROLE cloudsqlsuperuser` → Tasks 1, 6. ✓
- Prod runbook + one-off job + `app-rt-password` secret → Task 6; catalog entry Task 5. ✓
- cloudbuild `_APP_DB_USER=app_rt`, runtime/Flyway password split → Task 5. ✓
- Local parity (initdb + service flip + post-migration grant pass) → Task 4. ✓
- Verification audit (DDL-denied, subject-to-RLS) + wired into `verify-microservice-migration.ps1` → Tasks 3, 4, 7. ✓
- Doc updates (review/runbook reversal note) → Task 7. ✓
- Pooler GUC isolation explicitly deferred (1.3/3.3) — out of scope, noted in spec. ✓

**Placeholder scan:** none — every step has concrete SQL/PowerShell/YAML and exact commands. ✓

**Type/name consistency:** role `app_rt`; psql vars `app_rt_password`, `owner`, `set_superuser`, `run_live_probes`; scripts `create-app-rt-role.sql`, `audit-app-rt-privileges.sql`/`.ps1`, `ensure-app-rt-local.ps1`, `invoke-create-app-rt-role-cloudsql.ps1`; secret `app-rt-password`; substitution `_APP_DB_USER` — used consistently across all tasks. ✓

## Risks (carried from spec)

- Cutover before role/secret exist → runbook ordering; rollback = revert `_APP_DB_USER`.
- Reused local volume lacks role/grants → `docker compose down -v` for a clean slate; `ensure-app-rt-local.ps1` is idempotent.
- A new future schema not in the script's list → audit fails on missing USAGE (catches it).
- `audit-deployment-boundaries.ps1` (or sibling) may assert the old `_APP_DB_USER=appuser`; update in Task 5 if so.

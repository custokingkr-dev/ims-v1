# Connection Pooling Finalize Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finalize connection handling by certifying the "no RLS-GUC leak across pooled-connection reuse" property with a test, de-duplicating the Hikari pool config to a single source of truth, and documenting the connection headroom + pooler prerequisites — deferring PgBouncer and the GUC transaction-local conversion.

**Architecture:** One implementer task adds a Testcontainers integration test proving GUC isolation under physical-connection reuse; a second de-duplicates pool config (yml placeholders become the single source; `SPRING_DATASOURCE_HIKARI_*` env removed from cloudbuild/compose) and extends the scaling-thresholds doc; a controller task PRs/merges and redeploys one service to confirm the reconciled config preserves the effective pool values. Behavior-preserving throughout (effective prod values stay max=5, min-idle=0).

**Tech Stack:** Spring Boot 4.0.7 / Java 25, HikariCP, PostgreSQL RLS (`app_rt` role + `app.current_school_id` GUC), Testcontainers, Maven, Cloud Run.

**Spec:** `docs/superpowers/specs/2026-07-04-connection-pooling-finalize-design.md`

## Global Constraints

- **Behavior-preserving.** Effective pool values must stay **max=5, min-idle=0** in prod (the current effective values). No runtime behavior change.
- **Single source of truth for pool config = the `application.yml` Hikari placeholders** (`maximum-pool-size: ${DB_POOL_MAX:5}`, `minimum-idle: ${DB_POOL_MIN:0}`). Remove `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` / `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` from `cloudbuild.yaml` and `docker-compose.yml`.
- **Do NOT introduce PgBouncer / Cloud SQL connector pooling, and do NOT convert the RLS GUC to transaction-local** — both deferred (documented as prerequisites).
- **Do NOT touch the per-schema Flyway migration pools** in `*FlywayConfig.java` (separate, already right-sized in Phase 2).
- **Do NOT weaken any test.** The new GUC test must genuinely exercise RLS as the unprivileged `app_rt` role via the real `TenantAwareDataSource`.
- **Branch:** `phase3-pooling-finalize` (already created off `main`; spec committed there).
- **Maven on Windows (Java 25):** `$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"` then `.\mvnw.cmd -f services\<svc>\pom.xml test`. Docker must be running for Testcontainers.
- **5 services with the yml Hikari block:** billing, identity, operations, platform, school-core (each `services/<svc>-service/src/main/resources/application.yml`, the `spring.datasource.hikari` block at ~lines 11-13).

## File Structure

- `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/security/TenantGucConnectionReuseIntegrationTest.java` — **create** (Task 1): certifies no GUC leak on a reused physical connection.
- `services/{billing,identity,operations,platform,school-core}-service/src/main/resources/application.yml` — **modify** (Task 2): `minimum-idle: ${DB_POOL_MIN:1}` → `${DB_POOL_MIN:0}`.
- `cloudbuild.yaml` — **modify** (Task 2): remove the two `SPRING_DATASOURCE_HIKARI_*` entries from `common_env`.
- `docker-compose.yml` — **modify** (Task 2): remove the `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` lines from the service blocks.
- `docs/DB-SCALING-THRESHOLDS.md` — **modify** (Task 2): add the "Connection pooling" section.

---

### Task 1: Certify no RLS-GUC leak across pooled-connection reuse (integration test)

**Files:**
- Create: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/security/TenantGucConnectionReuseIntegrationTest.java`
- Model on (read first): `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/security/StudentRlsIntegrationTest.java` (and its siblings `AttendanceRlsIntegrationTest`/`CatalogRlsIntegrationTest`/`FeeRlsIntegrationTest`) — reuse their Testcontainers Postgres setup, `app_rt` runtime-role wiring, RLS-enabled schema, seed data, and `TenantContext` population.

**Interfaces:**
- Consumes: the existing `TenantAwareDataSource` (`…/security/TenantAwareDataSource.java`, sets `app.current_school_id` session-level per borrow), `TenantContext` (`…/security/TenantContext.java`), and the existing `*RlsIntegrationTest` harness (container + `app_rt` role + a tenant-scoped table with two schools' rows).

- [ ] **Step 1: Read the existing RLS test harness**

Read `StudentRlsIntegrationTest.java` (and note the shared setup its siblings use): how the Testcontainers Postgres is started, how the `app_rt` unprivileged role is created/used, how RLS + the schema + two schools' seed rows are set up, and how `TenantContext` is populated for a school. You will reuse this harness — do not reinvent it.

- [ ] **Step 2: Write the failing test**

Create `TenantGucConnectionReuseIntegrationTest.java` in the same package, reusing the sibling harness. The novel logic (the part these tests don't yet cover) is proving isolation holds when the **same physical connection** is reused across tenants. Force reuse by configuring the test datasource's Hikari `maximumPoolSize=1`, then drive two borrows through `TenantAwareDataSource` under different `TenantContext`s:

```java
// Pseudocode of the assertions — adapt types/setup to match the sibling *RlsIntegrationTest harness:
// Given: RLS-enabled tenant-scoped table seeded with a row for school A (id=SCHOOL_A) and school B (id=SCHOOL_B),
// a TenantAwareDataSource wrapping a Hikari pool with maximumPoolSize=1 connecting as app_rt.

// 1) Tenant A sees only A
TenantContext.set(contextForSchool(SCHOOL_A));           // non-superadmin, schoolId=SCHOOL_A
try (Connection c = tenantAwareDataSource.getConnection();
     Statement s = c.createStatement();
     ResultSet rs = s.executeQuery("SELECT school_id FROM <tenant_scoped_table>")) {
    List<Long> ids = collect(rs);
    assertThat(ids).containsExactly(SCHOOL_A);            // A visible, B invisible
}

// 2) Tenant B, SAME physical connection (pool size 1), sees only B — not A (proves no session-GUC leak)
TenantContext.set(contextForSchool(SCHOOL_B));
try (Connection c = tenantAwareDataSource.getConnection();
     Statement s = c.createStatement();
     ResultSet rs = s.executeQuery("SELECT school_id FROM <tenant_scoped_table>")) {
    List<Long> ids = collect(rs);
    assertThat(ids).containsExactly(SCHOOL_B);            // B visible, A still invisible
}

// 3) Empty/no-tenant context fails closed — 0 rows (not a leak of the previous tenant)
TenantContext.set(emptyContext());                        // schoolId=null, not superadmin
try (Connection c = tenantAwareDataSource.getConnection();
     Statement s = c.createStatement();
     ResultSet rs = s.executeQuery("SELECT school_id FROM <tenant_scoped_table>")) {
    assertThat(collect(rs)).isEmpty();
}
```
Use a table that the sibling tests already RLS-enable and seed for two schools (e.g. the one `StudentRlsIntegrationTest` uses). Clear `TenantContext` in an `@AfterEach` (mirror the siblings). Name the test methods descriptively (`tenantBOnReusedConnectionCannotSeeTenantA`, `emptyContextFailsClosed`).

- [ ] **Step 3: Run it — expect PASS (this certifies existing behavior)**

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\school-core-service\pom.xml "-Dtest=TenantGucConnectionReuseIntegrationTest" test
```
Expected: **PASS** — the session-level-set-on-every-borrow mechanism overwrites the GUC per borrow, so tenant B (and the empty context) never sees A's rows even on the reused connection. **If it FAILS (tenant B sees A's rows), STOP and report — that is a real cross-tenant leak (Critical), not a test to adjust.**

- [ ] **Step 4: Run the full school-core suite (no regression)**

```
.\mvnw.cmd -f services\school-core-service\pom.xml clean test
```
Expected: BUILD SUCCESS at the baseline count **plus the new test** (previous baseline was 228 → expect 228 + the new test's methods).

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/security/TenantGucConnectionReuseIntegrationTest.java
git commit -m "test(school-core): certify no RLS-GUC leak on reused pooled connection (Phase 3 Task 3.3)"
```

---

### Task 2: De-duplicate pool config + document connection pooling

**Files:**
- Modify: `services/{billing,identity,operations,platform,school-core}-service/src/main/resources/application.yml`
- Modify: `cloudbuild.yaml` (the `common_env` line, ~line 88)
- Modify: `docker-compose.yml` (the `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` service lines)
- Modify: `docs/DB-SCALING-THRESHOLDS.md`

**Interfaces:**
- Consumes: nothing from Task 1 (independent). Produces the single-source pool config.

- [ ] **Step 1: In all 5 service `application.yml`, set the min-idle default to 0**

In each `services/<svc>-service/src/main/resources/application.yml`, the `spring.datasource.hikari` block currently reads:
```yaml
    hikari:
      maximum-pool-size: ${DB_POOL_MAX:5}
      minimum-idle: ${DB_POOL_MIN:1}
```
Change `minimum-idle` to default 0 (preserving prod's current effective min-idle=0):
```yaml
    hikari:
      maximum-pool-size: ${DB_POOL_MAX:5}
      minimum-idle: ${DB_POOL_MIN:0}
```
Leave `maximum-pool-size` as `${DB_POOL_MAX:5}`.

- [ ] **Step 2: Remove the duplicate `SPRING_DATASOURCE_HIKARI_*` from `cloudbuild.yaml`**

In `cloudbuild.yaml` `common_env` (~line 88), delete `,SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5,SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0` from the value (so pool size now comes solely from the yml default / optional `DB_POOL_MAX`/`DB_POOL_MIN`). Change nothing else on that line.

- [ ] **Step 3: Remove the `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` lines from `docker-compose.yml`**

Delete every `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "…"` line in the service `environment:` blocks (there are ~5, values 3 or 5). After removal each service uses the yml default (max=5). (If a service intentionally needs a smaller local pool, set `DB_POOL_MAX: "3"` instead — but the arbitrary 3-vs-5 split has no functional reason at this scale, so standardizing on the yml default is correct.)

- [ ] **Step 4: Validate the compose + effective-value reconciliation**

```
cd /d/Projects/ims-v1
docker compose config -q && echo "compose OK"
grep -rn "SPRING_DATASOURCE_HIKARI" cloudbuild.yaml docker-compose.yml
```
Expected: `compose OK` (exit 0); the grep returns **no matches** (all removed). Confirm the yml now solely defines the pool: `grep -A2 "hikari:" services/school-core-service/src/main/resources/application.yml` shows `${DB_POOL_MAX:5}` / `${DB_POOL_MIN:0}`.

- [ ] **Step 5: Add the "Connection pooling" section to `docs/DB-SCALING-THRESHOLDS.md`**

Append a section covering:
- **Current headroom:** peak ≈ 5 domain services × Hikari `maximum-pool-size` 5 × `max-instances` ≤2 ≈ ~50, plus identity/gateway (min-instances 1) and transient per-schema Flyway migration pools (max 3, min-idle 0, drain after migrate) — vs `max_connections=200` (≈25% utilization).
- **Single source of truth:** pool size/min-idle are defined ONLY in each service's `application.yml` (`spring.datasource.hikari.maximum-pool-size: ${DB_POOL_MAX:5}`, `minimum-idle: ${DB_POOL_MIN:0}`); override per-env via `DB_POOL_MAX`/`DB_POOL_MIN`. The `SPRING_DATASOURCE_HIKARI_*` env duplication was removed.
- **Pooler threshold:** introduce a pooler (PgBouncer transaction-mode or Cloud SQL connector pooling) when peak connections approach ~150/200 (e.g. as `max-instances` or per-service pool sizes grow).
- **Prerequisite for transaction-mode pooling:** the RLS GUC in `services/*/security/TenantAwareDataSource.java` is currently **session-level** (`set_config('app.current_school_id', …, false)`, set on every Hikari borrow — safe with direct Hikari, certified by `TenantGucConnectionReuseIntegrationTest`). Transaction-mode pooling multiplexes physical backends per transaction, so it first requires converting this to **transaction-local** (`SET LOCAL` / `set_config(…, true)`) set inside each transaction, plus reworking the autocommit read path (which is why session-level was chosen). Do this conversion + its own RLS tests before enabling a transaction-mode pooler.

- [ ] **Step 6: Commit**

```bash
git add services/billing-service/src/main/resources/application.yml services/identity-service/src/main/resources/application.yml services/operations-service/src/main/resources/application.yml services/platform-service/src/main/resources/application.yml services/school-core-service/src/main/resources/application.yml cloudbuild.yaml docker-compose.yml docs/DB-SCALING-THRESHOLDS.md
git commit -m "chore(db): single-source Hikari pool config + document pooling headroom/threshold (Phase 3 Task 3.3)"
```

---

### Task 3: PR/merge + verify reconciled config in prod (controller)

**Owner:** controller. Runs after Tasks 1–2 are reviewed. Confirms the de-dup preserved the effective pool values.

- [ ] **Step 1: PR → CI → merge**

Push `phase3-pooling-finalize`; open PR (`chore: Phase 3 Task 3.3 — connection pooling finalize`); watch CI — all `service-test` jobs green (the 5 services build with the yml change; school-core runs the new GUC test), `secret-scan`, `docker-build`, `ci-result` green. Merge to `main`.

- [ ] **Step 2: Redeploy one representative service (school-core) + confirm the effective pool config**

```bash
gh workflow run deploy.yml --ref main -f environment=production -f deploy_services=school-core-service -f run_direct_smoke=false
# after Ready=True, check the Hikari config line in the startup logs:
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="custoking-school-core-service" AND textPayload:"HikariPool"' --freshness=8m --limit=10 --format='value(textPayload)'
```
Expected: deploy `success` + `Ready=True`; the Hikari startup log shows the pool configured with `maximumPoolSize=5` and `minimumIdle=0` (the preserved effective values — proving the de-dup, now sourced solely from the yml, is behavior-preserving). Then an authenticated route sanity check:
```bash
GW="https://custoking-api-gateway-xkv7oenbna-em.a.run.app"
TOKEN=$(curl -s -X POST "$GW/api/v1/auth/login" -H "Content-Type: application/json" -d '{"email":"e2e-superadmin@local.test","password":"password"}' | python -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")
echo "/schools -> $(curl -s -o /dev/null -w '%{http_code}' "$GW/api/v1/schools" -H "Authorization: Bearer $TOKEN")"
echo "admin RLS /students -> $(curl -s -o /dev/null -w '%{http_code}' "$GW/api/v1/students" -H "Authorization: Bearer $(curl -s -X POST "$GW/api/v1/auth/login" -H 'Content-Type: application/json' -d '{"email":"e2e-admin@local.test","password":"password"}' | python -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")")"
```
Expected: `/schools` → 200; admin `/students` → 200 (RLS scope still enforced). The other 4 services pick up the identical yml default on their next natural deploy (behavior-preserving — effective values unchanged).

- [ ] **Step 3: Record completion (controller)**

Update the prod-state memory: Phase 3 Task 3.3 done (right-sized) — no-GUC-leak certified by test, pool config single-sourced to yml (max=5, min-idle=0), pooling headroom/threshold + transaction-local-GUC prerequisite documented; PgBouncer + GUC conversion deferred. Remaining Phase 3: Task 3.1 (reporting outbox). Do NOT start 3.1 — separate spec/plan.

---

## Self-Review

**Spec coverage:**
- Component 1 (no-GUC-leak integration test, reused connection, empty-context fail-closed) → Task 1. ✓
- Component 2 (de-dup pool config to yml single-source; min-idle default :1→:0; remove SPRING_DATASOURCE_HIKARI_* from cloudbuild+compose) → Task 2 Steps 1-4/6. ✓
- Component 3 (extend DB-SCALING-THRESHOLDS.md: headroom, single-source, pooler threshold, transaction-local prerequisite) → Task 2 Step 5. ✓
- Verification (behavior-preserving; redeploy school-core, confirm Hikari max=5/min-idle=0; RLS still enforced) → Task 3. ✓
- Constraints: behavior-preserving effective max=5/min-idle=0; no PgBouncer; no GUC conversion; Flyway pools untouched; no test weakening → Global Constraints + task steps. ✓
- Non-goals respected. ✓

**Placeholder scan:** Task 1's test uses `<tenant_scoped_table>` / `SCHOOL_A`/`SCHOOL_B` as placeholders resolved by reading the sibling `*RlsIntegrationTest` harness (Step 1) — the novel assertions and the reuse mechanic (maxPoolSize=1, per-tenant borrow, empty-context fail-closed) are concrete; the config edits (exact yml lines, the exact env strings to remove, the doc sections) are fully specified. No lazy placeholders.

**Consistency:** the effective values (max=5, min-idle=0), the yml property paths, the `SPRING_DATASOURCE_HIKARI_*` env names to remove, the 5 service names, the branch `phase3-pooling-finalize`, and the test class name `TenantGucConnectionReuseIntegrationTest` are consistent across tasks and match the spec.

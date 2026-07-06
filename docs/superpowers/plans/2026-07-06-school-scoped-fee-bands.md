# School-Scoped Fee Bands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make fee bands (and their items) school-owned — add `school_id` + RLS to `fee_bands`/`fee_items`, migrate today's global/shared bands into per-school copies, and scope create/read/delete accordingly.

**Architecture:** A forward-only `fee` V8 migration copies each (band, using-school) into a school-owned band+items and repoints that school's `fee_assignments`, then drops the originals + unassigned bands, then enables RLS. `FeeReadRepository` create paths stamp `school_id`; reads rely on RLS (app_rt auto-scoped) plus an explicit `school_id` filter for superadmin. Controllers resolve the school via `TenantScope` and thread it into create. Migration is tested (Testcontainers).

**Tech Stack:** Spring Boot 4.0.7 / Java 25, `JdbcClient`, Flyway (`fee` history), Testcontainers `postgres:16`; React 18 + Vite + TS.

## Global Constraints

- **Copy-per-school, full isolation.** Each band belongs to exactly one school. A band shared by N schools → N independent copies. No global template tier.
- **Existing data:** for each `(band_id, school_id)` in `fee_assignments`, create a school-owned copy of the band + its items and repoint that school's assignments to the copy; drop the originals. **Bands with no assignments are dropped** (with their items).
- **RLS** on `fee_bands`/`fee_items` uses the exact `tenant_isolation` policy shape from `fee/V7__enable_rls.sql` (GUC `app.current_school_id`, bypass `app.bypass_rls='on'`). Runtime role is `app_rt`; migrations/tests run as `appuser`/`owner` (RLS-exempt).
- **`school_id` on an inserted band/item MUST equal the tenant GUC** (RLS `WITH CHECK`). For a school admin, `TenantScope.resolveSchoolId(...)` returns their school (matches the GUC). Superadmin (bypass on) must supply the target `schoolId`.
- **This rewrites financial data and is irreversible.** Dev first (prod gated). The Testcontainers migration test is the correctness gate; a pre-prod DB backup + band/assignment count spot-check is required before any prod promotion.
- **No change to fee amounts / discounts / payments** — ownership/isolation only.
- Build/test with **JDK 25** (`C:\Program Files\Java\jdk-25.0.3`); `.\mvnw.cmd -f services\school-core-service\pom.xml …`. The next `fee` migration is **V8** (V1–V7 exist).

---

### Task 1: V8 migration (copy-per-school + RLS) + migration test

**Files:**
- Create: `services/school-core-service/src/main/resources/db/migration/fee/V8__school_scope_fee_bands.sql`
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/FeeBandSchoolScopeMigrationTest.java`

**Interfaces:**
- Produces: `fee_bands` and `fee_items` each gain `school_id BIGINT NOT NULL` + RLS `tenant_isolation`; shared bands split per school; unassigned bands dropped.

- [ ] **Step 1: Write the migration**

`V8__school_scope_fee_bands.sql`:

```sql
-- Phase 1: nullable school_id on bands + items.
ALTER TABLE fee.fee_bands ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE fee.fee_items ADD COLUMN IF NOT EXISTS school_id BIGINT;

-- Phase 2: copy-per-school. For each (band, school) that has assignments, create a
-- school-owned copy of the band + its items and repoint that school's assignments.
DO $$
DECLARE r RECORD; new_band TEXT;
BEGIN
    FOR r IN
        SELECT DISTINCT band_id, school_id
        FROM fee.fee_assignments
        WHERE school_id IS NOT NULL
    LOOP
        new_band := gen_random_uuid()::text;
        INSERT INTO fee.fee_bands
            (id, name, class_from, class_to, discount, active_schedules_csv,
             created_at, updated_at, academic_year_id, school_id)
        SELECT new_band, name, class_from, class_to, discount, active_schedules_csv,
               created_at, now(), academic_year_id, r.school_id
        FROM fee.fee_bands WHERE id = r.band_id;

        INSERT INTO fee.fee_items
            (id, name, frequency, amount, created_at, updated_at, band_id, school_id)
        SELECT gen_random_uuid()::text, name, frequency, amount, created_at, now(), new_band, r.school_id
        FROM fee.fee_items WHERE band_id = r.band_id;

        UPDATE fee.fee_assignments
           SET band_id = new_band
         WHERE band_id = r.band_id AND school_id = r.school_id;
    END LOOP;

    -- Originals (now unreferenced) and truly-unassigned bands still have school_id IS NULL → drop.
    DELETE FROM fee.fee_items WHERE band_id IN (SELECT id FROM fee.fee_bands WHERE school_id IS NULL);
    DELETE FROM fee.fee_bands WHERE school_id IS NULL;
END $$;

-- Phase 3: enforce NOT NULL + RLS (mirrors fee/V7 policy).
ALTER TABLE fee.fee_bands ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE fee.fee_items ALTER COLUMN school_id SET NOT NULL;

ALTER TABLE fee.fee_bands ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fee.fee_bands;
CREATE POLICY tenant_isolation ON fee.fee_bands
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE fee.fee_items ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fee.fee_items;
CREATE POLICY tenant_isolation ON fee.fee_items
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

CREATE INDEX IF NOT EXISTS idx_fee_bands_school_year ON fee.fee_bands (school_id, academic_year_id);
CREATE INDEX IF NOT EXISTS idx_fee_items_school_band ON fee.fee_items (school_id, band_id);
```

- [ ] **Step 2: Write the failing migration test**

Create `FeeBandSchoolScopeMigrationTest.java`. It migrates the `fee` schema **to V7**, seeds the pre-migration global state, then migrates **to V8** and asserts the transformation (owner connection → RLS-exempt):

```java
package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class FeeBandSchoolScopeMigrationTest {

    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void up() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
    }

    @AfterAll
    static void down() { if (PG != null) PG.stop(); }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("fee").defaultSchema("fee")
                .locations("classpath:db/migration/fee")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    @Test
    void copyPerSchool_splitsSharedBands_repointsAssignments_dropsUnassigned() throws Exception {
        flyway("7").clean();          // fresh
        flyway("7").migrate();        // schema up to V7 (global bands, no school_id on bands/items)

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // shared band B1 (assigned in schools 10 and 20), single-school band B2 (school 10),
            // unassigned band B3.
            st.execute("INSERT INTO fee.fee_bands(id,name,class_from,class_to,discount,academic_year_id) VALUES " +
                    "('B1','Shared',1,5,0.0,'AY'),('B2','Single',6,8,0.0,'AY'),('B3','Unused',9,10,0.0,'AY')");
            st.execute("INSERT INTO fee.fee_items(id,name,frequency,amount,band_id) VALUES " +
                    "('I1','Tuition','Annual',1000,'B1'),('I2','Lab','Annual',200,'B2'),('I3','X','Annual',5,'B3')");
            st.execute("INSERT INTO fee.fee_assignments" +
                    "(id,band_discount,manual_discount,surcharge,net_payable,paid_amount,student_id,band_id,academic_year_id,version,school_id) VALUES " +
                    "('A1',0,0,0,1000,0,101,'B1','AY',0,10)," +
                    "('A2',0,0,0,1000,0,201,'B1','AY',0,20)," +
                    "('A3',0,0,0,200,0,102,'B2','AY',0,10)");
        }

        flyway("8").migrate();        // run V8 copy-per-school

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // B1 -> 2 school-owned copies (schools 10 & 20); B2 -> 1 (school 10); B3 gone.
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_bands")).isEqualTo(3);         // 2 + 1
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_bands WHERE school_id IS NULL")).isEqualTo(0);
            assertThat(scalar(st, "SELECT count(DISTINCT school_id) FROM fee.fee_bands WHERE name='Shared'")).isEqualTo(2);
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_bands WHERE name='Unused'")).isEqualTo(0);
            // items copied per band (Shared x2, Single x1); orphan I3 gone.
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_items")).isEqualTo(3);
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_items WHERE school_id IS NULL")).isEqualTo(0);
            // every assignment now points at a band owned by its own school.
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_assignments a " +
                    "JOIN fee.fee_bands b ON b.id=a.band_id WHERE b.school_id <> a.school_id")).isEqualTo(0);
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_assignments")).isEqualTo(3);
        }
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }
}
```

- [ ] **Step 3: Run the test to verify it fails, then passes**

Run:
```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=FeeBandSchoolScopeMigrationTest
```
Expected: PASS (real Testcontainers). If Docker is unavailable it self-skips — say so in the report.

- [ ] **Step 4: Commit**

```bash
git add services/school-core-service/src/main/resources/db/migration/fee/V8__school_scope_fee_bands.sql \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/FeeBandSchoolScopeMigrationTest.java
git commit -m "feat(fee): V8 migration — school-scope fee bands (copy-per-school) + RLS"
```

---

### Task 2: Repository — stamp school_id on create, scope superadmin reads

**Files:**
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/FeeReadRepository.java`
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/FeeBandSchoolScopeRepoTest.java`

**Interfaces:**
- Consumes: Task 1's `school_id` columns + RLS.
- Produces: `createBand`/`createItem` write `school_id`; `bands(String academicYearId, Long schoolId)` and `feeStructure(String, Long)` accept an optional superadmin `schoolId` filter.

- [ ] **Step 1: `createBand` writes `school_id`**

In `createBand`, read the resolved school from the request (the controller injects it in Task 3) and add it to the INSERT. Replace the INSERT block:

```java
        Long schoolId = requireSchool(request.get("schoolId"));
        jdbc.sql("""
                INSERT INTO fee.fee_bands(id, name, class_from, class_to, discount, active_schedules_csv,
                                      created_at, updated_at, academic_year_id, school_id)
                VALUES (:id, :name, :classFrom, :classTo, :discount, :schedules, :createdAt, :updatedAt, :academicYearId, :schoolId)
                """)
                .param("id", id)
                .param("name", name)
                .param("classFrom", classFrom)
                .param("classTo", classTo)
                .param("discount", doubleValue(request.get("discount"), 0))
                .param("schedules", schedules)
                .param("createdAt", now)
                .param("updatedAt", now)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .update();
        return bandWithItems(id);
```

Add a private helper near the other validators:

```java
    private Long requireSchool(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("A school must be selected to create a fee plan");
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid school id");
        }
    }
```

- [ ] **Step 2: `createItem` writes `school_id` from its band**

`bandRecord(bandId)` already fetches the band (RLS-scoped, so app_rt can only add items to its own bands). Have `bandRecord` return the band's `school_id` and pass it into the item INSERT. In `createItem`, replace:

```java
        Map<String, Object> band = bandRecord(bandId);   // already called for existence; capture it
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                INSERT INTO fee.fee_items(id, name, frequency, amount, created_at, updated_at, band_id, school_id)
                VALUES (:id, :name, :frequency, :amount, :createdAt, :updatedAt, :bandId, :schoolId)
                """)
                .param("id", id)
                .param("name", requireText(firstPresent(request, "itemName", "name"), "Item name is required"))
                .param("frequency", textOrDefault(request.get("frequency"), "Annual"))
                .param("amount", toPaise(request.get("amount")))
                .param("createdAt", now)
                .param("updatedAt", now)
                .param("bandId", bandId)
                .param("schoolId", band.get("schoolId"))
                .update();
        return bandWithItems(bandId);
```

Ensure `bandRecord(...)` selects `school_id` and includes it in its returned map as `schoolId` (find the `bandRecord` method and add `school_id` to its SELECT + `"schoolId", rs.getLong("school_id")` to the row). Keep its existing keys unchanged.

- [ ] **Step 3: Add an optional superadmin `schoolId` filter to `bands` and `feeStructure`**

RLS auto-scopes `app_rt`. Superadmin (bypass) sees all — so when a `schoolId` is supplied, filter explicitly. Change the signatures and SQL:

```java
    public List<FeeBandRow> bands(String academicYearId, Long schoolId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, class_from, class_to, discount, active_schedules_csv,
                       created_at, updated_at, academic_year_id
                FROM fee.fee_bands
                WHERE 1=1
                """);
        if (academicYearId != null && !academicYearId.isBlank()) sql.append(" AND academic_year_id = :academicYearId");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        sql.append(" ORDER BY class_from, class_to, name");
        var spec = jdbc.sql(sql.toString());
        if (academicYearId != null && !academicYearId.isBlank()) spec = spec.param("academicYearId", academicYearId);
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        return spec.query(FeeBandRow.class).list();
    }
```

```java
    public Map<String, Object> feeStructure(String academicYearId, Long schoolId) {
        Map<String, Object> year = academicYear(academicYearId);
        StringBuilder sql = new StringBuilder("SELECT id FROM fee.fee_bands WHERE academic_year_id = :academicYearId");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        sql.append(" ORDER BY class_from ASC, name ASC");
        var spec = jdbc.sql(sql.toString()).param("academicYearId", year.get("id"));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        List<Map<String, Object>> bands = spec.query(String.class).list().stream().map(this::bandWithItems).toList();
        return row("academicYearId", year.get("id"), "academicYear", year.get("label"), "bands", bands);
    }
```

(Leave `matchBand`, `items`, `bandWithItems`, `deleteBand` as-is — they run under RLS for app_rt; superadmin `matchBand`/`items` cross-school is not a surfaced path.)

- [ ] **Step 4: Write the repo test (Testcontainers, RLS on)**

Create `FeeBandSchoolScopeRepoTest.java` — migrate `tenant_school` + `fee` (full, so RLS is enabled), instantiate `FeeReadRepository` on an **`app_rt`** pool wrapped by `TenantAwareDataSource` (mirror `security/FeeRlsIntegrationTest.java`'s bootstrap: create the `app_rt` role, grants, `TenantAwareDataSource`, `TenantContext`). Assert:
- `createBand({..., schoolId:10})` under `TenantContext(school 10)` inserts a band visible to school 10 and **not** to school 20.
- `bands(null, null)` under school-10 context returns only school-10 bands (RLS); `bands(null, 20L)` under superadmin/bypass returns only school-20 bands.
- `deleteBand` of a school-10 band with no assignments succeeds; with a same-school assignment returns the numbered `IllegalArgumentException`.

(Use `FeeRlsIntegrationTest` as the structural template for the `app_rt`/`TenantContext` setup; use `AttendanceLateLeaveRoundTripTest` as the template for direct repo construction.)

- [ ] **Step 5: Run tests**

```bash
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=FeeBandSchoolScopeRepoTest
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest="Fee*"
```
Expected: new test passes; the existing fee suite stays green. **Note:** existing fee tests (e.g. `FeeBandDeleteIntegrationTest`) that seed `fee_bands`/`fee_items` without `school_id` will now fail the NOT NULL/RLS — update those fixtures to include `school_id` (and set a `TenantContext`/bypass) as part of this task; list each one you touch in the report.

- [ ] **Step 6: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/FeeReadRepository.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/FeeBandSchoolScopeRepoTest.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/FeeBandDeleteIntegrationTest.java
git commit -m "feat(fee): stamp school_id on band/item create; superadmin school filter; RLS-aware fee tests"
```

---

### Task 3: Controllers — resolve + inject the school into create

**Files:**
- Modify: `services/school-core-service/.../api/FeeReadController.java`
- Modify: `services/school-core-service/.../api/compat/FeePublicCompatibilityController.java`
- Modify: `services/school-core-service/.../api/dto/CreateBandRequest.java`

**Interfaces:**
- Consumes: Task 2's `createBand(request-with-schoolId)`, `bands(ay, schoolId)`, `feeStructure(ay, schoolId)`.
- Produces: both create endpoints inject `TenantScope.resolveSchoolId(...)`; read endpoints pass `schoolId`.

- [ ] **Step 1: Add optional `schoolId` to `CreateBandRequest`**

Add a nullable `Long schoolId` field to the record (so a superadmin can target a school; school admins omit it and get their own via TenantScope). Keep existing fields/validation.

- [ ] **Step 2: `FeeReadController.createBand` injects the resolved school**

```java
        requireToken(token, "fee:write");
        Long schoolId = TenantScope.resolveSchoolId(req.schoolId());
        Map<String, Object> body = new HashMap<>();
        body.put("name", req.name());
        if (req.classFrom() != null) body.put("classFrom", req.classFrom());
        if (req.classTo() != null) body.put("classTo", req.classTo());
        if (req.schedules() != null) body.put("schedules", req.schedules());
        if (req.discount() != null) body.put("discount", req.discount());
        if (schoolId != null) body.put("schoolId", schoolId);
        return execute(() -> fees.createBand(body));
```

Update the `/bands` GET and `/structure` GET to accept + pass `schoolId`:

```java
    @GetMapping("/bands")
    public List<FeeBandRow> bands(@RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
                                  @RequestParam(required = false) String academicYearId,
                                  @RequestParam(required = false) Long schoolId) {
        requireToken(token, "fee:read");
        return fees.bands(academicYearId, TenantScope.resolveSchoolId(schoolId));
    }
```
```java
    @GetMapping("/structure")
    public Map<String, Object> structure(@RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
                                         @RequestParam(required = false) String academicYearId,
                                         @RequestParam(required = false) Long schoolId) {
        requireToken(token, "fee:read");
        return execute(() -> fees.feeStructure(academicYearId, TenantScope.resolveSchoolId(schoolId)));
    }
```

- [ ] **Step 3: Compat `createBand` (raw map) injects the resolved school**

The compat controller passes the raw `request` map. Resolve and inject `schoolId` before calling the repo (mirror its existing `resolveAndScopeSchool` idiom if present; otherwise inline):

```java
    @PostMapping("/api/v1/fee-structure/band")
    public Map<String, Object> createBand(@RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
                                          @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:read");
        Long requested = request.get("schoolId") == null ? null : Long.valueOf(String.valueOf(request.get("schoolId")));
        Long scope = TenantScope.resolveSchoolId(requested);
        if (scope != null) request.put("schoolId", scope);
        return run(() -> fees.createBand(request));
    }
```

Also thread `schoolId` into the compat `/fee-structure` (and `/fee-structure/export`) GETs if they call `feeStructure`/`bands` — pass `TenantScope.resolveSchoolId(schoolId)` (add a `@RequestParam(required=false) Long schoolId` where the frontend sends `schoolScopedParams`).

- [ ] **Step 4: Compile + run fee controller tests**

```bash
.\mvnw.cmd -f services\school-core-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest="Fee*"
```
Expected: compile SUCCESS; fee tests green. Update any controller test that calls the changed `bands(...)`/`feeStructure(...)` arities or the createBand path (list them in the report).

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/FeeReadController.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/compat/FeePublicCompatibilityController.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/dto/CreateBandRequest.java
git commit -m "feat(fee): resolve + inject school into band create; school filter on fee reads"
```

---

### Task 4: Frontend — superadmin school context for band create + verify

**Files:**
- Modify: `frontend/src/pages/workspace/panels/FeeStructurePanel.tsx`

**Interfaces:**
- Consumes: the create endpoint now requiring a resolvable school.

- [ ] **Step 1: Ensure create carries a school**

`FeeStructurePanel` already sends `schoolScopedParams` on reads. For a **school admin** the server resolves their school from the token, so band create works unchanged. For a **superadmin**, the create must target a specific school — if the panel is used by superadmin without a selected school, `createBand` now returns 400 ("A school must be selected…"). Confirm the panel's create path includes the selected `schoolId` when the user is a platform admin (it already computes `schoolScopedParams` from `user.branchId`/selection). If superadmin fee management has no school selected, surface the 400 as the existing error banner (acceptable), or add a small school selector — implement only if superadmin fee-config is a used path; otherwise leave the 400 to guide them.

- [ ] **Step 2: Build**

```bash
cd frontend
npm run build
```
Expected: build succeeds (no contract change; `createBand`/reads still POST/GET the same shapes).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/workspace/panels/FeeStructurePanel.tsx
git commit -m "chore(fee-ui): ensure band create carries the school; verify scoped fee config"
```

---

## Self-Review Notes

- **Spec coverage:** migration copy-per-school + drop-unassigned + RLS (T1); create stamps school_id + superadmin read filter (T2); controllers resolve/inject school (T3); frontend verify (T4). All spec sections covered.
- **RLS WITH CHECK:** `createBand`/`createItem` stamp `school_id` = resolved school; for a school admin that equals the tenant GUC (insert passes); superadmin bypass passes and supplies the target school. `requireSchool` 400s if unresolved.
- **Existing-test fallout is expected and in-scope:** fee fixtures that insert bands/items without `school_id` break under NOT NULL/RLS — T2/T3 update them (named in each report).
- **Migration test method:** migrate to V7, seed the global state, migrate to V8, assert — the only way to exercise the data transformation through real Flyway.
- **Irreversible financial migration:** dev-first; the migration test is the gate; pre-prod backup + count spot-check required (per spec) before prod promotion.
- **Out of scope:** template library, cross-school sharing, any fee amount/payment logic.
```
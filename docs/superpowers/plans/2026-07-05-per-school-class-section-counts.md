# Per-School Class/Section Counts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a school's class/section counts editable after onboarding (superadmin and own-school admin), and make every class/section picker reflect that school's configured counts.

**Architecture:** `schools.configured_class_count` / `configured_section_count` stay the source of truth. A new transactional `PUT /api/v1/schools/{id}/structure` grows sections (idempotent create + reactivate) or shrinks them (deactivate), rejecting a shrink that would orphan students with **409**. `GET /classes` becomes school-scoped (first-N by `sort_order`). The two Students-workspace filter helpers and the pickers exclude inactive sections. Frontend gains an SA edit modal, a school-admin settings panel, and de-hardcoded class/section dropdowns in AddStudent.

**Tech Stack:** Java 25 / Spring Boot 4.0.7, Spring `JdbcClient`, PostgreSQL, Flyway, Testcontainers + JUnit 5, Mockito, MockMvc (standalone); React 18 + TypeScript + Vitest.

## Global Constraints

- Class count bounds **[1, 12]**; section count bounds **[1, 26]** — reject out-of-range with **400** (matches onboarding validation).
- Shrink that would drop a class/section **containing students** → **409 Conflict** with a human message naming the offender; no mutation on rejection. Grow is always allowed.
- **Authorization is enforced server-side by `TenantScope` + the internal route token — NOT by a new end-user permission code.** `TenantScope.resolveSchoolId(id)`: superadmin bypasses (any school); a non-superadmin is locked to their authenticated school and a cross-tenant `{id}` → **403**. **DEVIATION FROM SPEC:** the spec proposed a new `school:structure:edit` permission code, but this controller does not gate on end-user permission codes (there is no runtime permission catalog in Java; codes are DB-seeded), so a new code would be dead server-side and only a fragile cross-service DB seed for a frontend `can()`. We therefore drop the new code: the SA edit lives in the already-superadmin-gated SA panel, and the school-admin editor lives in the admin workspace where only school-scoped admins operate. Confirm this deviation is acceptable before Task 3.
- Classes remain a **global** master list; a school "has" the first `configured_class_count` classes by `sort_order, name`. No new class table.
- Section count is **uniform per class**; per-class variation is achieved only by the `active` flag, never by separate counts.
- All new SQL is **fully schema-qualified** (`tenant_school.*`, `student.*`) — no reliance on `search_path`.
- Money/student PII rules are unchanged; this feature touches neither.

---

## File Structure

**Backend — `services/school-core-service/`**
- Create `src/main/java/.../persistence/SchoolStructureDelta.java` — pure section-letter math (no DB). Task 1.
- Create `src/main/java/.../persistence/StructureInUseException.java` — signals a blocked shrink (→ 409). Task 2.
- Modify `src/main/java/.../persistence/SchoolStructureReadRepository.java` — add `updateStructure(...)` and `classes(Long schoolId)`. Tasks 2, 4.
- Modify `src/main/java/.../persistence/StudentReadRepository.java` — `classesForSchool` / `sectionNamesForSchool` filter `active = true`. Task 5.
- Modify `src/main/java/.../api/TenantSchoolController.java` — add `PUT /schools/{id}/structure`; make `GET /classes` school-scoped. Tasks 3, 4.
- Create `src/test/java/.../persistence/SchoolStructureDeltaTest.java` — Task 1.
- Create `src/test/java/.../persistence/SchoolStructureIntegrationTest.java` — Testcontainers; shared by Tasks 2, 4, 5.
- Create `src/test/java/.../api/SchoolStructureControllerTest.java` — standalone MockMvc; Task 3.

**Frontend — `frontend/`**
- Modify `src/pages/workspace/panels/AddStudentPanel.tsx` — fetch classes/sections instead of hardcoding. Task 6.
- Modify `src/pages/workspace/panels/SaSchoolsPanel.tsx` — Edit action → `PUT /schools/{id}/structure`. Task 7.
- Create `src/pages/workspace/panels/SchoolStructurePanel.tsx` — school-admin editor. Task 8.
- Modify `src/pages/workspace/config.ts` — new `classsetup` PanelKey, nav entry, title. Task 8.
- Modify `src/pages/UnifiedWorkspacePage.tsx` — render `SchoolStructurePanel`. Task 8.
- Create sibling `*.test.tsx` files for Tasks 6–8.

---

## Task 1: Pure section-letter math (`SchoolStructureDelta`)

**Files:**
- Create: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureDelta.java`
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureDeltaTest.java`

**Interfaces:**
- Produces:
  - `static String sectionLetter(int index)` — `0→"A"`, `1→"B"`.
  - `static int letterIndex(String name)` — 0-based index of a single-letter name; `-1` if not `A`–`Z`.
  - `static List<String> activeLetters(int sectionCount)` — `["A", …]` of length `sectionCount`.
  - `static List<String> droppedLetters(Collection<String> existingNames, int newSectionCount)` — existing letters whose index `>= newSectionCount`, deduped, ascending.

- [ ] **Step 1: Write the failing test**

```java
package com.custoking.ims.schoolcoreservice.persistence;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SchoolStructureDeltaTest {

    @Test
    void sectionLetter_mapsIndexToLetter() {
        assertThat(SchoolStructureDelta.sectionLetter(0)).isEqualTo("A");
        assertThat(SchoolStructureDelta.sectionLetter(2)).isEqualTo("C");
    }

    @Test
    void letterIndex_parsesSingleLetterCaseInsensitive() {
        assertThat(SchoolStructureDelta.letterIndex("A")).isEqualTo(0);
        assertThat(SchoolStructureDelta.letterIndex("c")).isEqualTo(2);
        assertThat(SchoolStructureDelta.letterIndex("AB")).isEqualTo(-1);
        assertThat(SchoolStructureDelta.letterIndex(null)).isEqualTo(-1);
    }

    @Test
    void activeLetters_returnsFirstNLetters() {
        assertThat(SchoolStructureDelta.activeLetters(1)).containsExactly("A");
        assertThat(SchoolStructureDelta.activeLetters(3)).containsExactly("A", "B", "C");
    }

    @Test
    void droppedLetters_returnsExistingLettersAtOrBeyondNewCount() {
        assertThat(SchoolStructureDelta.droppedLetters(List.of("A", "B", "C", "D"), 2))
                .containsExactly("C", "D");
        assertThat(SchoolStructureDelta.droppedLetters(List.of("A", "B"), 3)).isEmpty();
        assertThat(SchoolStructureDelta.droppedLetters(List.of("A", "C", "C"), 1))
                .containsExactly("C");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (from repo root, with JDK 25 on PATH):
```
./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=SchoolStructureDeltaTest test
```
Expected: FAIL — `SchoolStructureDelta` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.custoking.ims.schoolcoreservice.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Pure helpers for uniform A/B/C… section letters. No database access. */
public final class SchoolStructureDelta {

    private SchoolStructureDelta() {}

    public static String sectionLetter(int index) {
        return String.valueOf((char) ('A' + index));
    }

    public static int letterIndex(String name) {
        if (name == null || name.length() != 1) return -1;
        char c = Character.toUpperCase(name.charAt(0));
        return (c >= 'A' && c <= 'Z') ? c - 'A' : -1;
    }

    public static List<String> activeLetters(int sectionCount) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sectionCount; i++) out.add(sectionLetter(i));
        return out;
    }

    public static List<String> droppedLetters(Collection<String> existingNames, int newSectionCount) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String name : existingNames) {
            int idx = letterIndex(name);
            if (idx >= newSectionCount) out.add(sectionLetter(idx));
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(java.util.Comparator.comparingInt(SchoolStructureDelta::letterIndex));
        return sorted;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=SchoolStructureDeltaTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureDelta.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureDeltaTest.java
git commit -m "feat(school-core): pure section-letter helpers for structure edits"
```

---

## Task 2: `updateStructure` repository method + 409 exception

**Files:**
- Create: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/StructureInUseException.java`
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureReadRepository.java`
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureIntegrationTest.java`

**Interfaces:**
- Consumes: `SchoolStructureDelta.activeLetters`, `SchoolStructureDelta.droppedLetters` (Task 1); existing private `ensureSchoolSections(Long, int, int)`, `requireSchool(Long)`, `schoolDetails(Long)` in the same repository.
- Produces:
  - `class StructureInUseException extends RuntimeException` (public, package `…persistence`).
  - `Map<String, Object> updateStructure(Long schoolId, int classCount, int sectionCount)` on `SchoolStructureReadRepository` — `@Transactional`; validates the shrink, applies, returns the same map shape as `schoolDetails` (`id, name, shortCode, city, state, active, configuredClassCount, configuredSectionCount`). Throws `StructureInUseException` (→ 409 at controller) and `IllegalArgumentException("School not found")` (→ 404 at controller).
  - Static seed helpers on the test class reused by Tasks 4 & 5: `seedSchool(Connection, long id, int classCount, int sectionCount)`, `seedStudent(Connection, long schoolId, String classId, String sectionId)`.

- [ ] **Step 1: Write the failing test**

Create `SchoolStructureIntegrationTest.java`. It boots one Postgres container, migrates the `tenant_school` and `student` Flyway histories, seeds a school with 12 classes / 3 sections, and exercises grow/shrink/409 through a real `JdbcClient`-backed `SchoolStructureReadRepository`.

```java
package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchoolStructureIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;
    static SchoolStructureReadRepository repo;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        for (String schema : new String[] {"tenant_school", "student"}) {
            Flyway.configure()
                    .dataSource(PG.getJdbcUrl(), "owner", "owner")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load()
                    .migrate();
        }
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        repo = new SchoolStructureReadRepository(jdbc);
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM student.students");
            st.execute("DELETE FROM tenant_school.school_sections");
            st.execute("DELETE FROM tenant_school.school_classes");
            st.execute("DELETE FROM tenant_school.academic_years");
            st.execute("DELETE FROM tenant_school.schools");
            // 12 global classes named '1'..'12'
            for (int i = 1; i <= 12; i++) {
                st.execute("INSERT INTO tenant_school.school_classes (id, name, sort_order) VALUES " +
                        "('c" + i + "', '" + i + "', " + i + ")");
            }
            st.execute("INSERT INTO tenant_school.academic_years (id, label, active) VALUES ('ay1', '2025-26', true)");
        }
    }

    /** Seeds a school row and generates its sections via the repository's own path. */
    static long seedSchool(int classCount, int sectionCount) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.schools " +
                    "(name, short_code, city, state, active, configured_class_count, configured_section_count, created_at) " +
                    "VALUES ('Demo', 'DEMO', 'Hyd', 'TG', true, " + classCount + ", " + sectionCount + ", now()) ");
        }
        Long id = jdbc.sql("SELECT id FROM tenant_school.schools WHERE short_code = 'DEMO'")
                .query(Long.class).single();
        // Generate the initial sections to match the seeded counts.
        repo.updateStructure(id, classCount, sectionCount);
        return id;
    }

    static void seedStudent(long schoolId, String classId, String sectionId) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO student.students " +
                    "(full_name, admission_no, school_id, class_id, section_id, academic_year_id, fee_status) VALUES " +
                    "('Test Kid', 'ADM-" + java.util.UUID.randomUUID() + "', " + schoolId +
                    ", '" + classId + "', '" + sectionId + "', 'ay1', 'Pending')");
        }
    }

    @Test
    void grow_addsActiveSectionsForNewCounts() throws Exception {
        long schoolId = seedSchool(5, 2);

        Map<String, Object> result = repo.updateStructure(schoolId, 7, 4);

        assertThat(result.get("configuredClassCount")).isEqualTo(7);
        assertThat(result.get("configuredSectionCount")).isEqualTo(4);
        long activeSections = jdbc.sql(
                        "SELECT count(*) FROM tenant_school.school_sections WHERE school_id = :s AND active = true")
                .param("s", schoolId).query(Long.class).single();
        assertThat(activeSections).isEqualTo(7L * 4L);
    }

    @Test
    void shrink_whenEmpty_deactivatesDroppedSections() throws Exception {
        long schoolId = seedSchool(5, 3);

        repo.updateStructure(schoolId, 3, 2);

        long active = jdbc.sql(
                        "SELECT count(*) FROM tenant_school.school_sections WHERE school_id = :s AND active = true")
                .param("s", schoolId).query(Long.class).single();
        assertThat(active).isEqualTo(3L * 2L);
        // Dropped rows are preserved but inactive (regrow reactivates them).
        long inactive = jdbc.sql(
                        "SELECT count(*) FROM tenant_school.school_sections WHERE school_id = :s AND active = false")
                .param("s", schoolId).query(Long.class).single();
        assertThat(inactive).isGreaterThan(0L);
    }

    @Test
    void shrink_sectionsWithStudents_throwsAndDoesNotMutate() throws Exception {
        long schoolId = seedSchool(5, 3);
        seedStudent(schoolId, "c1", schoolId + "-c1-C"); // section 'C' occupied

        assertThatThrownBy(() -> repo.updateStructure(schoolId, 5, 2))
                .isInstanceOf(StructureInUseException.class)
                .hasMessageContaining("C");

        // No mutation: section count unchanged, 'C' still active.
        Integer count = jdbc.sql("SELECT configured_section_count FROM tenant_school.schools WHERE id = :s")
                .param("s", schoolId).query(Integer.class).single();
        assertThat(count).isEqualTo(3);
    }

    @Test
    void shrink_classesWithStudents_throws() throws Exception {
        long schoolId = seedSchool(12, 2);
        seedStudent(schoolId, "c8", schoolId + "-c8-A"); // class '8' occupied

        assertThatThrownBy(() -> repo.updateStructure(schoolId, 5, 2))
                .isInstanceOf(StructureInUseException.class)
                .hasMessageContaining("8");
    }

    @Test
    void reGrow_reactivatesPreviouslyDroppedSections_noDuplicates() throws Exception {
        long schoolId = seedSchool(5, 3);
        repo.updateStructure(schoolId, 5, 2);   // deactivate 'C'
        repo.updateStructure(schoolId, 5, 3);   // reactivate 'C'

        long cRows = jdbc.sql("SELECT count(*) FROM tenant_school.school_sections " +
                        "WHERE school_id = :s AND name = 'C'")
                .param("s", schoolId).query(Long.class).single();
        long cActive = jdbc.sql("SELECT count(*) FROM tenant_school.school_sections " +
                        "WHERE school_id = :s AND name = 'C' AND active = true")
                .param("s", schoolId).query(Long.class).single();
        assertThat(cRows).isEqualTo(5L);   // one 'C' per class, not duplicated
        assertThat(cActive).isEqualTo(5L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=SchoolStructureIntegrationTest test`
Expected: FAIL — `StructureInUseException` and `updateStructure` do not exist (compilation error).

- [ ] **Step 3a: Create the exception**

```java
package com.custoking.ims.schoolcoreservice.persistence;

/** Thrown when a class/section shrink would orphan students. Mapped to HTTP 409. */
public class StructureInUseException extends RuntimeException {
    public StructureInUseException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3b: Add `updateStructure` to `SchoolStructureReadRepository`**

Insert this method after `updateSchool(...)` (around line 194). It reuses the existing private `requireSchool`, `ensureSchoolSections`, and `schoolDetails`.

```java
    @Transactional
    public Map<String, Object> updateStructure(Long schoolId, int classCount, int sectionCount) {
        requireSchool(schoolId);

        // Block a class shrink that would orphan students: any student in a class
        // beyond the first `classCount` (ordered by sort_order, name).
        var offendingClass = jdbc.sql("""
                        SELECT sc.name AS name, count(*) AS n
                        FROM student.students st
                        JOIN tenant_school.school_classes sc ON sc.id = st.class_id
                        WHERE st.school_id = :schoolId AND st.deleted_at IS NULL
                          AND st.class_id IN (
                              SELECT id FROM tenant_school.school_classes
                              ORDER BY sort_order, name OFFSET :keep
                          )
                        GROUP BY sc.name, sc.sort_order
                        ORDER BY sc.sort_order
                        LIMIT 1
                        """)
                .param("schoolId", schoolId)
                .param("keep", classCount)
                .query((rs, n) -> new Object[] {rs.getString("name"), rs.getLong("n")})
                .optional();
        if (offendingClass.isPresent()) {
            Object[] o = offendingClass.get();
            throw new StructureInUseException(
                    "Cannot reduce classes to " + classCount + ": class '" + o[0] + "' has " + o[1] + " student(s)");
        }

        // Block a section shrink that would orphan students: any existing section
        // letter for this school whose index is >= sectionCount and that has students.
        List<String> existing = jdbc.sql(
                        "SELECT DISTINCT name FROM tenant_school.school_sections WHERE school_id = :schoolId")
                .param("schoolId", schoolId)
                .query(String.class)
                .list();
        List<String> dropped = SchoolStructureDelta.droppedLetters(existing, sectionCount);
        if (!dropped.isEmpty()) {
            var offendingSection = jdbc.sql("""
                            SELECT ss.name AS name, count(*) AS n
                            FROM student.students st
                            JOIN tenant_school.school_sections ss ON ss.id = st.section_id
                            WHERE st.school_id = :schoolId AND st.deleted_at IS NULL
                              AND ss.name IN (:dropped)
                            GROUP BY ss.name
                            ORDER BY ss.name
                            LIMIT 1
                            """)
                    .param("schoolId", schoolId)
                    .param("dropped", dropped)
                    .query((rs, n) -> new Object[] {rs.getString("name"), rs.getLong("n")})
                    .optional();
            if (offendingSection.isPresent()) {
                Object[] o = offendingSection.get();
                throw new StructureInUseException(
                        "Cannot reduce sections to " + sectionCount + ": section '" + o[0] + "' has " + o[1] + " student(s)");
            }
        }

        // Apply: persist counts, create any missing in-range sections, then set the
        // active flag so only in-range class/section combinations are visible.
        jdbc.sql("""
                        UPDATE tenant_school.schools
                        SET configured_class_count = :classCount, configured_section_count = :sectionCount
                        WHERE id = :schoolId
                        """)
                .param("classCount", classCount)
                .param("sectionCount", sectionCount)
                .param("schoolId", schoolId)
                .update();

        ensureSchoolSections(schoolId, classCount, sectionCount);

        List<String> inRangeClassIds = jdbc.sql("""
                        SELECT id FROM tenant_school.school_classes
                        ORDER BY sort_order, name
                        LIMIT :classCount
                        """)
                .param("classCount", classCount)
                .query(String.class)
                .list();
        List<String> activeLetters = SchoolStructureDelta.activeLetters(sectionCount);

        jdbc.sql("""
                        UPDATE tenant_school.school_sections
                        SET active = (school_class_id IN (:classIds) AND name IN (:letters))
                        WHERE school_id = :schoolId
                        """)
                .param("classIds", inRangeClassIds)
                .param("letters", activeLetters)
                .param("schoolId", schoolId)
                .update();

        return schoolDetails(schoolId);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=SchoolStructureIntegrationTest test`
Expected: PASS (5 tests). (Requires Docker; the test self-skips via `Assumptions.assumeTrue` if Docker is absent — ensure Docker is running so it actually executes.)

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/StructureInUseException.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureReadRepository.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureIntegrationTest.java
git commit -m "feat(school-core): editable class/section structure with in-use shrink guard"
```

---

## Task 3: `PUT /schools/{id}/structure` endpoint

**Files:**
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/TenantSchoolController.java`
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/api/SchoolStructureControllerTest.java`

**Interfaces:**
- Consumes: `SchoolStructureReadRepository.updateStructure(Long, int, int)` and `StructureInUseException` (Task 2); existing `TenantScope.resolveSchoolId(Long)`, `requireToken(String, String)` in the controller.
- Produces: `PUT /api/v1/schools/{id}/structure` returning the `updateStructure` map. Body `{ "classCount": int, "sectionCount": int }`. 400 on out-of-range; 403 on cross-tenant; 404 on unknown school; 409 on in-use shrink.

- [ ] **Step 1: Write the failing test**

```java
package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.SchoolRepository;
import com.custoking.ims.schoolcoreservice.persistence.SchoolStructureReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.StructureInUseException;
import com.custoking.ims.schoolcoreservice.persistence.ModuleEntitlementReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.ZoneCommandRepository;
import com.custoking.ims.schoolcoreservice.persistence.ZoneRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchoolStructureControllerTest {

    private final SchoolStructureReadRepository structure = mock(SchoolStructureReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TenantSchoolController(
                    mock(SchoolRepository.class),
                    mock(ZoneRepository.class),
                    mock(ModuleEntitlementReadRepository.class),
                    structure,
                    mock(ZoneCommandRepository.class),
                    "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void superadmin_editsAnySchool() throws Exception {
        when(structure.updateStructure(7L, 5, 2)).thenReturn(Map.of("id", 7L));
        mvc.perform(put("/api/v1/schools/7/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":5,\"sectionCount\":2}"))
                .andExpect(status().isOk());
        verify(structure).updateStructure(7L, 5, 2);
    }

    @Test
    void schoolAdmin_editingOwnSchool_isAllowed() throws Exception {
        when(structure.updateStructure(10L, 6, 3)).thenReturn(Map.of("id", 10L));
        mvc.perform(put("/api/v1/schools/10/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":6,\"sectionCount\":3}"))
                .andExpect(status().isOk());
        verify(structure).updateStructure(10L, 6, 3);
    }

    @Test
    void schoolAdmin_editingAnotherSchool_isForbidden() throws Exception {
        mvc.perform(put("/api/v1/schools/99/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":6,\"sectionCount\":3}"))
                .andExpect(status().isForbidden());
        verify(structure, never()).updateStructure(anyLong(), anyInt(), anyInt());
    }

    @Test
    void classCountOutOfRange_returns400() throws Exception {
        mvc.perform(put("/api/v1/schools/7/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":13,\"sectionCount\":2}"))
                .andExpect(status().isBadRequest());
        verify(structure, never()).updateStructure(anyLong(), anyInt(), anyInt());
    }

    @Test
    void inUseShrink_returns409() throws Exception {
        when(structure.updateStructure(7L, 2, 2))
                .thenThrow(new StructureInUseException("Cannot reduce classes to 2: class '8' has 3 student(s)"));
        mvc.perform(put("/api/v1/schools/7/structure")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classCount\":2,\"sectionCount\":2}"))
                .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=SchoolStructureControllerTest test`
Expected: FAIL — no `PUT /schools/{id}/structure` mapping (404 instead of expected statuses / compilation of endpoint helper missing).

- [ ] **Step 3: Add the endpoint + a range helper to `TenantSchoolController`**

Add the import near the other persistence imports (top of file):
```java
import com.custoking.ims.schoolcoreservice.persistence.StructureInUseException;
```

Add this endpoint after `updateSchool(...)` (after line 111):
```java
    @PutMapping("/schools/{id}/structure")
    public Map<String, Object> updateSchoolStructure(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "tenant-school:write");
        Long resolvedId = TenantScope.resolveSchoolId(id); // superadmin bypass; own-school admin; else 403
        int classCount = intInRange(body.get("classCount"), 1, 12, "classCount");
        int sectionCount = intInRange(body.get("sectionCount"), 1, 26, "sectionCount");
        try {
            return structure.updateStructure(resolvedId, classCount, sectionCount);
        } catch (StructureInUseException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null ? "Invalid request" : ex.getMessage();
            HttpStatus status = message.toLowerCase().contains("not found")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, message, ex);
        }
    }
```

Add this private helper next to the other parse helpers (near `parseLong`, around line 391):
```java
    private int intInRange(Object value, int min, int max, String field) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        int parsed;
        try {
            parsed = (value instanceof Number number) ? number.intValue()
                    : Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field + ": " + value);
        }
        if (parsed < min || parsed > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be between " + min + " and " + max);
        }
        return parsed;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=SchoolStructureControllerTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/TenantSchoolController.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/api/SchoolStructureControllerTest.java
git commit -m "feat(school-core): PUT /schools/{id}/structure endpoint (400/403/404/409)"
```

---

## Task 4: School-scope reads (`GET /classes` first-N + section endpoints active-only by default)

**Files:**
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureReadRepository.java`
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/TenantSchoolController.java`
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureIntegrationTest.java` (add methods; reuses Task 2 seeds)
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/api/SchoolStructureControllerTest.java` (add methods; created in Task 3)

**Interfaces:**
- Consumes: `seedSchool(int, int)` seed helper (Task 2); the existing `SchoolStructureReadRepository.sections(Long, String, Boolean)`.
- Produces: `List<SchoolClassRow> classes(Long schoolId)` — `schoolId == null` → full global list; otherwise first `configured_class_count` classes (falling back to the full list when the count is null). The no-arg `classes()` is removed; the controller passes the resolved scope. The three section-list endpoints (`GET /sections`, `GET /schools/{id}/sections`, `GET /classes/{classId}/sections`) treat an **omitted** `active` param as `true`, so deactivated sections disappear from every picker (FeesPanel, Attendance, AddStudent) without per-caller changes.

- [ ] **Step 1: Write the failing test** (append to `SchoolStructureIntegrationTest`)

```java
    @Test
    void classes_nullScope_returnsFullGlobalList() {
        assertThat(repo.classes(null)).hasSize(12);
    }

    @Test
    void classes_schoolScope_returnsFirstNByConfiguredCount() throws Exception {
        long schoolId = seedSchool(5, 2);
        assertThat(repo.classes(schoolId))
                .extracting(SchoolStructureReadRepository.SchoolClassRow::name)
                .containsExactly("1", "2", "3", "4", "5");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=SchoolStructureIntegrationTest test`
Expected: FAIL — `classes(Long)` does not exist (compilation error).

- [ ] **Step 3a: Replace `classes()` in `SchoolStructureReadRepository`**

Replace the existing method (lines 23–31):
```java
    public List<SchoolClassRow> classes(Long schoolId) {
        if (schoolId == null) {
            return jdbc.sql("""
                            SELECT id, name, sort_order
                            FROM tenant_school.school_classes
                            ORDER BY sort_order, name
                            """)
                    .query(SchoolClassRow.class)
                    .list();
        }
        Integer count = jdbc.sql(
                        "SELECT configured_class_count FROM tenant_school.schools WHERE id = :schoolId")
                .param("schoolId", schoolId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        int limit = (count == null) ? Integer.MAX_VALUE : count;
        return jdbc.sql("""
                        SELECT id, name, sort_order
                        FROM tenant_school.school_classes
                        ORDER BY sort_order, name
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(SchoolClassRow.class)
                .list();
    }
```

- [ ] **Step 3b: Update the controller `GET /classes` and default the section endpoints to active-only**

Replace the existing `classes` method (lines 199–204):
```java
    @GetMapping("/classes")
    public Object classes(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        // Scoped to the caller's school (first-N configured classes); superadmin with no
        // schoolId gets the full global list.
        requireToken(token, "tenant-school:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return structure.classes(scope);
    }
```

In `schoolSections` (lines 169–178), `sections` (lines 206–215), and `sectionsForClass` (lines 217–226), replace the single line `return structure.sections(...);` with a default-active variant. For example, `sections`:
```java
    @GetMapping("/sections")
    public Object sections(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) Boolean active) {
        requireToken(token, "tenant-school:read");
        schoolId = TenantScope.resolveSchoolId(schoolId);
        return structure.sections(schoolId, classId, active == null ? Boolean.TRUE : active);
    }
```
Apply the identical `active == null ? Boolean.TRUE : active` change to `schoolSections` and `sectionsForClass` (each already resolves `schoolId` via `TenantScope`).

- [ ] **Step 3c: Add controller tests for the section active-default** (append to `SchoolStructureControllerTest` from Task 3)

```java
    @Test
    void sectionsEndpoint_defaultsToActiveOnly_whenActiveOmitted() throws Exception {
        when(structure.sections(99L, null, Boolean.TRUE)).thenReturn(java.util.List.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/sections?schoolId=99")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(structure).sections(99L, null, Boolean.TRUE);
    }

    @Test
    void sectionsEndpoint_honoursExplicitActiveFalse() throws Exception {
        when(structure.sections(99L, null, Boolean.FALSE)).thenReturn(java.util.List.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/sections?schoolId=99&active=false")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(structure).sections(99L, null, Boolean.FALSE);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=SchoolStructureIntegrationTest,SchoolStructureControllerTest test`
Expected: PASS (integration 7 + controller 7). Then compile-check the whole module to confirm no other caller used the removed no-arg `classes()`:
`./mvnw.cmd -f services/school-core-service/pom.xml -DskipTests compile` → Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureReadRepository.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/TenantSchoolController.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureIntegrationTest.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/api/SchoolStructureControllerTest.java
git commit -m "feat(school-core): scope GET /classes to configured count; sections default to active-only"
```

---

## Task 5: Students-workspace filters exclude inactive sections

**Files:**
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/StudentReadRepository.java`
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureIntegrationTest.java` (add a method that constructs a `StudentReadRepository`)

**Interfaces:**
- Consumes: `seedSchool` / `seedStudent` (Task 2); `StudentPhotoStorage` (mock) for the `StudentReadRepository` constructor.
- Produces: `classesForSchool` and `sectionNamesForSchool` return only classes/sections with `active = true` rows, so `workspaceStudents(...).filters` reflects the configured counts after a shrink.

- [ ] **Step 1: Write the failing test** (append to `SchoolStructureIntegrationTest`)

```java
    @Test
    void workspaceFilters_excludeDeactivatedSectionsAfterShrink() throws Exception {
        long schoolId = seedSchool(5, 3);
        repo.updateStructure(schoolId, 3, 2); // classes 4-5 and section C deactivated

        var studentRepo = new StudentReadRepository(
                jdbc, org.mockito.Mockito.mock(
                        com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class));
        Map<String, Object> workspace =
                studentRepo.workspaceStudents(schoolId, null, null, null, 0, 500);

        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) workspace.get("filters");
        assertThat((java.util.List<String>) filters.get("sections")).containsExactly("A", "B");
        assertThat((java.util.List<String>) filters.get("classes")).containsExactly("1", "2", "3");
    }
```

> Note: confirm the `StudentReadRepository` constructor is `(JdbcClient, StudentPhotoStorage)`; if it also takes a schema `@Value`, pass `"student"` as the third argument to match `application.yml`'s `student.db.schema` default.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=SchoolStructureIntegrationTest test`
Expected: FAIL — `classes` filter still contains `"4"`/`"5"` and `sections` still contains `"C"` (inactive rows not excluded).

- [ ] **Step 3: Add `active = true` to both helper queries in `StudentReadRepository`**

`classesForSchool` (around line 1374) — add the active predicate:
```java
    private List<String> classesForSchool(Long schoolId) {
        return jdbc.sql("""
                SELECT DISTINCT sc.name
                FROM tenant_school.school_sections ss
                JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                WHERE ss.school_id = :schoolId
                  AND ss.active = true
                ORDER BY sc.name
                """)
                .param("schoolId", schoolId)
                .query(String.class)
                .list();
    }
```

`sectionNamesForSchool` (around line 1387) — add the active predicate:
```java
    private List<String> sectionNamesForSchool(Long schoolId) {
        return jdbc.sql("""
                SELECT DISTINCT name
                FROM tenant_school.school_sections
                WHERE school_id = :schoolId
                  AND active = true
                ORDER BY name
                """)
                .param("schoolId", schoolId)
                .query(String.class)
                .list();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=SchoolStructureIntegrationTest test`
Expected: PASS (8 tests). Then run the full module suite to confirm no regression:
`./mvnw.cmd -f services/school-core-service/pom.xml test` → Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/StudentReadRepository.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureIntegrationTest.java
git commit -m "feat(school-core): students filters show only active classes/sections"
```

---

## Task 6: De-hardcode AddStudentPanel class/section dropdowns

**Files:**
- Modify: `frontend/src/pages/workspace/panels/AddStudentPanel.tsx`
- Test: `frontend/src/pages/workspace/panels/AddStudentPanel.test.tsx` (create)

**Interfaces:**
- Consumes: `GET /classes` (returns `[{ id, name, sortOrder }]`, now school-scoped — Task 4) and `GET /classes/{classId}/sections?active=true` (returns `[{ id, name, ... }]`).
- Produces: the class/section `<select>`s are populated from the API; the submitted `gradeLevel` is the selected class `name` and `sectionName` is the selected section `name`. `createStudent` resolves the class by name (`StudentReadRepository.classByName`), so submitting the API's class name stays compatible.

The panel currently takes `Props { setPanel, onRefresh }` and holds `studentForm` with `gradeLevel` / `sectionName`. We add state for fetched `classes` and `sections`, load classes on mount, and load sections whenever the selected class changes.

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AddStudentPanel } from './AddStudentPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

describe('AddStudentPanel class/section dropdowns', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/classes') {
        return Promise.resolve({ data: [
          { id: 'c1', name: 'Class 1', sortOrder: 1 },
          { id: 'c2', name: 'Class 2', sortOrder: 2 },
        ] });
      }
      if (url === '/classes/c1/sections') {
        return Promise.resolve({ data: [{ id: 's-a', name: 'A' }, { id: 's-b', name: 'B' }] });
      }
      return Promise.resolve({ data: [] });
    });
  });

  it('renders class options fetched from the API, not a hardcoded 1-12 list', async () => {
    render(<AddStudentPanel setPanel={vi.fn()} onRefresh={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('option', { name: 'Class 1' })).toBeInTheDocument());
    expect(screen.getByRole('option', { name: 'Class 2' })).toBeInTheDocument();
    // The old hardcoded list went up to Class 12 — it must be gone.
    expect(screen.queryByRole('option', { name: 'Class 12' })).not.toBeInTheDocument();
  });

  it('loads sections for the selected class with active=true', async () => {
    render(<AddStudentPanel setPanel={vi.fn()} onRefresh={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('option', { name: 'Class 1' })).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText(/Class \*/i), { target: { value: 'Class 1' } });
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/classes/c1/sections', { params: { active: true } }));
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `frontend/`): `npm test -- AddStudentPanel`
Expected: FAIL — the current panel renders hardcoded `Class 12` and never calls `/classes`.

- [ ] **Step 3: Implement the fetch-driven dropdowns**

In `AddStudentPanel.tsx`, add `useEffect` to the imports:
```tsx
import { DragEvent, useEffect, useRef, useState } from 'react';
```

Add class/section state and loaders inside the component, after the existing `useState` declarations (after line 29):
```tsx
  const [classes, setClasses] = useState<Array<{ id: string; name: string }>>([]);
  const [sections, setSections] = useState<Array<{ id: string; name: string }>>([]);

  useEffect(() => {
    let alive = true;
    void api.get<Array<{ id: string; name: string }>>('/classes')
      .then((res) => { if (alive) setClasses(Array.isArray(res.data) ? res.data : []); })
      .catch(() => { if (alive) setClasses([]); });
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    const selected = classes.find((c) => c.name === studentForm.gradeLevel);
    if (!selected) { setSections([]); return; }
    let alive = true;
    void api.get<Array<{ id: string; name: string }>>(`/classes/${selected.id}/sections`, { params: { active: true } })
      .then((res) => { if (alive) setSections(Array.isArray(res.data) ? res.data : []); })
      .catch(() => { if (alive) setSections([]); });
    return () => { alive = false; };
  }, [classes, studentForm.gradeLevel]);
```

Replace the hardcoded Class `<select>` (line 150) with:
```tsx
              <Field label="Class *"><select value={studentForm.gradeLevel} onChange={(e) => setStudentForm({ ...studentForm, gradeLevel: e.target.value, sectionName: '' })}>{classes.map((c) => <option key={c.id} value={c.name}>{c.name}</option>)}</select></Field>
```

Replace the hardcoded Section `<select>` (line 151) with:
```tsx
              <Field label="Section"><select value={studentForm.sectionName} onChange={(e) => setStudentForm({ ...studentForm, sectionName: e.target.value })}>{sections.map((s) => <option key={s.id} value={s.name}>{s.name}</option>)}</select></Field>
```

> The `Field` component renders a `<label>` wrapping its child; `getByLabelText(/Class \*/i)` targets the class select. If `Field` does not associate the label with the control, the test's `getByLabelText` will fail — in that case switch the test to `screen.getByRole('combobox')` ordering (first combobox = Class) rather than changing `Field`.

- [ ] **Step 4: Run test to verify it passes**

Run (from `frontend/`): `npm test -- AddStudentPanel`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/AddStudentPanel.tsx \
        frontend/src/pages/workspace/panels/AddStudentPanel.test.tsx
git commit -m "fix(fe): AddStudent class/section dropdowns reflect the school's configured counts"
```

---

## Task 7: Superadmin edit action in SaSchoolsPanel

**Files:**
- Modify: `frontend/src/pages/workspace/panels/SaSchoolsPanel.tsx`
- Test: `frontend/src/pages/workspace/panels/SaSchoolsPanel.test.tsx` (create)

**Interfaces:**
- Consumes: `PUT /schools/{id}/structure` (Task 3); `GET /sa/schools` already loads rows with `id`, `configuredClassCount`, `configuredSectionCount`.
- Produces: an **Edit** button per school row opening a modal with class/section number inputs; submit calls `PUT /schools/{id}/structure`; a **409** response message is shown inline (no fake success).

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { SaSchoolsPanel } from './SaSchoolsPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

describe('SaSchoolsPanel structure edit', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockResolvedValue({ data: [
      { id: 7, name: 'Demo School', shortCode: 'DEMO', city: 'Hyd', active: true,
        configuredClassCount: 12, configuredSectionCount: 3, adminEmail: 'a@x.com', ordersYTD: 0, gmvYTD: 0 },
    ] });
    vi.mocked(api.put).mockReset();
    vi.mocked(api.post).mockReset();
  });

  it('surfaces a 409 in-use message and does not close on failure', async () => {
    vi.mocked(api.put).mockRejectedValue({ response: { data: { message: "class '8' has 3 student(s)" } } });
    render(<SaSchoolsPanel />);
    await waitFor(() => expect(screen.getByText('Demo School')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /edit structure/i }));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/No\. of classes/i), { target: { value: '2' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /save/i }));
    await waitFor(() =>
      expect(within(dialog).getByText(/has 3 student\(s\)/i)).toBeInTheDocument());
    expect(api.put).toHaveBeenCalledWith('/schools/7/structure', { classCount: 2, sectionCount: 3 });
  });

  it('saves valid counts and reloads', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: { id: 7 } });
    render(<SaSchoolsPanel />);
    await waitFor(() => expect(screen.getByText('Demo School')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /edit structure/i }));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Sections per class/i), { target: { value: '4' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /save/i }));
    await waitFor(() =>
      expect(api.put).toHaveBeenCalledWith('/schools/7/structure', { classCount: 12, sectionCount: 4 }));
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `frontend/`): `npm test -- SaSchoolsPanel`
Expected: FAIL — no "Edit structure" button / no edit modal exists.

- [ ] **Step 3: Add the edit modal + action**

Add edit state after the existing `useState` declarations (after line 14):
```tsx
  const [editSchool, setEditSchool] = useState<any | null>(null);
  const [editForm, setEditForm] = useState({ classCount: '12', sectionCount: '2' });
  const [editError, setEditError] = useState('');
  const [editSaving, setEditSaving] = useState(false);

  const openEdit = (school: any) => {
    setEditSchool(school);
    setEditForm({
      classCount: String(school.configuredClassCount ?? 12),
      sectionCount: String(school.configuredSectionCount ?? 2),
    });
    setEditError('');
  };

  const submitEdit = async () => {
    const classCount = Number(editForm.classCount || 0);
    const sectionCount = Number(editForm.sectionCount || 0);
    if (!Number.isInteger(classCount) || classCount < 1 || classCount > 12) { setEditError('Classes must be between 1 and 12'); return; }
    if (!Number.isInteger(sectionCount) || sectionCount < 1 || sectionCount > 26) { setEditError('Sections must be between 1 and 26'); return; }
    setEditError(''); setEditSaving(true);
    try {
      await api.put(`/schools/${editSchool.id}/structure`, { classCount, sectionCount });
      setToast(`${editSchool.name} structure updated`);
      setEditSchool(null);
      await loadSaSchools();
    } catch (e: any) {
      setEditError(e?.response?.data?.message || 'Update failed. Please try again.');
    } finally {
      setEditSaving(false);
    }
  };
```

Add an actions cell to the table. Change the header row (line 80) to append a column:
```tsx
            <thead><tr><th>School</th><th>Short code</th><th>City</th><th>Classes</th><th>Sections / class</th><th>Admin</th><th>Orders YTD</th><th>Order Value YTD</th><th>ERP since</th><th></th></tr></thead>
```
Append an actions cell to each row, immediately before `</tr>` (after line 94):
```tsx
                    <td><button className="ck-btn ck-btn-ghost" onClick={() => openEdit(school)}>Edit structure</button></td>
```
Update the empty-state `colSpan` (line 83) from `9` to `10`.

Add the modal just before the `{toast && (` block (before line 136):
```tsx
      {editSchool && (
        <div className="ck-modal-bg" onClick={() => setEditSchool(null)}>
          <div className="ck-modal" role="dialog" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Edit structure — {editSchool.name}</div>
              <button className="ck-modal-x" onClick={() => setEditSchool(null)}>×</button>
            </div>
            <div className="ck-modal-body">
              {editError && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{editError}</div></div>}
              <div className="ck-form-grid ck-fg-2">
                <Field label="No. of classes *"><input type="number" min={1} max={12} value={editForm.classCount} onChange={(e) => setEditForm({ ...editForm, classCount: e.target.value })} /></Field>
                <Field label="Sections per class *"><input type="number" min={1} max={26} value={editForm.sectionCount} onChange={(e) => setEditForm({ ...editForm, sectionCount: e.target.value })} /></Field>
              </div>
              <div className="ts" style={{ marginTop: 10 }}>Reducing a count is blocked if a removed class or section still has students.</div>
            </div>
            <div className="ck-modal-foot">
              <button className="ck-btn ck-btn-ghost" onClick={() => setEditSchool(null)}>Cancel</button>
              <button className="ck-btn ck-btn-g" disabled={editSaving} onClick={submitEdit}>{editSaving ? 'Saving…' : 'Save changes'}</button>
            </div>
          </div>
        </div>
      )}
```

- [ ] **Step 4: Run test to verify it passes**

Run (from `frontend/`): `npm test -- SaSchoolsPanel`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/SaSchoolsPanel.tsx \
        frontend/src/pages/workspace/panels/SaSchoolsPanel.test.tsx
git commit -m "feat(fe): superadmin can edit a school's class/section structure"
```

---

## Task 8: School-admin structure editor panel

**Files:**
- Create: `frontend/src/pages/workspace/panels/SchoolStructurePanel.tsx`
- Modify: `frontend/src/pages/workspace/config.ts`
- Modify: `frontend/src/pages/UnifiedWorkspacePage.tsx`
- Test: `frontend/src/pages/workspace/panels/SchoolStructurePanel.test.tsx` (create)

**Interfaces:**
- Consumes: `GET /schools/{id}` (returns `configuredClassCount` / `configuredSectionCount`) and `PUT /schools/{id}/structure` (Task 3). The panel receives the admin's school id via a `schoolId` prop (from `schoolScopedParams.schoolId`, i.e. `user.branchId`).
- Produces: `SchoolStructurePanel({ schoolId, onSaved }: { schoolId?: number; onSaved: () => void })`; a new `PanelKey` `'classsetup'` wired into `ADMIN_NAV_SECTIONS` "School ERP" and `PANEL_TITLES`.

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { SchoolStructurePanel } from './SchoolStructurePanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

describe('SchoolStructurePanel', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockResolvedValue({ data: { id: 10, configuredClassCount: 8, configuredSectionCount: 3 } });
    vi.mocked(api.put).mockReset();
  });

  it('loads current counts and submits an update for the admin school', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: { id: 10 } });
    render(<SchoolStructurePanel schoolId={10} onSaved={vi.fn()} />);
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/schools/10'));
    await waitFor(() => expect((screen.getByLabelText(/No\. of classes/i) as HTMLInputElement).value).toBe('8'));
    fireEvent.change(screen.getByLabelText(/No\. of classes/i), { target: { value: '9' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));
    await waitFor(() =>
      expect(api.put).toHaveBeenCalledWith('/schools/10/structure', { classCount: 9, sectionCount: 3 }));
  });

  it('shows a 409 in-use message without clearing the form', async () => {
    vi.mocked(api.put).mockRejectedValue({ response: { data: { message: "section 'C' has 2 student(s)" } } });
    render(<SchoolStructurePanel schoolId={10} onSaved={vi.fn()} />);
    await waitFor(() => expect((screen.getByLabelText(/Sections per class/i) as HTMLInputElement).value).toBe('3'));
    fireEvent.change(screen.getByLabelText(/Sections per class/i), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));
    await waitFor(() => expect(screen.getByText(/has 2 student\(s\)/i)).toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `frontend/`): `npm test -- SchoolStructurePanel`
Expected: FAIL — `SchoolStructurePanel` does not exist.

- [ ] **Step 3a: Create the panel**

```tsx
import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';

export function SchoolStructurePanel({ schoolId, onSaved }: { schoolId?: number; onSaved: () => void }) {
  const [form, setForm] = useState({ classCount: '', sectionCount: '' });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  useEffect(() => {
    if (!schoolId) { setLoading(false); return; }
    let alive = true;
    setLoading(true);
    void api.get<{ configuredClassCount?: number; configuredSectionCount?: number }>(`/schools/${schoolId}`)
      .then((res) => {
        if (!alive) return;
        setForm({
          classCount: String(res.data.configuredClassCount ?? 12),
          sectionCount: String(res.data.configuredSectionCount ?? 2),
        });
      })
      .catch(() => { if (alive) setError('Unable to load current structure.'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [schoolId]);

  const save = async () => {
    if (!schoolId) return;
    const classCount = Number(form.classCount || 0);
    const sectionCount = Number(form.sectionCount || 0);
    if (!Number.isInteger(classCount) || classCount < 1 || classCount > 12) { setError('Classes must be between 1 and 12'); return; }
    if (!Number.isInteger(sectionCount) || sectionCount < 1 || sectionCount > 26) { setError('Sections must be between 1 and 26'); return; }
    setError(''); setOk(''); setSaving(true);
    try {
      await api.put(`/schools/${schoolId}/structure`, { classCount, sectionCount });
      setOk('Structure updated.');
      onSaved();
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Update failed. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <ModuleShell title="Class & section setup" subtitle="Set how many classes and sections per class your school uses">
      <div className="ck-form-card">
        <div className="ck-form-body">
          {!schoolId ? <div className="ck-alert ck-alert-re"><span>!</span><div>No school is associated with your account.</div></div>
          : loading ? <div style={{ padding: 16 }}>Loading…</div>
          : <>
            {error && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{error}</div></div>}
            {ok && <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}><span>✓</span><div>{ok}</div></div>}
            <div className="ck-form-grid ck-fg-2">
              <Field label="No. of classes *"><input type="number" min={1} max={12} value={form.classCount} onChange={(e) => setForm({ ...form, classCount: e.target.value })} /></Field>
              <Field label="Sections per class *"><input type="number" min={1} max={26} value={form.sectionCount} onChange={(e) => setForm({ ...form, sectionCount: e.target.value })} /></Field>
            </div>
            <div className="ts" style={{ marginTop: 10 }}>Reducing a count is blocked if a removed class or section still has students.</div>
            <div className="ck-actions-inline" style={{ marginTop: 16 }}>
              <button className="ck-btn ck-btn-g" disabled={saving} onClick={() => void save()}>{saving ? 'Saving…' : 'Save changes'}</button>
            </div>
          </>}
        </div>
      </div>
    </ModuleShell>
  );
}
```

- [ ] **Step 3b: Wire the panel key + nav + title in `config.ts`**

Add `'classsetup'` to the `PanelKey` union (line 21-27), e.g. append to the School ERP-related keys:
```ts
  | 'addstudent' | 'bulkimport' | 'staff' | 'catalog' | 'orders' | 'planning' | 'classsetup'
```
Add a nav item to `ADMIN_NAV_SECTIONS` "School ERP" section items (after the `staff` entry, line 63):
```ts
      { key: 'classsetup', label: 'Class & section setup', icon: '🏫' },
```
Add to `PANEL_TITLES` (after the `staff` entry, line 211):
```ts
  classsetup: 'Class & section setup',
```

- [ ] **Step 3c: Render the panel in `UnifiedWorkspacePage.tsx`**

Add the import alongside the other panel imports:
```tsx
import { SchoolStructurePanel } from './workspace/panels/SchoolStructurePanel';
```
Add a render branch next to the other admin panels (near the `addstudent` branch, line 381):
```tsx
          {panel === 'classsetup' && <SchoolStructurePanel schoolId={user?.branchId} onSaved={refresh} />}
```

> Confirm the logged-in school id is `user?.branchId` (the same value `schoolScopedParams` uses at line 72). If the field differs, use whatever `schoolScopedParams.schoolId` resolves to.

- [ ] **Step 4: Run test + typecheck**

Run (from `frontend/`): `npm test -- SchoolStructurePanel`
Expected: PASS (2 tests).
Then: `npm run build`
Expected: BUILD SUCCESS (TypeScript compiles; new PanelKey is exhaustive in `PANEL_TITLES`).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/SchoolStructurePanel.tsx \
        frontend/src/pages/workspace/panels/SchoolStructurePanel.test.tsx \
        frontend/src/pages/workspace/config.ts \
        frontend/src/pages/UnifiedWorkspacePage.tsx
git commit -m "feat(fe): school-admin panel to edit own class/section structure"
```

---

## Final verification (after all tasks)

- [ ] Backend suite: `./mvnw.cmd -f services/school-core-service/pom.xml test` → BUILD SUCCESS (Docker running so the integration test executes, not skips).
- [ ] Frontend suite: from `frontend/`, `npm test` then `npm run build` → both green.
- [ ] Manual dev check after deploy: onboard/select a school, lower its section count with an occupied section → 409 message shown; raise counts → new sections appear in AddStudent and Students filters; a 5-class school shows only Class 1–5 in AddStudent and Fees pickers.

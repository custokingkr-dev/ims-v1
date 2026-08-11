package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
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
        repo = new SchoolStructureReadRepository(jdbc, new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));
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
            st.execute("INSERT INTO tenant_school.school_classes (id, name, sort_order) VALUES " +
                    "('pre-primary', 'Nursery / Pre-Nursery / Playgroup', 1), " +
                    "('lkg', 'LKG (Lower Kindergarten)', 2), " +
                    "('ukg', 'UKG (Upper Kindergarten)', 3)");
            for (int i = 1; i <= 12; i++) {
                st.execute("INSERT INTO tenant_school.school_classes (id, name, sort_order) VALUES " +
                        "('c" + i + "', '" + i + "', " + (i + 3) + ")");
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
        long schoolId = seedSchool(15, 2);
        seedStudent(schoolId, "c8", schoolId + "-c8-A"); // class '8' occupied

        assertThatThrownBy(() -> repo.updateStructure(schoolId, 7, 2))
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

    @Test
    void edit_blockedForNonLetterSectionWithStudents() throws Exception {
        long schoolId = seedSchool(5, 3);
        // A non-A..Z section (e.g. created via import) that holds a student.
        try (java.sql.Connection c = dataSource.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.school_sections (id, school_id, school_class_id, name, teacher_name, active) VALUES " +
                    "('" + schoolId + "-c1-SR', " + schoolId + ", 'c1', 'SR', '', true)");
        }
        seedStudent(schoolId, "c1", schoolId + "-c1-SR");

        // Even a no-op-looking edit (same counts) would deactivate 'SR' -> must be blocked.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repo.updateStructure(schoolId, 5, 3))
                .isInstanceOf(StructureInUseException.class)
                .hasMessageContaining("SR");
    }

    @Test
    void classes_nullScope_returnsFullGlobalList() {
        assertThat(repo.classes(null))
                .extracting(SchoolStructureReadRepository.SchoolClassRow::name)
                .containsExactly(
                        "Nursery / Pre-Nursery / Playgroup",
                        "LKG (Lower Kindergarten)",
                        "UKG (Upper Kindergarten)",
                        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    }

    @Test
    void classes_schoolScope_returnsFirstNByConfiguredCount() throws Exception {
        long schoolId = seedSchool(6, 2);
        assertThat(repo.classes(schoolId))
                .extracting(SchoolStructureReadRepository.SchoolClassRow::name)
                .containsExactly(
                        "Nursery / Pre-Nursery / Playgroup",
                        "LKG (Lower Kindergarten)",
                        "UKG (Upper Kindergarten)",
                        "1", "2", "3");
    }

    @Test
    void classes_schoolScope_alsoIncludesOutOfRangeClassesThatHaveStudents() throws Exception {
        // School configured for 1 class, but a student was onboarded into class 8
        // (its section auto-created). Class 8 must NOT be hidden from the pickers.
        long schoolId = seedSchool(1, 1);
        // class 8 section must exist for the FK before seeding the student.
        try (java.sql.Connection c = dataSource.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.school_sections (id, school_id, school_class_id, name, teacher_name, active) VALUES " +
                    "('" + schoolId + "-c8-A', " + schoolId + ", 'c8', 'A', '', true)");
        }
        seedStudent(schoolId, "c8", schoolId + "-c8-A");

        assertThat(repo.classes(schoolId))
                .extracting(SchoolStructureReadRepository.SchoolClassRow::name)
                .containsExactly("Nursery / Pre-Nursery / Playgroup", "8");
    }

    @Test
    void academicYears_schoolScopeDeduplicatesLegacyCurrentYearAndMarksCanonicalCurrentActive() throws Exception {
        long schoolId = seedSchool(3, 1);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM tenant_school.academic_years");
            st.execute("INSERT INTO tenant_school.academic_years (id, label, active) VALUES " +
                    "('2026_27', '2026-2027', true), " +
                    "('ay_2025_26', '2025-26', false)");
        }

        List<SchoolStructureReadRepository.AcademicYearRow> years = repo.academicYears(schoolId, null);

        assertThat(years)
                .extracting(SchoolStructureReadRepository.AcademicYearRow::id)
                .contains("ay_2026_27", "ay_2025_26")
                .doesNotContain("2026_27");
        assertThat(years.stream().filter(row -> Boolean.TRUE.equals(row.active())).toList())
                .singleElement()
                .satisfies(row -> assertThat(row.id()).isEqualTo("ay_2026_27"));
        assertThat(repo.academicYears(schoolId, true))
                .extracting(SchoolStructureReadRepository.AcademicYearRow::id)
                .containsExactly("ay_2026_27");
    }

    @Test
    void workspaceFilters_excludeDeactivatedSectionsAfterShrink() throws Exception {
        long schoolId = seedSchool(8, 3);
        repo.updateStructure(schoolId, 6, 2); // classes 4-5 and section C deactivated

        var studentRepo = new StudentReadRepository(
                jdbc, org.mockito.Mockito.mock(
                        com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class),
                new com.custoking.ims.schoolcoreservice.outbox.OutboxWriter(
                        jdbc, new tools.jackson.databind.ObjectMapper(), "tenant_school"));
        Map<String, Object> workspace =
                studentRepo.workspaceStudents(schoolId, null, null, null, 0, 500);

        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) workspace.get("filters");
        assertThat((java.util.List<String>) filters.get("sections")).isEmpty();
        assertThat((java.util.List<String>) filters.get("classes")).containsExactly(
                "Nursery / Pre-Nursery / Playgroup",
                "LKG (Lower Kindergarten)",
                "UKG (Upper Kindergarten)",
                "1", "2", "3");

        Map<String, Object> classWorkspace =
                studentRepo.workspaceStudents(schoolId, "1", null, null, 0, 500);
        @SuppressWarnings("unchecked")
        Map<String, Object> classFilters = (Map<String, Object>) classWorkspace.get("filters");
        assertThat((java.util.List<String>) classFilters.get("sections")).containsExactly("A", "B");
    }

    @Test
    void studentDirectoryMigrations_addIndexesForActiveOrderingSectionCountsAndReviewLookup() {
        Map<String, String> indexes = jdbc.sql("""
                        SELECT indexname, indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'student'
                          AND indexname IN (
                              'idx_student_students_active_school_name',
                              'idx_student_students_active_school_section',
                              'idx_student_review_items_school_student_latest'
                          )
                        """)
                .query((rs, rowNum) -> Map.entry(
                        rs.getString("indexname"), rs.getString("indexdef").toLowerCase()))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(indexes).containsKeys(
                "idx_student_students_active_school_name",
                "idx_student_students_active_school_section",
                "idx_student_review_items_school_student_latest");
        assertThat(indexes.get("idx_student_students_active_school_name"))
                .contains("school_id", "lower((full_name)::text)", "deleted_at is null");
        assertThat(indexes.get("idx_student_students_active_school_section"))
                .contains("school_id", "section_id", "deleted_at is null");
        assertThat(indexes.get("idx_student_review_items_school_student_latest"))
                .contains("school_id", "student_id", "updated_at desc", "include (campaign_id, status)");
    }

    @Test
    void workspaceStudentStats_countRowsAndGloballyUniqueSectionsWithoutFilters() throws Exception {
        long schoolId = seedSchool(2, 2);
        seedStudent(schoolId, "pre-primary", schoolId + "-pre-primary-A");
        seedStudent(schoolId, "pre-primary", schoolId + "-pre-primary-A");
        seedStudent(schoolId, "lkg", schoolId + "-lkg-B");
        var studentRepo = new StudentReadRepository(
                jdbc, org.mockito.Mockito.mock(
                        com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class),
                new OutboxWriter(jdbc, new tools.jackson.databind.ObjectMapper(), "tenant_school"));

        Map<String, Object> unfiltered =
                studentRepo.workspaceStudents(schoolId, null, null, null, 0, 50);
        Map<String, Object> classFiltered =
                studentRepo.workspaceStudents(
                        schoolId, "Nursery / Pre-Nursery / Playgroup", null, null, 0, 50);

        assertThat(unfiltered)
                .containsEntry("totalItems", 3L)
                .containsEntry("filteredCount", 3L)
                .containsEntry("filteredSections", 2L);
        assertThat(classFiltered)
                .containsEntry("totalItems", 2L)
                .containsEntry("filteredSections", 1L);
    }
}

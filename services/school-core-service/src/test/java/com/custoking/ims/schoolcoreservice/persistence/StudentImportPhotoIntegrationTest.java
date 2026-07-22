package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentImportPhotoIntegrationTest {

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

    static String schoolUid(long schoolId) {
        return jdbc.sql("SELECT school_uid::text FROM tenant_school.schools WHERE id = :schoolId")
                .param("schoolId", schoolId)
                .query(String.class)
                .single();
    }

    @Test
    void previewImport_storesOriginalFileUnderSchoolUidFolder() throws Exception {
        long schoolId = seedSchool(5, 2);
        String schoolUid = schoolUid(schoolId);
        byte[] original = "xlsx-bytes".getBytes(StandardCharsets.UTF_8);
        StudentPhotoStorage photoStorage = mock(StudentPhotoStorage.class);
        when(photoStorage.uploadImportFile(
                eq(schoolUid),
                anyString(),
                same(original),
                eq("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                eq("students.xlsx")))
                .thenAnswer(invocation -> "schools/" + invocation.getArgument(0, String.class)
                        + "/student-imports/" + invocation.getArgument(1, String.class) + "/evidence.xlsx");
        StudentReadRepository studentRepo = new StudentReadRepository(
                jdbc, photoStorage, new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));

        Map<String, Object> preview = studentRepo.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", List.of(Map.of(
                        "Name", "Imp Evidence", "Class", "1", "Section", "A",
                        "AdmissionNo", "IMP-EVIDENCE", "Gender", "Female",
                        "Phone", "9876543210")),
                "originalFileBytes", original,
                "originalFileName", "students.xlsx",
                "originalFileContentType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        String batchId = String.valueOf(preview.get("batchId"));

        verify(photoStorage).uploadImportFile(
                eq(schoolUid),
                eq(batchId),
                same(original),
                eq("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                eq("students.xlsx"));
        String objectPath = jdbc.sql("""
                        SELECT original_file_object_path
                        FROM student.import_batches
                        WHERE id = :batchId
                        """)
                .param("batchId", batchId)
                .query(String.class)
                .single();
        assertThat(preview.get("originalFileStored")).isEqualTo(true);
        assertThat(objectPath).startsWith("schools/" + schoolUid + "/student-imports/" + batchId + "/");
    }

    @Test
    void attachPhoto_storesPhotoUnderSchoolUidFolder() throws Exception {
        long schoolId = seedSchool(5, 2);
        String schoolUid = schoolUid(schoolId);
        Long studentId = jdbc.sql("""
                        INSERT INTO student.students
                            (admission_no, roll_no, full_name, gender, father_name, father_contact,
                             phone, fee_status, attendance_percent, created_at, updated_at,
                             school_id, class_id, section_id, academic_year_id)
                        VALUES
                            ('PHOTO-1', '1', 'Photo Student', 'Female', '', '',
                             '9876543210', 'Pending', 0, now(), now(),
                             :schoolId, 'c1', :sectionId, 'ay1')
                        RETURNING id
                        """)
                .param("schoolId", schoolId)
                .param("sectionId", schoolId + "-c1-A")
                .query(Long.class)
                .single();
        byte[] photo = "photo-bytes".getBytes(StandardCharsets.UTF_8);
        String key = "schools/" + schoolUid + "/students/" + studentId + "/photos/photo.jpg";
        StudentPhotoStorage photoStorage = mock(StudentPhotoStorage.class);
        when(photoStorage.upload(eq(schoolUid), eq(studentId), same(photo), eq("image/jpeg"))).thenReturn(key);
        StudentReadRepository studentRepo = new StudentReadRepository(
                jdbc, photoStorage, new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));

        studentRepo.attachPhoto(studentId, photo, "image/jpeg");

        verify(photoStorage).upload(eq(schoolUid), eq(studentId), same(photo), eq("image/jpeg"));
        String stored = jdbc.sql("SELECT photo_url FROM student.students WHERE id = :studentId")
                .param("studentId", studentId)
                .query(String.class)
                .single();
        assertThat(stored).isEqualTo(key);
    }

    @Test
    void confirmImport_returnsAdmissionNoToStudentIdMap() throws Exception {
        long schoolId = seedSchool(5, 2); // reuse the helper pattern from SchoolStructureIntegrationTest
        StudentReadRepository repo = new StudentReadRepository(jdbc,
                org.mockito.Mockito.mock(com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class),
                new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));

        Map<String, Object> preview = repo.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", java.util.List.of(Map.of(
                        "Name", "Imp One", "Class", "1", "Section", "A", "AdmissionNo", "IMP-1", "Gender", "Male",
                        "Phone", "9876543210"))));
        String fileToken = (String) preview.get("fileToken");

        Map<String, Object> confirm = repo.confirmImport(Map.of("schoolId", schoolId, "fileToken", fileToken));

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> inserted =
                (java.util.List<Map<String, Object>>) confirm.get("insertedStudents");
        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).get("admissionNo")).isEqualTo("IMP-1");
        assertThat(((Number) inserted.get(0).get("studentId")).longValue()).isPositive();
    }

    @Test
    void confirmImport_toleratesDuplicateSectionRows() throws Exception {
        long schoolId = seedSchool(5, 2);
        jdbc.sql("""
                        INSERT INTO tenant_school.school_sections
                            (id, name, teacher_name, active, school_class_id, school_id)
                        VALUES (:id, 'A', '', true, 'c1', :schoolId)
                        """)
                .param("id", schoolId + "-c1-A-duplicate")
                .param("schoolId", schoolId)
                .update();
        StudentReadRepository repo = new StudentReadRepository(jdbc,
                org.mockito.Mockito.mock(com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class),
                new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));

        Map<String, Object> preview = repo.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", java.util.List.of(Map.of(
                        "Name", "Imp Duplicate Section", "Class", "1", "Section", "A",
                        "AdmissionNo", "IMP-DUP-SECTION", "Gender", "Male",
                        "Phone", "9876543210"))));
        String fileToken = (String) preview.get("fileToken");

        Map<String, Object> confirm = repo.confirmImport(Map.of("schoolId", schoolId, "fileToken", fileToken));

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> inserted =
                (java.util.List<Map<String, Object>>) confirm.get("insertedStudents");
        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).get("admissionNo")).isEqualTo("IMP-DUP-SECTION");
    }

    @Test
    void confirmImport_acceptsClassLabelWithPrefix() throws Exception {
        long schoolId = seedSchool(7, 2);
        StudentReadRepository repo = new StudentReadRepository(jdbc,
                org.mockito.Mockito.mock(com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class),
                new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));

        Map<String, Object> preview = repo.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", java.util.List.of(Map.of(
                        "Name", "Imp Class Label", "Class", "Class 4", "Section", "A",
                        "AdmissionNo", "IMP-CLASS-LABEL", "Gender", "Female",
                        "Phone", "9876543210"))));

        assertThat(preview.get("validCount")).isEqualTo(1);
        String fileToken = (String) preview.get("fileToken");

        Map<String, Object> confirm = repo.confirmImport(Map.of("schoolId", schoolId, "fileToken", fileToken));

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> inserted =
                (java.util.List<Map<String, Object>>) confirm.get("insertedStudents");
        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).get("admissionNo")).isEqualTo("IMP-CLASS-LABEL");
    }

    @Test
    void confirmImport_acceptsPrePrimaryAlias() throws Exception {
        long schoolId = seedSchool(3, 1);
        StudentReadRepository repo = new StudentReadRepository(jdbc,
                org.mockito.Mockito.mock(com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class),
                new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));

        Map<String, Object> preview = repo.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", java.util.List.of(Map.of(
                        "Name", "Imp Nursery", "Class", "Nursery", "Section", "A",
                        "AdmissionNo", "IMP-NURSERY", "Gender", "Female",
                        "Phone", "9876543210"))));

        assertThat(preview.get("validCount")).isEqualTo(1);
        String fileToken = (String) preview.get("fileToken");

        Map<String, Object> confirm = repo.confirmImport(Map.of("schoolId", schoolId, "fileToken", fileToken));

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> inserted =
                (java.util.List<Map<String, Object>>) confirm.get("insertedStudents");
        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).get("admissionNo")).isEqualTo("IMP-NURSERY");
    }

    @Test
    void previewImport_marksInactiveSectionAsSetupUpdateNeeded() throws Exception {
        long schoolId = seedSchool(4, 2);
        repo.updateStructure(schoolId, 4, 1);
        StudentReadRepository studentRepo = new StudentReadRepository(jdbc,
                org.mockito.Mockito.mock(com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class),
                new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));

        Map<String, Object> preview = studentRepo.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", java.util.List.of(Map.of(
                        "Name", "Imp Inactive Section", "Class", "1", "Section", "B",
                        "AdmissionNo", "IMP-INACTIVE-SECTION", "Gender", "Female",
                        "Phone", "9876543210"))));

        assertThat(preview.get("validCount")).isEqualTo(0);
        assertThat(preview.get("errorCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> structure = (Map<String, Object>) preview.get("structure");
        assertThat(structure.get("requiresStructureUpdate")).isEqualTo(true);
        assertThat((java.util.List<String>) structure.get("missingSections")).contains("B");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> rows = (java.util.List<Map<String, Object>>) preview.get("rows");
        assertThat(rows.get(0).get("status")).isEqualTo("Setup update needed");
    }

    @Test
    void previewImport_marksInactiveClassAsSetupUpdateNeeded() throws Exception {
        long schoolId = seedSchool(4, 1);
        StudentReadRepository studentRepo = new StudentReadRepository(jdbc,
                org.mockito.Mockito.mock(com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class),
                new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));

        Map<String, Object> preview = studentRepo.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", java.util.List.of(Map.of(
                        "Name", "Imp Inactive Class", "Class", "3", "Section", "A",
                        "AdmissionNo", "IMP-INACTIVE-CLASS", "Gender", "Female",
                        "Phone", "9876543210"))));

        assertThat(preview.get("validCount")).isEqualTo(0);
        assertThat(preview.get("errorCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> structure = (Map<String, Object>) preview.get("structure");
        assertThat(structure.get("requiresStructureUpdate")).isEqualTo(true);
        assertThat((java.util.List<String>) structure.get("missingClasses")).contains("3");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> rows = (java.util.List<Map<String, Object>>) preview.get("rows");
        assertThat(rows.get(0).get("status")).isEqualTo("Setup update needed");
        assertThat(rows.get(0).get("description")).isEqualTo("Class is not active for this school's configured setup");
    }

    @Test
    void confirmImport_rejectsInactiveSectionIfStructureChangedAfterPreview() throws Exception {
        long schoolId = seedSchool(4, 2);
        StudentReadRepository studentRepo = new StudentReadRepository(jdbc,
                org.mockito.Mockito.mock(com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class),
                new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));

        Map<String, Object> preview = studentRepo.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", java.util.List.of(Map.of(
                        "Name", "Imp Shrunk Section", "Class", "1", "Section", "B",
                        "AdmissionNo", "IMP-SHRUNK-SECTION", "Gender", "Male",
                        "Phone", "9876543210"))));
        assertThat(preview.get("validCount")).isEqualTo(1);
        repo.updateStructure(schoolId, 4, 1);

        Map<String, Object> confirm = studentRepo.confirmImport(Map.of(
                "schoolId", schoolId,
                "fileToken", preview.get("fileToken")));

        assertThat(confirm.get("inserted")).isEqualTo(0);
        assertThat(confirm.get("skipped")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> skippedRows =
                (java.util.List<Map<String, Object>>) confirm.get("skippedRows");
        assertThat(String.valueOf(skippedRows.get(0).get("reason"))).contains("not active");
    }

    @Test
    void confirmImport_rejectsInactiveClassIfStructureChangedAfterPreview() throws Exception {
        long schoolId = seedSchool(6, 1);
        StudentReadRepository studentRepo = new StudentReadRepository(jdbc,
                org.mockito.Mockito.mock(com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class),
                new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));

        Map<String, Object> preview = studentRepo.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", java.util.List.of(Map.of(
                        "Name", "Imp Shrunk Class", "Class", "3", "Section", "A",
                        "AdmissionNo", "IMP-SHRUNK-CLASS", "Gender", "Male",
                        "Phone", "9876543210"))));
        assertThat(preview.get("validCount")).isEqualTo(1);
        repo.updateStructure(schoolId, 5, 1);

        Map<String, Object> confirm = studentRepo.confirmImport(Map.of(
                "schoolId", schoolId,
                "fileToken", preview.get("fileToken")));

        assertThat(confirm.get("inserted")).isEqualTo(0);
        assertThat(confirm.get("skipped")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> skippedRows =
                (java.util.List<Map<String, Object>>) confirm.get("skippedRows");
        assertThat(String.valueOf(skippedRows.get(0).get("reason"))).contains("Class is not active");
    }
}

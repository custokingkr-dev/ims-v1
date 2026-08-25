package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StudentGuardianForwardSyncIntegrationTest {

    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static StudentReadRepository students;
    static SchoolStructureReadRepository schools;

    @BeforeAll
    static void setUpDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        postgres.start();
        for (String schema : new String[]{"tenant_school", "student"}) {
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load().migrate();
        }
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        OutboxWriter outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        students = new StudentReadRepository(jdbc, mock(StudentPhotoStorage.class), outbox);
        schools = new SchoolStructureReadRepository(jdbc, outbox);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void resetData() {
        jdbc.sql("DELETE FROM student.student_consent_events").update();
        jdbc.sql("DELETE FROM student.student_guardians").update();
        jdbc.sql("DELETE FROM student.guardians").update();
        jdbc.sql("DELETE FROM student.import_rows").update();
        jdbc.sql("DELETE FROM student.import_job_progress").update();
        jdbc.sql("DELETE FROM student.import_batches").update();
        jdbc.sql("DELETE FROM student.student_enrollments").update();
        jdbc.sql("DELETE FROM student.students").update();
        jdbc.sql("DELETE FROM tenant_school.outbox_events").update();
        jdbc.sql("DELETE FROM tenant_school.school_sections").update();
        jdbc.sql("DELETE FROM tenant_school.schools").update();
        jdbc.sql("DELETE FROM tenant_school.school_classes").update();
        jdbc.sql("DELETE FROM tenant_school.academic_years").update();
        jdbc.sql("INSERT INTO tenant_school.school_classes (id, name, sort_order) VALUES ('c1', '1', 1)").update();
        jdbc.sql("INSERT INTO tenant_school.academic_years (id, label, active) VALUES ('ay1', '2026-27', true)").update();
    }

    @Test
    void createStudentSynchronizesFatherAndMotherWithExactParity() {
        long schoolId = seedSchool();

        Map<String, Object> created = students.createStudent(Map.of(
                "schoolId", schoolId,
                "fullName", "Create Student",
                "admissionNumber", "SYNC-CREATE",
                "gradeLevel", "1",
                "sectionName", "A",
                "phone", "9999900000",
                "fatherName", "Create Father",
                "fatherContact", "+91 98765 43210",
                "motherName", "Create Mother"));
        long studentId = ((Number) created.get("id")).longValue();

        assertThat(guardian(studentId, "FATHER"))
                .containsEntry("fullName", "Create Father")
                .containsEntry("phone", "+91 98765 43210")
                .containsEntry("primary", true);
        assertThat(guardian(studentId, "MOTHER"))
                .containsEntry("fullName", "Create Mother")
                .containsEntry("primary", false);
        assertParity(studentId);
    }

    @Test
    void updateStudentPreservesGuardianAndConsentIdentityAndRestoresParity() {
        long schoolId = seedSchool();
        Map<String, Object> created = students.createStudent(Map.of(
                "schoolId", schoolId,
                "fullName", "Update Student",
                "admissionNumber", "SYNC-UPDATE",
                "gradeLevel", "1",
                "sectionName", "A",
                "phone", "9999900001",
                "fatherName", "Original Father",
                "fatherContact", "9876543210"));
        long studentId = ((Number) created.get("id")).longValue();
        String guardianId = String.valueOf(guardian(studentId, "FATHER").get("id"));
        jdbc.sql("""
                        INSERT INTO student.student_consent_events
                            (id, school_id, student_id, guardian_id, purpose, status,
                             lawful_basis, notice_version, evidence_source)
                        VALUES
                            ('consent-forward-sync', :schoolId, :studentId, :guardianId,
                             'SCHOOL_COMMUNICATIONS', 'GRANTED', 'CONSENT', 'notice-1', 'SIGNED_FORM')
                        """)
                .param("schoolId", schoolId)
                .param("studentId", studentId)
                .param("guardianId", guardianId)
                .update();

        students.updateStudent(studentId, Map.of(
                "schoolId", schoolId,
                "fullName", "Update Student",
                "admissionNumber", "SYNC-UPDATE",
                "classId", created.get("classId"),
                "sectionId", created.get("sectionId"),
                "phone", "9999900001",
                "fatherName", "Updated Father",
                "fatherContact", "9876543299"));

        assertThat(guardian(studentId, "FATHER"))
                .containsEntry("id", guardianId)
                .containsEntry("fullName", "Updated Father")
                .containsEntry("phone", "9876543299")
                .containsEntry("primary", true);
        assertThat(jdbc.sql("SELECT guardian_id FROM student.student_consent_events WHERE id = 'consent-forward-sync'")
                .query(String.class).single()).isEqualTo(guardianId);
        assertParity(studentId);
    }

    @Test
    void spreadsheetImportSynchronizesFatherAndExactParity() {
        long schoolId = seedSchool();
        Map<String, Object> preview = students.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", List.of(Map.of(
                        "Name", "Imported Student",
                        "Class", "1",
                        "Section", "A",
                        "AdmissionNo", "SYNC-IMPORT",
                        "DateOfBirth", "2015-01-15",
                        "Gender", "Female",
                        "FatherName", "Imported Father",
                        "Phone", "9876500012"))));

        Map<String, Object> result = students.confirmImport(Map.of(
                "schoolId", schoolId,
                "fileToken", preview.get("fileToken")));
        @SuppressWarnings("unchecked")
        long studentId = ((Number) ((List<Map<String, Object>>) result.get("insertedStudents"))
                .getFirst().get("studentId")).longValue();

        assertThat(guardian(studentId, "FATHER"))
                .containsEntry("fullName", "Imported Father")
                .containsEntry("phone", "9876500012")
                .containsEntry("primary", true);
        assertParity(studentId);
    }

    private static long seedSchool() {
        jdbc.sql("""
                        INSERT INTO tenant_school.schools
                            (name, short_code, active, configured_class_count,
                             configured_section_count, created_at)
                        VALUES ('Guardian Sync School', 'GSYNC', true, 1, 1, now())
                        """).update();
        long schoolId = jdbc.sql("SELECT id FROM tenant_school.schools WHERE short_code = 'GSYNC'")
                .query(Long.class).single();
        schools.updateStructure(schoolId, 1, 1);
        return schoolId;
    }

    private static Map<String, Object> guardian(long studentId, String relationship) {
        return jdbc.sql("""
                        SELECT guardian.id, guardian.full_name, guardian.phone, link.is_primary
                        FROM student.student_guardians link
                        JOIN student.guardians guardian ON guardian.id = link.guardian_id
                        WHERE link.student_id = :studentId AND link.relationship = :relationship
                        ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
                        LIMIT 1
                        """)
                .param("studentId", studentId)
                .param("relationship", relationship)
                .query((rs, rowNum) -> {
                    java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("fullName", rs.getString("full_name"));
                    row.put("phone", rs.getString("phone"));
                    row.put("primary", rs.getBoolean("is_primary"));
                    return row;
                })
                .single();
    }

    private static void assertParity(long studentId) {
        Map<String, Boolean> parity = jdbc.sql("""
                        SELECT father_name_matches, father_contact_matches, mother_name_matches
                        FROM student.guardian_legacy_parity
                        WHERE student_id = :studentId
                        """)
                .param("studentId", studentId)
                .query((rs, rowNum) -> Map.of(
                        "fatherName", rs.getBoolean("father_name_matches"),
                        "fatherContact", rs.getBoolean("father_contact_matches"),
                        "motherName", rs.getBoolean("mother_name_matches")))
                .single();
        assertThat(parity.values()).allMatch(Boolean.TRUE::equals);
    }
}

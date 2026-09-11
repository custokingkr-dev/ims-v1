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
    void legacyUpdateOfSharedFatherFansOutWithoutChangingLinksOrConsentHistory() {
        long schoolId = seedSchool();
        Map<String, Object> first = students.createStudent(Map.of(
                "schoolId", schoolId,
                "fullName", "First Sibling",
                "admissionNumber", "SYNC-SHARED-1",
                "gradeLevel", "1",
                "sectionName", "A",
                "phone", "9999900011",
                "fatherName", "Shared Father",
                "fatherContact", "9876500011"));
        Map<String, Object> second = students.createStudent(Map.of(
                "schoolId", schoolId,
                "fullName", "Second Sibling",
                "admissionNumber", "SYNC-SHARED-2",
                "gradeLevel", "1",
                "sectionName", "A",
                "phone", "9999900012",
                "fatherName", "Shared Father",
                "fatherContact", "9876500011"));
        long firstId = ((Number) first.get("id")).longValue();
        long secondId = ((Number) second.get("id")).longValue();
        String sharedGuardianId = String.valueOf(guardian(firstId, "FATHER").get("id"));
        String replacedGuardianId = String.valueOf(guardian(secondId, "FATHER").get("id"));

        jdbc.sql("DELETE FROM student.student_guardians WHERE student_id = :studentId AND guardian_id = :guardianId")
                .param("studentId", secondId).param("guardianId", replacedGuardianId).update();
        jdbc.sql("DELETE FROM student.guardians WHERE id = :guardianId")
                .param("guardianId", replacedGuardianId).update();
        jdbc.sql("""
                        INSERT INTO student.student_guardians
                            (id, school_id, student_id, guardian_id, relationship, is_primary,
                             receives_notifications, can_view_academic, can_manage_fees,
                             pickup_authorized, created_at, updated_at, version)
                        VALUES
                            ('shared-father-second-link', :schoolId, :studentId, :guardianId,
                             'FATHER', true, false, true, true, true, now(), now(), 7)
                        """)
                .param("schoolId", schoolId).param("studentId", secondId)
                .param("guardianId", sharedGuardianId).update();
        jdbc.sql("UPDATE student.guardians SET contact_verified_at = now() WHERE id = :guardianId")
                .param("guardianId", sharedGuardianId).update();
        jdbc.sql("""
                        INSERT INTO student.student_consent_events
                            (id, school_id, student_id, guardian_id, purpose, status,
                             lawful_basis, notice_version, evidence_source)
                        VALUES
                            ('shared-consent-1', :schoolId, :firstId, :guardianId,
                             'SCHOOL_COMMUNICATIONS', 'GRANTED', 'CONSENT', 'notice-1', 'SIGNED_FORM'),
                            ('shared-consent-2', :schoolId, :secondId, :guardianId,
                             'SCHOOL_COMMUNICATIONS', 'GRANTED', 'CONSENT', 'notice-1', 'SIGNED_FORM')
                        """)
                .param("schoolId", schoolId).param("firstId", firstId).param("secondId", secondId)
                .param("guardianId", sharedGuardianId).update();

        String firstLinkBefore = linkState(firstId, sharedGuardianId);
        String secondLinkBefore = linkState(secondId, sharedGuardianId);
        String consentsBefore = consentSnapshot();
        jdbc.sql("DELETE FROM tenant_school.outbox_events").update();

        students.updateStudent(firstId, Map.of(
                "schoolId", schoolId,
                "fullName", "First Sibling",
                "admissionNumber", "SYNC-SHARED-1",
                "classId", first.get("classId"),
                "sectionId", first.get("sectionId"),
                "phone", "9999900011",
                "fatherName", "Updated Shared Father",
                "fatherContact", "9876500099"));

        assertThat(guardian(firstId, "FATHER"))
                .containsEntry("id", sharedGuardianId)
                .containsEntry("fullName", "Updated Shared Father")
                .containsEntry("phone", "9876500099");
        assertThat(guardian(secondId, "FATHER"))
                .containsEntry("id", sharedGuardianId)
                .containsEntry("fullName", "Updated Shared Father")
                .containsEntry("phone", "9876500099");
        assertThat(linkState(firstId, sharedGuardianId)).isEqualTo(firstLinkBefore);
        assertThat(linkState(secondId, sharedGuardianId)).isEqualTo(secondLinkBefore);
        assertThat(consentSnapshot()).isEqualTo(consentsBefore);
        assertThat(jdbc.sql("SELECT contact_verified_at IS NULL FROM student.guardians WHERE id = :guardianId")
                .param("guardianId", sharedGuardianId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT aggregate_id FROM tenant_school.outbox_events
                        WHERE event_type = 'student.upserted.v1'
                        ORDER BY aggregate_id
                        """).query(String.class).list())
                .containsExactly(String.valueOf(firstId), String.valueOf(secondId));
        assertParity(firstId);
        assertParity(secondId);
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

    // ── the legacy projection is derived once, from the final guardian state ────
    //
    // students.father_name / father_contact / mother_name are a projection of the guardian
    // ledger. A legacy save writes them directly and then syncs both relationships; the
    // projection must agree with the guardian rows after the sync no matter which
    // combination of father / mother changes the save carried.

    @Test
    void fatherOnlySave_projectsTheFatherAndLeavesTheMotherAbsent() {
        long schoolId = seedSchool();
        Map<String, Object> created = createLegacy(schoolId, "PROJ-F", "Father Only",
                "Original Father", "9876500001", null);
        long studentId = studentId(created);
        assertThat(projection(studentId)).isEqualTo(new Projection("Original Father", "9876500001", null));

        saveLegacy(created, "Renamed Father", "9876500002", null);

        assertThat(guardian(studentId, "FATHER"))
                .containsEntry("fullName", "Renamed Father")
                .containsEntry("phone", "9876500002");
        assertThat(guardianCount(studentId, "MOTHER")).isZero();
        assertThat(projection(studentId)).isEqualTo(new Projection("Renamed Father", "9876500002", null));
        assertParity(studentId);
    }

    @Test
    void motherOnlySave_projectsTheMotherAndLeavesTheFatherAbsent() {
        long schoolId = seedSchool();
        Map<String, Object> created = createLegacy(schoolId, "PROJ-M", "Mother Only",
                null, null, "Original Mother");
        long studentId = studentId(created);
        assertThat(guardianCount(studentId, "FATHER")).isZero();
        String motherId = String.valueOf(guardian(studentId, "MOTHER").get("id"));

        saveLegacy(created, null, null, "Renamed Mother");

        assertThat(guardian(studentId, "MOTHER"))
                .containsEntry("id", motherId)
                .containsEntry("fullName", "Renamed Mother");
        assertThat(guardianCount(studentId, "FATHER")).isZero();
        assertThat(projection(studentId)).isEqualTo(new Projection(null, null, "Renamed Mother"));
        assertParity(studentId);
    }

    @Test
    void addingBothParentsInOneSave_projectsBoth() {
        long schoolId = seedSchool();
        Map<String, Object> created = createLegacy(schoolId, "PROJ-BOTH", "No Parents Yet",
                null, null, null);
        long studentId = studentId(created);
        assertThat(guardianCount(studentId, "FATHER")).isZero();
        assertThat(guardianCount(studentId, "MOTHER")).isZero();

        saveLegacy(created, "New Father", "9876500003", "New Mother");

        assertThat(guardian(studentId, "FATHER"))
                .containsEntry("fullName", "New Father")
                .containsEntry("phone", "9876500003")
                .containsEntry("primary", true);
        assertThat(guardian(studentId, "MOTHER"))
                .containsEntry("fullName", "New Mother")
                .containsEntry("primary", false);
        assertThat(projection(studentId)).isEqualTo(new Projection("New Father", "9876500003", "New Mother"));
        assertParity(studentId);
    }

    /**
     * The data-loss bug: the FATHER sync used to rewrite the projection from the guardian rows
     * before the MOTHER row existed, wiping the mother name the save had just written.
     * The father's identity (and any consent recorded against it) must survive the edit.
     */
    @Test
    void fatherChangedAndMotherAddedInOneSave_keepsTheMotherOnTheProjection() {
        long schoolId = seedSchool();
        Map<String, Object> created = createLegacy(schoolId, "PROJ-BUG", "Imported Child",
                "Imported Father", "9876500004", null);
        long studentId = studentId(created);
        String fatherId = String.valueOf(guardian(studentId, "FATHER").get("id"));
        jdbc.sql("""
                        INSERT INTO student.student_consent_events
                            (id, school_id, student_id, guardian_id, purpose, status,
                             lawful_basis, notice_version, evidence_source)
                        VALUES
                            ('consent-combined-edit', :schoolId, :studentId, :guardianId,
                             'STUDENT_PHOTO', 'GRANTED', 'CONSENT', 'notice-1', 'SIGNED_FORM')
                        """)
                .param("schoolId", schoolId).param("studentId", studentId).param("guardianId", fatherId)
                .update();
        String consentsBefore = consentSnapshot();

        saveLegacy(created, "Corrected Father", "9876500005", "Added Mother");

        assertThat(guardian(studentId, "FATHER"))
                .containsEntry("id", fatherId)
                .containsEntry("fullName", "Corrected Father")
                .containsEntry("phone", "9876500005");
        assertThat(guardian(studentId, "MOTHER")).containsEntry("fullName", "Added Mother");
        assertThat(projection(studentId))
                .isEqualTo(new Projection("Corrected Father", "9876500005", "Added Mother"));
        assertThat(consentSnapshot()).isEqualTo(consentsBefore);
        assertParity(studentId);
    }

    /**
     * Mirror of the bug: with the early refresh, blanking the mother while the father changed
     * resurrected the old mother name on the profile even though her guardian was unlinked.
     */
    @Test
    void fatherChangedAndMotherRemovedInOneSave_dropsTheMotherFromTheProjection() {
        long schoolId = seedSchool();
        Map<String, Object> created = createLegacy(schoolId, "PROJ-MIRROR", "Child",
                "Father", "9876500006", "Departing Mother");
        long studentId = studentId(created);
        String motherId = String.valueOf(guardian(studentId, "MOTHER").get("id"));

        saveLegacy(created, "Renamed Father", "9876500007", null);

        assertThat(guardianCount(studentId, "MOTHER")).isZero();
        assertThat(guardianStatus(motherId)).isEqualTo("INACTIVE");
        assertThat(projection(studentId)).isEqualTo(new Projection("Renamed Father", "9876500007", null));
        assertParity(studentId);
    }

    @Test
    void removingTheMother_unlinksHerAndClearsTheProjection() {
        long schoolId = seedSchool();
        Map<String, Object> created = createLegacy(schoolId, "PROJ-RM", "Child",
                "Father", "9876500008", "Departing Mother");
        long studentId = studentId(created);
        String fatherId = String.valueOf(guardian(studentId, "FATHER").get("id"));
        String motherId = String.valueOf(guardian(studentId, "MOTHER").get("id"));

        saveLegacy(created, "Father", "9876500008", null);

        assertThat(guardianCount(studentId, "MOTHER")).isZero();
        assertThat(guardianStatus(motherId)).isEqualTo("INACTIVE");
        assertThat(guardian(studentId, "FATHER")).containsEntry("id", fatherId);
        assertThat(projection(studentId)).isEqualTo(new Projection("Father", "9876500008", null));
        assertParity(studentId);
    }

    @Test
    void resavingAnUnchangedForm_leavesGuardiansAndTheProjectionUntouched() {
        long schoolId = seedSchool();
        Map<String, Object> created = createLegacy(schoolId, "PROJ-IDEM", "Child",
                "Father", "9876500009", "Mother");
        long studentId = studentId(created);
        String guardiansBefore = guardianLedgerSnapshot();
        long versionBefore = studentVersion(studentId);
        jdbc.sql("DELETE FROM tenant_school.outbox_events").update();

        saveLegacy(created, "Father", "9876500009", "Mother");
        saveLegacy(created, "Father", "9876500009", "Mother");

        // No guardian re-created, re-versioned or unlinked; no link touched.
        assertThat(guardianLedgerSnapshot()).isEqualTo(guardiansBefore);
        assertThat(projection(studentId)).isEqualTo(new Projection("Father", "9876500009", "Mother"));
        // One version bump per save (the caller's own UPDATE); the projection refresh adds none.
        assertThat(studentVersion(studentId)).isEqualTo(versionBefore + 2);
        // Only this student's own upsert events; nothing fanned out.
        assertThat(upsertedStudentIds()).containsExactly(studentId, studentId);
        assertParity(studentId);
    }

    /**
     * The consequence of the bug: after the combined edit the modal re-read a NULL mother_name,
     * so the next save (with nothing changed by the user) sent an empty motherName and the
     * synchronizer unlinked the mother guardian for good. The form must round-trip cleanly.
     */
    @Test
    void resavingTheFormAfterTheCombinedEdit_doesNotUnlinkTheMother() {
        long schoolId = seedSchool();
        Map<String, Object> created = createLegacy(schoolId, "PROJ-RESAVE", "Imported Child",
                "Imported Father", "9876500010", null);
        long studentId = studentId(created);
        saveLegacy(created, "Corrected Father", "9876500011", "Added Mother");
        String motherId = String.valueOf(guardian(studentId, "MOTHER").get("id"));

        // Re-save exactly what the edit modal now reads back.
        Map<String, Object> detail = students.workspaceStudentDetail(studentId);
        saveLegacy(created, (String) detail.get("fatherName"), (String) detail.get("fatherContact"),
                (String) detail.get("motherName"));

        assertThat(guardianCount(studentId, "MOTHER")).isOne();
        assertThat(guardian(studentId, "MOTHER"))
                .containsEntry("id", motherId)
                .containsEntry("fullName", "Added Mother");
        assertThat(guardianStatus(motherId)).isEqualTo("ACTIVE");
        assertThat(projection(studentId))
                .isEqualTo(new Projection("Corrected Father", "9876500011", "Added Mother"));
        assertParity(studentId);
    }

    /**
     * Deferring the refresh must not lose the fan-out: a shared father identity edited through one
     * sibling still re-projects onto the other sibling, whose own mother is left alone.
     */
    @Test
    void fatherChangedAndMotherAdded_stillFansTheSharedFatherOutToTheSibling() {
        long schoolId = seedSchool();
        Map<String, Object> first = createLegacy(schoolId, "PROJ-SIB-1", "First Sibling",
                "Shared Father", "9876500012", null);
        Map<String, Object> second = createLegacy(schoolId, "PROJ-SIB-2", "Second Sibling",
                "Shared Father", "9876500012", "Second Mother");
        long firstId = studentId(first);
        long secondId = studentId(second);
        String sharedFatherId = shareFatherGuardian(schoolId, firstId, secondId);
        jdbc.sql("DELETE FROM tenant_school.outbox_events").update();

        saveLegacy(first, "Corrected Father", "9876500013", "First Mother");

        assertThat(guardian(secondId, "FATHER")).containsEntry("id", sharedFatherId);
        assertThat(projection(firstId))
                .isEqualTo(new Projection("Corrected Father", "9876500013", "First Mother"));
        assertThat(projection(secondId))
                .isEqualTo(new Projection("Corrected Father", "9876500013", "Second Mother"));
        assertThat(guardian(secondId, "MOTHER")).containsEntry("fullName", "Second Mother");
        assertThat(upsertedStudentIds()).containsExactlyInAnyOrder(firstId, secondId);
        assertParity(firstId);
        assertParity(secondId);
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

    /** The student's own phone; the create response does not echo it, so both helpers share it. */
    private static final String STUDENT_PHONE = "9999900099";

    /** Creates a student through the legacy shape; null parent fields are simply omitted. */
    private static Map<String, Object> createLegacy(long schoolId, String admissionNo, String fullName,
                                                    String fatherName, String fatherContact, String motherName) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("schoolId", schoolId);
        request.put("fullName", fullName);
        request.put("admissionNumber", admissionNo);
        request.put("gradeLevel", "1");
        request.put("sectionName", "A");
        request.put("phone", STUDENT_PHONE);
        if (fatherName != null) request.put("fatherName", fatherName);
        if (fatherContact != null) request.put("fatherContact", fatherContact);
        if (motherName != null) request.put("motherName", motherName);
        return students.createStudent(request);
    }

    /** Saves the edit form for a created student, changing only the legacy parent fields. */
    private static void saveLegacy(Map<String, Object> created,
                                   String fatherName, String fatherContact, String motherName) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("schoolId", created.get("schoolId"));
        request.put("fullName", created.get("fullName"));
        request.put("admissionNumber", created.get("admissionNumber"));
        request.put("classId", created.get("classId"));
        request.put("sectionId", created.get("sectionId"));
        request.put("phone", STUDENT_PHONE);
        if (fatherName != null) request.put("fatherName", fatherName);
        if (fatherContact != null) request.put("fatherContact", fatherContact);
        if (motherName != null) request.put("motherName", motherName);
        students.updateStudent(studentId(created), request);
    }

    private static long studentId(Map<String, Object> created) {
        return ((Number) created.get("id")).longValue();
    }

    /** The legacy columns as the parity view compares them: trimmed, blank folded to null. */
    private record Projection(String fatherName, String fatherContact, String motherName) {
    }

    private static Projection projection(long studentId) {
        return jdbc.sql("""
                        SELECT NULLIF(btrim(COALESCE(father_name, '')), '') AS father_name,
                               NULLIF(btrim(COALESCE(father_contact, '')), '') AS father_contact,
                               NULLIF(btrim(COALESCE(mother_name, '')), '') AS mother_name
                        FROM student.students WHERE id = :studentId
                        """)
                .param("studentId", studentId)
                .query((rs, rowNum) -> new Projection(
                        rs.getString("father_name"), rs.getString("father_contact"), rs.getString("mother_name")))
                .single();
    }

    private static long guardianCount(long studentId, String relationship) {
        return jdbc.sql("""
                        SELECT count(*) FROM student.student_guardians
                        WHERE student_id = :studentId AND relationship = :relationship
                        """)
                .param("studentId", studentId).param("relationship", relationship)
                .query(Long.class).single();
    }

    private static String guardianStatus(String guardianId) {
        return jdbc.sql("SELECT status FROM student.guardians WHERE id = :guardianId")
                .param("guardianId", guardianId).query(String.class).single();
    }

    private static long studentVersion(long studentId) {
        return jdbc.sql("SELECT version FROM student.students WHERE id = :studentId")
                .param("studentId", studentId).query(Long.class).single();
    }

    /** Every guardian and link row, including version and updated_at, so a no-op must be byte-identical. */
    private static String guardianLedgerSnapshot() {
        return jdbc.sql("""
                        SELECT concat(
                            (SELECT COALESCE(jsonb_agg(to_jsonb(g) ORDER BY g.id)::text, '[]') FROM student.guardians g),
                            '|',
                            (SELECT COALESCE(jsonb_agg(to_jsonb(l) ORDER BY l.id)::text, '[]') FROM student.student_guardians l))
                        """).query(String.class).single();
    }

    private static List<Long> upsertedStudentIds() {
        return jdbc.sql("""
                        SELECT aggregate_id::bigint FROM tenant_school.outbox_events
                        WHERE event_type = 'student.upserted.v1'
                        ORDER BY id
                        """).query(Long.class).list();
    }

    /** Re-points {@code toStudent}'s FATHER link at {@code fromStudent}'s father guardian (siblings). */
    private static String shareFatherGuardian(long schoolId, long fromStudent, long toStudent) {
        String sharedGuardianId = String.valueOf(guardian(fromStudent, "FATHER").get("id"));
        String replacedGuardianId = String.valueOf(guardian(toStudent, "FATHER").get("id"));
        jdbc.sql("DELETE FROM student.student_guardians WHERE student_id = :studentId AND guardian_id = :guardianId")
                .param("studentId", toStudent).param("guardianId", replacedGuardianId).update();
        jdbc.sql("DELETE FROM student.guardians WHERE id = :guardianId")
                .param("guardianId", replacedGuardianId).update();
        jdbc.sql("""
                        INSERT INTO student.student_guardians
                            (id, school_id, student_id, guardian_id, relationship, is_primary,
                             receives_notifications, can_view_academic, can_manage_fees,
                             pickup_authorized, created_at, updated_at, version)
                        VALUES
                            (:id, :schoolId, :studentId, :guardianId, 'FATHER', true,
                             true, true, false, false, now(), now(), 0)
                        """)
                .param("id", "shared-father-link-" + toStudent)
                .param("schoolId", schoolId).param("studentId", toStudent)
                .param("guardianId", sharedGuardianId).update();
        return sharedGuardianId;
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

    private static String linkState(long studentId, String guardianId) {
        return jdbc.sql("""
                        SELECT concat_ws('|', relationship, is_primary, receives_notifications,
                                         can_view_academic, can_manage_fees, pickup_authorized, version)
                        FROM student.student_guardians
                        WHERE student_id = :studentId AND guardian_id = :guardianId
                        """)
                .param("studentId", studentId).param("guardianId", guardianId)
                .query(String.class).single();
    }

    private static String consentSnapshot() {
        return jdbc.sql("""
                        SELECT COALESCE(jsonb_agg(to_jsonb(event) ORDER BY event.id)::text, '[]')
                        FROM student.student_consent_events event
                        """).query(String.class).single();
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

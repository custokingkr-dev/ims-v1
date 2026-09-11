package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@code StudentReadRepository.updateStudent} — the write behind
 * {@code PUT /api/v1/workspace/students/{id}} — against the real student + tenant_school schema:
 * per-school admission-number uniqueness, class/section transfer with enrolment history, audit
 * actor from {@link TenantContext}, and the {@code /students/{id}/workspace} detail shape the
 * edit modal reads back.
 *
 * <p>Runs as the table owner (RLS-exempt). The cross-school assertions exercise the
 * application-level {@code school_id} predicates only; they do not prove RLS isolation.</p>
 */
class StudentUpdateIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static JdbcClient jdbc;
    static StudentReadRepository students;

    static final long SCHOOL_A = 1L;
    static final long SCHOOL_B = 2L;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        for (String schema : new String[] {"tenant_school", "student"}) {
            Flyway.configure()
                    .dataSource(PG.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load().migrate();
        }
        DataSource ds = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(ds);
        OutboxWriter outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        students = new StudentReadRepository(jdbc, mock(StudentPhotoStorage.class), outbox);
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    /**
     * School A: class c1 sections a1 / a2, class c2 section a3. School B: class c1 section b1.
     * Classes are global; sections carry the school.
     */
    @BeforeEach
    void seed() {
        for (String table : List.of("student.student_consent_events", "student.student_guardians", "student.guardians",
                "student.student_review_items", "student.student_review_campaigns", "student.student_enrollments",
                "student.students", "tenant_school.outbox_events", "tenant_school.school_sections",
                "tenant_school.school_classes", "tenant_school.schools", "tenant_school.academic_years")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) VALUES " +
                "(1,'School A','SCHA',true, now()), (2,'School B','SCHB',true, now())").update();
        jdbc.sql("INSERT INTO tenant_school.school_classes(id, name, sort_order) VALUES " +
                "('c1','Class 1',1), ('c2','Class 2',2)").update();
        jdbc.sql("INSERT INTO tenant_school.school_sections(id, name, teacher_name, active, school_class_id, school_id) VALUES " +
                "('a1','A','Ms Rao',true,'c1',1), ('a2','B','Mr Iyer',true,'c1',1), " +
                "('a3','A','Ms Das',true,'c2',1), ('b1','A','Mr Other',true,'c1',2)").update();
        TenantContext.set(new TenantContext(42L, "admin@a", "ADMIN", SCHOOL_A, null, Set.of(), Set.of("student:update")));
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // ── admission number uniqueness ─────────────────────────────────────────────

    @Test
    void admissionNumber_mayRepeatAcrossSchools_butNotWithinOne() {
        long ashaA = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");
        long bharatB = create(SCHOOL_B, "ADM-200", "Bharat", "c1", "b1");
        long chitraA = create(SCHOOL_A, "ADM-300", "Chitra", "c1", "a1");

        // Another school may take a number school A already holds.
        TenantContext.set(new TenantContext(43L, "admin@b", "ADMIN", SCHOOL_B, null, Set.of(), Set.of("student:update")));
        Map<String, Object> moved = students.updateStudent(bharatB, update(SCHOOL_B, "ADM-100", "Bharat", "c1", "b1"));
        assertThat(moved).containsEntry("admissionNumber", "ADM-100");
        assertThat(admissionNo(bharatB)).isEqualTo("ADM-100");
        assertThat(admissionNo(ashaA)).isEqualTo("ADM-100");

        // The same school may not duplicate it — exact or case-insensitive.
        TenantContext.set(new TenantContext(42L, "admin@a", "ADMIN", SCHOOL_A, null, Set.of(), Set.of("student:update")));
        assertThatThrownBy(() -> students.updateStudent(chitraA, update(SCHOOL_A, "ADM-100", "Chitra", "c1", "a1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Admission Number already exists");
        assertThatThrownBy(() -> students.updateStudent(chitraA, update(SCHOOL_A, "adm-100", "Chitra", "c1", "a1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Admission Number already exists");
        assertThat(admissionNo(chitraA)).as("rejected update left the row untouched").isEqualTo("ADM-300");

        // Re-submitting a student's own number is not a collision with itself.
        assertThat(students.updateStudent(ashaA, update(SCHOOL_A, "ADM-100", "Asha Rao", "c1", "a1")))
                .containsEntry("fullName", "Asha Rao");
    }

    @Test
    void admissionNumber_heldByASoftDeletedRow_isStillRejectedByTheDatabaseBackstop() {
        long asha = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");
        long chitra = create(SCHOOL_A, "ADM-300", "Chitra", "c1", "a1");
        jdbc.sql("UPDATE student.students SET deleted_at = now() WHERE id = :id").param("id", asha).update();

        // The application-level check skips deleted rows, so this reaches the
        // UNIQUE (school_id, admission_no) constraint and must be translated to the same 400.
        assertThatThrownBy(() -> students.updateStudent(chitra, update(SCHOOL_A, "ADM-100", "Chitra", "c1", "a1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Admission Number already exists");
        assertThat(admissionNo(chitra)).isEqualTo("ADM-300");
    }

    // ── class / section transfer ────────────────────────────────────────────────

    @Test
    void transfer_updatesPlacement_endsTheOldEnrolment_andOpensANewOne() {
        long asha = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");
        assertThat(enrollments(asha)).hasSize(1);
        Map<String, Object> original = enrollments(asha).getFirst();
        assertThat(original).containsEntry("classId", "c1").containsEntry("sectionId", "a1")
                .containsEntry("status", "ACTIVE").containsEntry("effectiveTo", null);

        Map<String, Object> request = update(SCHOOL_A, "ADM-100", "Asha", "c2", "a3");
        request.put("rollNo", "17");
        Map<String, Object> detail = students.updateStudent(asha, request);

        assertThat(detail).containsEntry("classId", "c2").containsEntry("sectionId", "a3")
                .containsEntry("className", "Class 2").containsEntry("sectionName", "A")
                .containsEntry("rollNo", "17");
        assertThat(jdbc.sql("SELECT class_id || '/' || section_id FROM student.students WHERE id = :id")
                .param("id", asha).query(String.class).single()).isEqualTo("c2/a3");

        List<Map<String, Object>> history = enrollments(asha);
        assertThat(history).hasSize(2);
        Map<String, Object> ended = history.get(0);
        assertThat(ended).containsEntry("id", original.get("id"))
                .containsEntry("status", "ENDED")
                .containsEntry("effectiveTo", LocalDate.now())
                .containsEntry("reason", "Placement changed from student profile edit");
        Map<String, Object> opened = history.get(1);
        assertThat(opened).containsEntry("classId", "c2").containsEntry("sectionId", "a3")
                .containsEntry("rollNo", "17").containsEntry("status", "ACTIVE")
                .containsEntry("effectiveTo", null)
                .containsEntry("reason", "TRANSFERRED")
                .containsEntry("sourceType", "STUDENT_UPDATE")
                .containsEntry("sourceId", String.valueOf(asha))
                .containsEntry("createdBy", 42L);
    }

    @Test
    void editWithoutMovingSection_keepsTheEnrolmentHistoryFlat_andRefreshesRollNo() {
        long asha = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");
        Map<String, Object> request = update(SCHOOL_A, "ADM-100", "Asha Rao", "c1", "a1");
        request.put("rollNo", "9");
        students.updateStudent(asha, request);

        List<Map<String, Object>> history = enrollments(asha);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst()).containsEntry("status", "ACTIVE").containsEntry("rollNo", "9");
    }

    @Test
    void transfer_rejectsASectionThatIsNotInTheClass_orNotInTheSchool() {
        long asha = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");

        assertThatThrownBy(() -> students.updateStudent(asha, update(SCHOOL_A, "ADM-100", "Asha", "c2", "a1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Selected section does not belong to the class and school");
        assertThatThrownBy(() -> students.updateStudent(asha, update(SCHOOL_A, "ADM-100", "Asha", "c1", "b1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Selected section does not belong to the class and school");
        assertThatThrownBy(() -> students.updateStudent(asha, update(SCHOOL_A, "ADM-100", "Asha", "c1", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Section is required");

        assertThat(jdbc.sql("SELECT class_id || '/' || section_id FROM student.students WHERE id = :id")
                .param("id", asha).query(String.class).single()).isEqualTo("c1/a1");
        assertThat(enrollments(asha)).hasSize(1);
    }

    // ── audit actor ─────────────────────────────────────────────────────────────

    @Test
    void auditActor_comesFromTenantContext_neverFromTheBody() {
        long asha = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");
        long versionBefore = jdbc.sql("SELECT version FROM student.students WHERE id = :id")
                .param("id", asha).query(Long.class).single();

        TenantContext.set(new TenantContext(42L, "admin@a", "ADMIN", SCHOOL_A, null, Set.of(), Set.of("student:update")));
        Map<String, Object> request = update(SCHOOL_A, "ADM-100", "Asha", "c1", "a2");
        request.put("updatedBy", 999);
        request.put("actorId", 999);
        request.put("createdBy", 999);
        request.put("userId", 999);
        students.updateStudent(asha, request);

        assertThat(jdbc.sql("SELECT updated_by FROM student.students WHERE id = :id")
                .param("id", asha).query(String.class).single()).isEqualTo("42");
        assertThat(jdbc.sql("SELECT version FROM student.students WHERE id = :id")
                .param("id", asha).query(Long.class).single()).isEqualTo(versionBefore + 1);
        assertThat(enrollments(asha).getLast()).containsEntry("createdBy", 42L);

        // A different authenticated user stamps a different actor for the same body.
        TenantContext.set(new TenantContext(7L, "other@a", "ADMIN", SCHOOL_A, null, Set.of(), Set.of("student:update")));
        students.updateStudent(asha, request);
        assertThat(jdbc.sql("SELECT updated_by FROM student.students WHERE id = :id")
                .param("id", asha).query(String.class).single()).isEqualTo("7");
    }

    // ── scope and existence guards ──────────────────────────────────────────────

    @Test
    void update_rejectsAScopeThatIsNotTheStudentsSchool_andUnknownOrDeletedStudents() {
        long asha = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");

        assertThatThrownBy(() -> students.updateStudent(asha, update(SCHOOL_B, "ADM-100", "Asha", "c1", "a1")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("You do not have access to this student");
        assertThatThrownBy(() -> students.updateStudent(999_999L, update(SCHOOL_A, "X", "Nobody", "c1", "a1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Student not found");

        jdbc.sql("UPDATE student.students SET deleted_at = now() WHERE id = :id").param("id", asha).update();
        assertThatThrownBy(() -> students.updateStudent(asha, update(SCHOOL_A, "ADM-100", "Asha", "c1", "a1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Student not found");
    }

    @Test
    void update_requiresNameAdmissionClassSectionAndPhone() {
        long asha = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");
        Map<String, Object> base = update(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");

        assertThatThrownBy(() -> students.updateStudent(asha, without(base, "fullName")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Full name is mandatory");
        assertThatThrownBy(() -> students.updateStudent(asha, without(base, "admissionNumber")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Admission Number is mandatory");
        assertThatThrownBy(() -> students.updateStudent(asha, without(base, "classId")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Class is required");
        assertThatThrownBy(() -> students.updateStudent(asha, without(base, "phone")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Phone is required");
    }

    // ── the workspace detail contract the edit modal reads ──────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void workspaceDetail_roundTripsEveryFieldTheEditFormReadsAndWrites() {
        // The mother is seeded at creation; the combined father-change + mother-add save has its own
        // regression test — see addingAMother_whileTheFatherAlsoChanges_...
        long asha = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1", "Meena");

        // Exactly the keys frontend/src/features/students/profileForm.ts sends on save.
        Map<String, Object> request = update(SCHOOL_A, "ADM-101", "Asha Rao", "c1", "a2");
        request.put("rollNo", "12");
        request.put("boardRegistrationNumber", "BR-77");
        request.put("dateOfBirth", "2015-06-01");
        request.put("gender", "Female");
        request.put("admissionDate", "2024-04-01");
        request.put("fatherName", "Ravi Rao");
        request.put("fatherContact", "9876543210");
        request.put("fatherContactNumber", "9876543210");
        request.put("motherName", "Meena Rao");
        request.put("phone", "9000000000");
        request.put("houseNumber", "12B");
        request.put("street", "MG Road");
        request.put("locality", "Indiranagar");
        request.put("city", "Bengaluru");
        request.put("state", "Karnataka");
        request.put("pinCode", "560038");
        students.updateStudent(asha, request);

        Map<String, Object> detail = students.workspaceStudentDetail(asha);

        // Keys studentDetailToProfileForm() pre-fills the form from.
        assertThat(detail).containsEntry("id", asha)
                .containsEntry("admissionNumber", "ADM-101")
                .containsEntry("boardRegistrationNumber", "BR-77")
                .containsEntry("fullName", "Asha Rao").containsEntry("name", "Asha Rao")
                .containsEntry("rollNo", "12")
                .containsEntry("dateOfBirth", "2015-06-01")
                .containsEntry("gender", "Female")
                .containsEntry("classId", "c1").containsEntry("sectionId", "a2")
                .containsEntry("className", "Class 1").containsEntry("sectionName", "B")
                .containsEntry("classSection", "1-B")
                .containsEntry("admissionDate", "2024-04-01")
                .containsEntry("fatherName", "Ravi Rao").containsEntry("fatherContact", "9876543210")
                .containsEntry("motherName", "Meena Rao")
                .containsEntry("phone", "9000000000")
                .containsEntry("schoolId", SCHOOL_A)
                .containsKeys("academicYear", "avatarInitials", "photoUrl", "feeStatus", "attendancePercent",
                        "profileVerificationStatus", "photoVerificationStatus", "fee");
        assertThat(detail.get("address")).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) detail.get("address"))
                .containsEntry("houseNumber", "12B").containsEntry("street", "MG Road")
                .containsEntry("locality", "Indiranagar").containsEntry("city", "Bengaluru")
                .containsEntry("state", "Karnataka").containsEntry("pinCode", "560038")
                .containsEntry("full", "12B, MG Road, Indiranagar, Bengaluru, Karnataka, 560038");
        assertThat(detail.get("avatarInitials")).isEqualTo("AR");
        assertThat((Map<String, Object>) detail.get("fee")).containsEntry("assigned", false);

        // The flat legacy read still agrees on the mutable fields.
        StudentReadRepository.StudentRow flat = students.find(asha).orElseThrow();
        assertThat(flat.admissionNo()).isEqualTo("ADM-101");
        assertThat(flat.sectionId()).isEqualTo("a2");
    }

    // ── legacy parent columns vs the guardian sync ──────────────────────────────

    @Test
    void addingAMother_withoutTouchingTheFather_keepsHerOnTheProfile() {
        long asha = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");
        Map<String, Object> request = update(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");
        request.put("fatherName", "Father of Asha");       // unchanged from create()
        request.put("fatherContact", "9111111111");        // unchanged from create()
        request.put("motherName", "Meena Rao");
        students.updateStudent(asha, request);

        assertThat(students.workspaceStudentDetail(asha)).containsEntry("motherName", "Meena Rao");
        assertThat(guardianName(asha, "MOTHER")).isEqualTo("Meena Rao");
    }

    /**
     * Regression for a data-loss bug found 2026-09-11 (reproduced against origin/dev):
     * {@code LegacyGuardianSynchronizer.syncFromLegacy} syncs FATHER then MOTHER. When the father
     * guardian already existed and changed, {@code updateExisting} immediately rewrote
     * {@code students.father_name/father_contact/mother_name} from the guardian tables — before the
     * MOTHER guardian had been inserted — so {@code mother_name} was set to NULL, and the MOTHER
     * branch's {@code insertNew} never refreshed the projection. The guardian row said "Meena Rao",
     * the student row (read by the detail modal and the student export; dim_student carries no
     * mother_name) said null, and a second save from the now-empty form unlinked the mother
     * guardian for good.
     *
     * <p>Typical trigger: a spreadsheet-imported student (father + phone, no mother) whose first
     * profile edit corrects the father's phone and fills in the mother's name.</p>
     *
     * <p>The synchronizer now syncs both relationships first and derives the projection once from
     * the final guardian state; the full behaviour matrix lives in
     * {@link StudentGuardianForwardSyncIntegrationTest}.</p>
     */
    @Test
    void addingAMother_whileTheFatherAlsoChanges_keepsHerOnTheProfile() {
        long asha = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");
        Map<String, Object> request = update(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");
        request.put("fatherName", "Ravi Rao");             // changed
        request.put("fatherContact", "9876543210");        // changed
        request.put("motherName", "Meena Rao");            // new
        students.updateStudent(asha, request);

        // The normalized guardian is created ...
        assertThat(guardianName(asha, "MOTHER")).isEqualTo("Meena Rao");
        // ... and the legacy column the detail modal reads must agree with it.
        assertThat(jdbc.sql("SELECT mother_name FROM student.students WHERE id = :id")
                .param("id", asha).query(String.class).optional().orElse(null)).isEqualTo("Meena Rao");
        assertThat(students.workspaceStudentDetail(asha)).containsEntry("motherName", "Meena Rao");
    }

    @Test
    void workspaceDetail_ofADeletedStudent_isNotFound() {
        long asha = create(SCHOOL_A, "ADM-100", "Asha", "c1", "a1");
        jdbc.sql("UPDATE student.students SET deleted_at = now() WHERE id = :id").param("id", asha).update();
        assertThatThrownBy(() -> students.workspaceStudentDetail(asha))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Student not found");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static long create(long schoolId, String admissionNo, String fullName, String classId, String sectionId) {
        return create(schoolId, admissionNo, fullName, classId, sectionId, null);
    }

    private static long create(long schoolId, String admissionNo, String fullName, String classId, String sectionId,
                               String motherName) {
        Map<String, Object> request = new HashMap<>();
        request.put("schoolId", schoolId);
        request.put("admissionNumber", admissionNo);
        request.put("fullName", fullName);
        request.put("classId", classId);
        request.put("sectionId", sectionId);
        request.put("phone", "9000000000");
        request.put("fatherName", "Father of " + fullName);
        request.put("fatherContact", "9111111111");
        if (motherName != null) request.put("motherName", motherName);
        Map<String, Object> created = students.createStudent(request);
        return ((Number) created.get("id")).longValue();
    }

    private static Map<String, Object> update(long schoolId, String admissionNo, String fullName, String classId, String sectionId) {
        Map<String, Object> m = new HashMap<>();
        m.put("schoolId", schoolId);
        m.put("admissionNumber", admissionNo);
        m.put("fullName", fullName);
        m.put("classId", classId);
        m.put("sectionId", sectionId);
        m.put("phone", "9000000000");
        return m;
    }

    private static Map<String, Object> without(Map<String, Object> base, String key) {
        Map<String, Object> m = new HashMap<>(base);
        m.remove(key);
        return m;
    }

    private static String guardianName(long studentId, String relationship) {
        return jdbc.sql("""
                        SELECT g.full_name FROM student.student_guardians link
                        JOIN student.guardians g ON g.id = link.guardian_id
                        WHERE link.student_id = :studentId AND link.relationship = :relationship AND g.status = 'ACTIVE'
                        """)
                .param("studentId", studentId).param("relationship", relationship)
                .query(String.class).optional().orElse(null);
    }

    private static String admissionNo(long id) {
        return jdbc.sql("SELECT admission_no FROM student.students WHERE id = :id").param("id", id).query(String.class).single();
    }

    private static List<Map<String, Object>> enrollments(long studentId) {
        return jdbc.sql("""
                        SELECT id, class_id, section_id, roll_no, status, effective_to, reason, source_type, source_id, created_by
                        FROM student.student_enrollments
                        WHERE student_id = :studentId
                        ORDER BY created_at, id
                        """)
                .param("studentId", studentId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("classId", rs.getString("class_id"));
                    m.put("sectionId", rs.getString("section_id"));
                    m.put("rollNo", rs.getString("roll_no"));
                    m.put("status", rs.getString("status"));
                    m.put("effectiveTo", rs.getObject("effective_to", LocalDate.class));
                    m.put("reason", rs.getString("reason"));
                    m.put("sourceType", rs.getString("source_type"));
                    m.put("sourceId", rs.getString("source_id"));
                    m.put("createdBy", rs.getObject("created_by", Long.class));
                    return m;
                })
                .list();
    }
}

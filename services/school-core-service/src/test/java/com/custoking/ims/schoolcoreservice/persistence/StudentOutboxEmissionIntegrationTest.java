package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that {@code createStudent} / {@code updateStudent} emit {@code student.upserted.v1}
 * into {@code tenant_school.outbox_events} within the same transaction as the domain write,
 * per Reporting Decoupling SP5. Mirrors {@code ReferenceEventEmissionIntegrationTest}'s
 * (SP1) bootstrap/assertion shape exactly, one level down (student, not school/section).
 */
class StudentOutboxEmissionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;
    static SchoolStructureReadRepository schoolRepo;
    static StudentReadRepository studentRepo;
    static StudentPhotoStorage photoStorage;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        for (String schema : new String[] {"tenant_school", "student", "fee", "attendance"}) {
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
        OutboxWriter outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        schoolRepo = new SchoolStructureReadRepository(jdbc, outbox);
        photoStorage = org.mockito.Mockito.mock(StudentPhotoStorage.class);
        studentRepo = new StudentReadRepository(jdbc, photoStorage, outbox);
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            org.mockito.Mockito.reset(photoStorage);
            st.execute("DELETE FROM attendance.absentee_notifications");
            st.execute("DELETE FROM attendance.attendance_student_records");
            st.execute("DELETE FROM attendance.attendance_daily");
            st.execute("DELETE FROM fee.payment_records");
            st.execute("DELETE FROM fee.fee_assignments");
            st.execute("DELETE FROM fee.fee_items");
            st.execute("DELETE FROM fee.fee_bands");
            st.execute("DELETE FROM student.student_review_items");
            st.execute("DELETE FROM student.student_review_campaigns");
            st.execute("DELETE FROM student.photo_import_rows");
            st.execute("DELETE FROM student.photo_import_column_mappings");
            st.execute("DELETE FROM student.photo_import_sources");
            st.execute("DELETE FROM student.photo_import_batches");
            st.execute("DELETE FROM student.student_consent_events");
            st.execute("DELETE FROM student.student_guardians");
            st.execute("DELETE FROM student.guardians");
            st.execute("DELETE FROM student.student_promotion_batch_items");
            st.execute("DELETE FROM student.student_promotion_batches");
            st.execute("DELETE FROM student.import_rows");
            st.execute("DELETE FROM student.import_batches");
            st.execute("DELETE FROM student.student_enrollments");
            st.execute("DELETE FROM student.students");
            st.execute("DELETE FROM tenant_school.outbox_events");
            st.execute("DELETE FROM tenant_school.school_sections");
            st.execute("DELETE FROM tenant_school.school_classes");
            st.execute("DELETE FROM tenant_school.academic_years");
            st.execute("DELETE FROM tenant_school.schools");
            for (int i = 1; i <= 12; i++) {
                st.execute("INSERT INTO tenant_school.school_classes (id, name, sort_order) VALUES " +
                        "('c" + i + "', '" + i + "', " + i + ")");
            }
            st.execute("INSERT INTO tenant_school.academic_years (id, label, active) VALUES ('ay1', '2025-26', true)");
        }
    }

    static long seedSchool(int classCount, int sectionCount) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.schools " +
                    "(name, short_code, city, state, active, configured_class_count, configured_section_count, created_at) " +
                    "VALUES ('Demo', 'DEMO', 'Hyd', 'TG', true, " + classCount + ", " + sectionCount + ", now()) ");
        }
        Long id = jdbc.sql("SELECT id FROM tenant_school.schools WHERE short_code = 'DEMO'")
                .query(Long.class).single();
        schoolRepo.updateStructure(id, classCount, sectionCount);
        return id;
    }

    @Test
    void createStudentEmitsStudentUpsertedInSameTransaction() throws Exception {
        long schoolId = seedSchool(3, 2);

        Map<String, Object> created = studentRepo.createStudent(Map.of(
                "schoolId", schoolId,
                "fullName", "Jane Doe",
                "admissionNumber", "ADM-1",
                "gradeLevel", "1",
                "sectionName", "A",
                "phone", "9876500000"));
        Long id = ((Number) created.get("id")).longValue();

        var rows = jdbc.sql("""
                        SELECT event_type, payload FROM tenant_school.outbox_events
                        WHERE aggregate_type = 'Student' AND aggregate_id = :id
                        """)
                .param("id", id.toString())
                .query((rs, n) -> rs.getString("event_type"))
                .list();
        assertThat(rows).contains("student.upserted.v1");
    }

    @Test
    void updateStudentEmitsStudentUpsertedInSameTransaction() throws Exception {
        long schoolId = seedSchool(3, 2);
        Map<String, Object> created = studentRepo.createStudent(Map.of(
                "schoolId", schoolId,
                "fullName", "Jane Doe",
                "admissionNumber", "ADM-2",
                "gradeLevel", "1",
                "sectionName", "A",
                "phone", "9876500000"));
        Long id = ((Number) created.get("id")).longValue();
        jdbc.sql("DELETE FROM tenant_school.outbox_events WHERE aggregate_type = 'Student' AND aggregate_id = :id")
                .param("id", id.toString())
                .update();

        studentRepo.updateStudent(id, Map.of(
                "schoolId", schoolId,
                "fullName", "Jane Updated",
                "admissionNumber", "ADM-2",
                "classId", created.get("classId"),
                "sectionId", created.get("sectionId"),
                "phone", "9876500001"));

        var rows = jdbc.sql("""
                        SELECT event_type, payload FROM tenant_school.outbox_events
                        WHERE aggregate_type = 'Student' AND aggregate_id = :id
                        """)
                .param("id", id.toString())
                .query((rs, n) -> Map.of("eventType", rs.getString("event_type"), "payload", rs.getString("payload")))
                .list();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("eventType")).isEqualTo("student.upserted.v1");
        assertThat(rows.get(0).get("payload")).contains("Jane Updated");
    }

    @Test
    void reviewCampaignBulkCreatesItemsAndChunkedOutboxEvents() throws Exception {
        long schoolId = seedSchool(3, 2);
        Map<String, Object> placement = jdbc.sql("""
                        SELECT school_class_id, id
                        FROM tenant_school.school_sections
                        WHERE school_id = :schoolId
                        ORDER BY id
                        LIMIT 1
                        """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "classId", rs.getString("school_class_id"),
                        "sectionId", rs.getString("id")))
                .single();
        jdbc.sql("""
                        INSERT INTO student.students(
                            admission_no, roll_no, full_name, school_id, class_id, section_id,
                            academic_year_id, created_at, updated_at)
                        SELECT 'BULK-' || n, n::text, 'Bulk Student ' || n, :schoolId,
                               :classId, :sectionId, 'ay1', now(), now()
                        FROM generate_series(1, 520) AS n
                        """)
                .param("schoolId", schoolId)
                .param("classId", placement.get("classId"))
                .param("sectionId", placement.get("sectionId"))
                .update();

        Map<String, Object> status = studentRepo.initiateProfileVerification(Map.of(
                "schoolId", schoolId,
                "actorId", 42L,
                "dueDate", "2026-12-31"));

        assertThat(status).containsEntry("totalStudents", 520L)
                .containsEntry("pending", 520L);
        assertThat(jdbc.sql("SELECT count(*) FROM student.student_review_items WHERE school_id = :schoolId")
                .param("schoolId", schoolId)
                .query(Long.class)
                .single()).isEqualTo(520L);
        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM tenant_school.outbox_events
                        WHERE school_id = :schoolId
                          AND event_type = 'student-review-item.upserted.v1'
                        """)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single()).isEqualTo(520L);
        assertThat(jdbc.sql("""
                        SELECT count(DISTINCT aggregate_id)
                        FROM tenant_school.outbox_events
                        WHERE school_id = :schoolId
                          AND event_type = 'student-review-item.upserted.v1'
                        """)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single()).isEqualTo(520L);
    }

    @Test
    void profileEditInvalidatesOnlyTheActiveProfileVerification() throws Exception {
        long schoolId = seedSchool(3, 2);
        Map<String, Object> created = studentRepo.createStudent(Map.of(
                "schoolId", schoolId,
                "fullName", "Verified Student",
                "admissionNumber", "ADM-VERIFY-1",
                "gradeLevel", "1",
                "sectionName", "A",
                "phone", "9876500000"));
        Long id = ((Number) created.get("id")).longValue();
        String profileCampaign = java.util.UUID.randomUUID().toString();
        String photoCampaign = java.util.UUID.randomUUID().toString();
        String profileItem = java.util.UUID.randomUUID().toString();
        String photoItem = java.util.UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO student.student_review_campaigns
                    (id, school_id, review_type, title, status, initiated_at, created_at, updated_at)
                VALUES (:profileCampaign, :schoolId, 'PROFILE_VERIFICATION', 'Profile', 'ACTIVE', now(), now(), now()),
                       (:photoCampaign, :schoolId, 'PHOTO_VERIFICATION', 'Photo', 'ACTIVE', now(), now(), now())
                """)
                .param("profileCampaign", profileCampaign)
                .param("photoCampaign", photoCampaign)
                .param("schoolId", schoolId)
                .update();
        jdbc.sql("""
                INSERT INTO student.student_review_items
                    (id, campaign_id, student_id, school_id, status, verified_photo,
                     verified_full_name, verified_admission_no, verified_class_section,
                     verified_roll_no, verified_father_name, verified_father_contact,
                     verified_address, current_full_name, suggested_full_name, completed_at)
                VALUES (:profileItem, :profileCampaign, :studentId, :schoolId, 'COMPLETED', false,
                        true, true, true, true, true, true, true,
                        'Verified Student', 'Rejected Name', now()),
                       (:photoItem, :photoCampaign, :studentId, :schoolId, 'COMPLETED', true,
                        false, false, false, false, false, false, false,
                        'Verified Student', NULL, now())
                """)
                .param("profileItem", profileItem)
                .param("profileCampaign", profileCampaign)
                .param("photoItem", photoItem)
                .param("photoCampaign", photoCampaign)
                .param("studentId", id)
                .param("schoolId", schoolId)
                .update();
        jdbc.sql("DELETE FROM tenant_school.outbox_events").update();

        studentRepo.updateStudent(id, Map.of(
                "schoolId", schoolId,
                "fullName", "Updated Student",
                "admissionNumber", "ADM-VERIFY-1",
                "classId", created.get("classId"),
                "sectionId", created.get("sectionId"),
                "phone", "9876500000"));

        Map<String, Object> profile = jdbc.sql("""
                        SELECT status, verified_full_name, current_full_name, completed_at
                        FROM student.student_review_items WHERE id = :id
                        """)
                .param("id", profileItem)
                .query((rs, n) -> Map.<String, Object>of(
                        "status", rs.getString("status"),
                        "verified", rs.getBoolean("verified_full_name"),
                        "currentFullName", rs.getString("current_full_name"),
                        "completed", rs.getObject("completed_at") != null))
                .single();
        assertThat(profile).containsEntry("status", "PENDING")
                .containsEntry("verified", false)
                .containsEntry("currentFullName", "Updated Student")
                .containsEntry("completed", false);
        assertThat(jdbc.sql("SELECT suggested_full_name FROM student.student_review_items WHERE id = :id")
                .param("id", profileItem).query(String.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM student.student_review_items WHERE id = :id")
                .param("id", photoItem).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("""
                        SELECT aggregate_id || '|' || (payload->>'status')
                        FROM tenant_school.outbox_events
                        WHERE event_type = 'student-review-item.upserted.v1'
                        """).query(String.class).list())
                .containsExactly(profileItem + "|PENDING");

        Map<String, Object> listRow = ((java.util.List<Map<String, Object>>) studentRepo
                .workspaceStudents(schoolId, "All", "All", "All", 0, 50)
                .get("items")).get(0);
        assertThat(listRow).containsEntry("profileVerificationStatus", "PENDING")
                .containsEntry("photoVerificationStatus", "COMPLETED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void studentHistoryIncludesFeeAssignmentsAndPaymentsByAcademicYear() throws Exception {
        long schoolId = seedSchool(3, 2);
        Map<String, Object> created = studentRepo.createStudent(Map.of(
                "schoolId", schoolId,
                "fullName", "Fee Linked",
                "admissionNumber", "ADM-FEE-1",
                "gradeLevel", "1",
                "sectionName", "A",
                "phone", "9876500000"));
        Long id = ((Number) created.get("id")).longValue();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO fee.fee_bands(id, name, class_from, class_to, discount, academic_year_id, school_id) " +
                    "VALUES ('band-fee-history', 'Primary Fee', 1, 5, 0.0, 'ay1', " + schoolId + ")");
            st.execute("INSERT INTO fee.fee_items(id, name, frequency, amount, band_id, school_id) " +
                    "VALUES ('item-fee-history', 'Tuition', 'Annual', 500000, 'band-fee-history', " + schoolId + ")");
            st.execute("INSERT INTO fee.fee_assignments(id, schedule, band_discount, manual_discount, surcharge, " +
                    "net_payable, paid_amount, student_id, band_id, academic_year_id, version, school_id, assigned_at) " +
                    "VALUES ('assignment-fee-history', 'Annual', 0.0, 0.0, 0.0, 500000, 125000, " +
                    id + ", 'band-fee-history', 'ay1', 0, " + schoolId + ", now())");
            st.execute("INSERT INTO fee.payment_records(id, amount, mode, paid_at, receipt_number, student_id, assignment_id, version, school_id, created_at) " +
                    "VALUES ('payment-fee-history', 125000, 'Cash', now(), 'RCPT-FEE-1', " +
                    id + ", 'assignment-fee-history', 0, " + schoolId + ", now())");
            st.execute("UPDATE student.students SET photo_url = 'schools/demo/students/" + id + "/photos/photo.jpg' WHERE id = " + id);
            st.execute("INSERT INTO attendance.attendance_daily(id, attendance_date, total_enrolled, present_count, absent_count, locked, school_class_id, section_id, academic_year_id, school_id) " +
                    "VALUES ('daily-delete', DATE '2026-08-13', 2, 1, 1, false, 'c1', 's1', 'ay1', " + schoolId + ")");
            st.execute("INSERT INTO attendance.attendance_student_records(id, attendance_daily_id, student_id, school_id, attendance_date, academic_year_id, class_id, section_id, status) " +
                    "VALUES ('record-delete', 'daily-delete', " + id + ", " + schoolId + ", DATE '2026-08-13', 'ay1', 'c1', 's1', 'ABSENT'), " +
                    "('record-keep', 'daily-delete', 999999, " + schoolId + ", DATE '2026-08-13', 'ay1', 'c1', 's1', 'PRESENT')");
            st.execute("INSERT INTO attendance.absentee_notifications(id, school_id, student_id, class_id, section_id, academic_year_id, attendance_date, parent_contact, message, status) " +
                    "VALUES ('notification-delete', " + schoolId + ", " + id + ", 'c1', 's1', 'ay1', DATE '2026-08-13', '9876500000', 'Absent', 'SUPPRESSED')");
            st.execute("INSERT INTO student.student_review_campaigns(id, school_id, review_type, title, status) " +
                    "VALUES ('campaign-delete', " + schoolId + ", 'PROFILE_VERIFICATION', 'Delete test', 'ACTIVE')");
            st.execute("INSERT INTO student.student_review_items(id, campaign_id, student_id, school_id) " +
                    "VALUES ('review-delete', 'campaign-delete', " + id + ", " + schoolId + ")");
        }

        Map<String, Object> history = studentRepo.studentHistory(id);
        Map<String, Object> assignment = ((java.util.List<Map<String, Object>>) history.get("feeAssignments")).get(0);
        Map<String, Object> payment = ((java.util.List<Map<String, Object>>) history.get("feePayments")).get(0);
        Map<String, Object> detailFee = (Map<String, Object>) studentRepo.workspaceStudentDetail(id).get("fee");

        assertThat(assignment.get("academicYear")).isEqualTo("2025-26");
        assertThat(assignment.get("planName")).isEqualTo("Primary Fee");
        assertThat(assignment.get("netPayablePaise")).isEqualTo(500000L);
        assertThat(assignment.get("paidAmountPaise")).isEqualTo(125000L);
        assertThat(assignment.get("dueAmountPaise")).isEqualTo(375000L);
        assertThat(payment.get("amountPaise")).isEqualTo(125000L);
        assertThat(payment.get("receiptNumber")).isEqualTo("RCPT-FEE-1");
        assertThat(detailFee.get("assigned")).isEqualTo(true);
        assertThat(detailFee.get("dueAmountPaise")).isEqualTo(375000L);

        assertThatThrownBy(() -> studentRepo.deleteStudent(id, "WRONG-ADM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
        assertThat(jdbc.sql("SELECT count(*) FROM student.students WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(Long.class)
                .single()).isEqualTo(1L);

        Map<String, Object> deleted = studentRepo.deleteStudent(id, "ADM-FEE-1");

        assertThat(deleted).containsEntry("deleted", true).containsEntry("permanent", true);
        assertThat(jdbc.sql("SELECT count(*) FROM student.students WHERE id = :id")
                .param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM fee.fee_assignments WHERE student_id = :id")
                .param("id", id)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM fee.payment_records WHERE student_id = :id")
                .param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM student.student_enrollments WHERE student_id = :id")
                .param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM student.student_review_items WHERE student_id = :id")
                .param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM attendance.attendance_student_records WHERE student_id = :id")
                .param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM attendance.absentee_notifications WHERE student_id = :id")
                .param("id", id).query(Long.class).single()).isZero();
        Map<String, Object> daily = jdbc.sql("""
                        SELECT total_enrolled, present_count, absent_count
                        FROM attendance.attendance_daily WHERE id = 'daily-delete'
                        """)
                .query((rs, n) -> Map.<String, Object>of(
                        "total", rs.getInt("total_enrolled"),
                        "present", rs.getInt("present_count"),
                        "absent", rs.getInt("absent_count")))
                .single();
        assertThat(daily).containsEntry("total", 1).containsEntry("present", 1).containsEntry("absent", 0);
        Map<String, Object> deletionEvent = jdbc.sql("""
                        SELECT event_type, payload::text AS payload
                        FROM tenant_school.outbox_events
                        WHERE aggregate_type = 'Student' AND aggregate_id = :id
                          AND event_type = 'student.deleted.v1'
                        """)
                .param("id", id.toString())
                .query((rs, n) -> Map.<String, Object>of(
                        "eventType", rs.getString("event_type"),
                        "payload", rs.getString("payload")))
                .single();
        assertThat(deletionEvent.get("payload").toString())
                .contains("\"id\"")
                .doesNotContain("ADM-FEE-1", "Fee Linked");
        org.mockito.Mockito.verify(photoStorage)
                .deleteStoredPhoto("schools/demo/students/" + id + "/photos/photo.jpg");
    }
}

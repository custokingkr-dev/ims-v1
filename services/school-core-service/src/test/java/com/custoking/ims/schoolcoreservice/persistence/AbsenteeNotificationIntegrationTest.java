package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * {@code GET /attendance/absentees} and {@code POST /attendance/absentees/notify} at the
 * repository layer, against the real {@code attendance.absentee_notifications} table.
 *
 * <p>Runs as the table owner (RLS-exempt). The school-scope assertions below exercise the
 * {@code AND ar.school_id = :schoolId} predicate only; they do not prove RLS isolation.</p>
 */
class AbsenteeNotificationIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource ds;
    static JdbcClient jdbc;
    static AttendanceReadRepository repo;
    static final LocalDate DAY = LocalDate.parse("2026-04-06");

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        for (String schema : new String[] {"tenant_school", "student", "attendance"}) {
            Flyway.configure()
                    .dataSource(PG.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load().migrate();
        }
        ds = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(ds);
        StudentPhotoStorage photo = Mockito.mock(StudentPhotoStorage.class);
        Mockito.when(photo.toDisplayUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        OutboxWriter outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        repo = new AttendanceReadRepository(jdbc, photo, outbox, "attendance");
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    /**
     * School 1: class c1 / section s1 holds Asha (1, notifiable), Bala (2, no guardian) and
     * Chitra (3, guardian without a phone); class c2 / section s2 holds Dev (4, notifiable).
     * School 2: class c1 / section o1 holds Esha (5, notifiable).
     * All five are marked ABSENT on {@link #DAY}.
     */
    @BeforeEach
    void seed() throws Exception {
        AcademicCalendar.AcademicYear academicYear =
                AcademicCalendar.currentAcademicYear(AcademicCalendar.DEFAULT_ACADEMIC_YEAR_START_MONTH);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM attendance.absentee_notifications");
            st.execute("DELETE FROM attendance.attendance_student_records");
            st.execute("DELETE FROM attendance.attendance_daily");
            st.execute("DELETE FROM student.student_consent_events");
            st.execute("DELETE FROM student.student_guardians");
            st.execute("DELETE FROM student.guardians");
            st.execute("DELETE FROM student.students");
            st.execute("DELETE FROM tenant_school.outbox_events");
            st.execute("DELETE FROM tenant_school.school_sections");
            st.execute("DELETE FROM tenant_school.school_classes");
            st.execute("DELETE FROM tenant_school.schools");
            st.execute("DELETE FROM tenant_school.academic_years");

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tenant_school.academic_years(id, label, active) VALUES (?, ?, true)")) {
                ps.setString(1, academicYear.id());
                ps.setString(2, academicYear.label());
                ps.executeUpdate();
            }
            st.execute("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) VALUES " +
                    "(1,'Test School','TST',true, now()), (2,'Other School','OTH',true, now())");
            st.execute("INSERT INTO tenant_school.school_classes(id, name, sort_order) VALUES " +
                    "('c1','Class 1',1), ('c2','Class 2',2)");
            st.execute("INSERT INTO tenant_school.school_sections(id, name, teacher_name, active, school_class_id, school_id) VALUES " +
                    "('s1','A','Ms Rao',true,'c1',1), ('s2','A','Ms Das',true,'c2',1), ('o1','A','Mr Other',true,'c1',2)");
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO student.students
                        (id, admission_no, roll_no, full_name, school_id, class_id, section_id, academic_year_id,
                         father_contact, phone)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                Object[][] rows = {
                        {1L, "ADM1", "1", "Asha", 1L, "c1", "s1", "919000000001", "919000000001"},
                        {2L, "ADM2", "2", "Bala", 1L, "c1", "s1", "", ""},
                        {3L, "ADM3", "3", "Chitra", 1L, "c1", "s1", "", ""},
                        {4L, "ADM4", "1", "Dev", 1L, "c2", "s2", "919000000004", "919000000004"},
                        {5L, "ADM5", "1", "Esha", 2L, "c1", "o1", "919000000005", "919000000005"},
                };
                for (Object[] r : rows) {
                    ps.setLong(1, (Long) r[0]);
                    ps.setString(2, (String) r[1]);
                    ps.setString(3, (String) r[2]);
                    ps.setString(4, (String) r[3]);
                    ps.setLong(5, (Long) r[4]);
                    ps.setString(6, (String) r[5]);
                    ps.setString(7, (String) r[6]);
                    ps.setString(8, academicYear.id());
                    ps.setString(9, (String) r[7]);
                    ps.setString(10, (String) r[8]);
                    ps.executeUpdate();
                }
            }
        }
        consentedGuardian(1L, 1L, "919000000001");
        consentedGuardian(3L, 1L, null);            // guardian, consent, but no phone
        consentedGuardian(4L, 1L, "919000000004");
        consentedGuardian(5L, 2L, "919000000005");

        mark(DAY, "c1", "s1", 1, "ABSENT", 2, "ABSENT", 3, "ABSENT");
        mark(DAY, "c2", "s2", 4, "ABSENT");
        mark(DAY, "c1", "o1", 5, "ABSENT");
    }

    // ── idempotency ─────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void notify_twice_queuesOnceAndTheSecondCallIsAHarmlessNoOp() {
        Map<String, Object> first = repo.notifyAbsentees(DAY, null, null, 1L, 42L);
        assertThat(first).containsEntry("date", "2026-04-06")
                .containsEntry("queued", 2)                 // Asha + Dev
                .containsEntry("skippedNoContact", 1)       // Chitra: DESTINATION_MISSING
                .containsEntry("skippedAlreadyQueued", 0)
                .containsEntry("suppressedCount", 2);       // Bala + Chitra
        assertThat((List<Map<String, Object>>) first.get("suppressed"))
                .extracting(s -> s.get("studentId") + ":" + s.get("reason"))
                .containsExactlyInAnyOrder("2:NO_PRIMARY_GUARDIAN", "3:DESTINATION_MISSING");
        assertThat(queuedStudentIds()).containsExactly(1L, 4L);

        Map<String, Object> second = repo.notifyAbsentees(DAY, null, null, 1L, 42L);
        assertThat(second).containsEntry("queued", 0)
                .containsEntry("skippedAlreadyQueued", 2)
                .containsEntry("skippedNoContact", 1)
                .containsEntry("suppressedCount", 2);
        assertThat(queuedStudentIds()).as("no duplicate rows").containsExactly(1L, 4L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM attendance.absentee_notifications").query(Long.class).single())
                .isEqualTo(2L);

        // Third time, from a different actor, still nothing new and no exception.
        assertThatCode(() -> repo.notifyAbsentees(DAY, null, null, 1L, 77L)).doesNotThrowAnyException();
        assertThat(jdbc.sql("SELECT DISTINCT queued_by FROM attendance.absentee_notifications").query(Long.class).list())
                .containsExactly(42L);
    }

    @Test
    void notify_uniqueKeyOnStudentAndDate_isEnforcedByTheTable() {
        repo.notifyAbsentees(DAY, null, null, 1L, 42L);
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO attendance.absentee_notifications
                            (id, school_id, student_id, class_id, section_id, academic_year_id,
                             attendance_date, parent_contact, message, status)
                        VALUES ('dup', 1, 1, 'c1', 's1', 'y', :date, '919000000001', 'dup', 'SUPPRESSED')
                        """).param("date", DAY).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_absentee_notification");
        // The same student on another day is a different notification.
        assertThatCode(() -> jdbc.sql("""
                        INSERT INTO attendance.absentee_notifications
                            (id, school_id, student_id, class_id, section_id, academic_year_id,
                             attendance_date, parent_contact, message, status)
                        VALUES ('next-day', 1, 1, 'c1', 's1', 'y', :date, '919000000001', 'ok', 'SUPPRESSED')
                        """).param("date", DAY.plusDays(1)).update())
                .doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void notify_anyExistingRowForTheStudentAndDate_blocksRequeueRegardlessOfStatus() {
        // A row quarantined by the V9 migration (or a delivered/failed one) still counts as queued.
        jdbc.sql("""
                INSERT INTO attendance.absentee_notifications
                    (id, school_id, student_id, class_id, section_id, academic_year_id,
                     attendance_date, parent_contact, message, status, queued_by)
                VALUES ('legacy', 1, 1, 'c1', 's1', 'y', :date, '919000000001', 'legacy', 'SUPPRESSED', 7)
                """).param("date", DAY).update();

        List<Map<String, Object>> absentees = (List<Map<String, Object>>) repo.absentees(DAY, "c1", "s1", 1L).get("students");
        assertThat(absentees).filteredOn(s -> Long.valueOf(1L).equals(s.get("studentId")))
                .singleElement().satisfies(asha -> assertThat(asha).containsEntry("alreadyQueued", true));

        Map<String, Object> result = repo.notifyAbsentees(DAY, "c1", "s1", 1L, 42L);
        assertThat(result).containsEntry("queued", 0).containsEntry("skippedAlreadyQueued", 1);
        assertThat(jdbc.sql("SELECT status, queued_by FROM attendance.absentee_notifications WHERE student_id = 1")
                .query((rs, n) -> rs.getString(1) + ":" + rs.getLong(2)).list())
                .containsExactly("SUPPRESSED:7");
    }

    // ── filters ─────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void classFilter_worksWithoutASectionFilter() {
        Map<String, Object> c1 = repo.absentees(DAY, "c1", null, 1L);
        assertThat(c1).containsEntry("totalAbsent", 3).containsEntry("queuedCount", 0L);
        assertThat((List<Map<String, Object>>) c1.get("students"))
                .extracting(s -> s.get("studentId")).containsExactly(1L, 2L, 3L);

        Map<String, Object> c2 = repo.absentees(DAY, "c2", null, 1L);
        assertThat((List<Map<String, Object>>) c2.get("students"))
                .extracting(s -> s.get("studentId")).containsExactly(4L);

        Map<String, Object> notified = repo.notifyAbsentees(DAY, "c1", null, 1L, 42L);
        assertThat(notified).containsEntry("queued", 1);          // Asha only
        assertThat(queuedStudentIds()).containsExactly(1L);       // Dev (c2) untouched

        Map<String, Object> c2After = repo.absentees(DAY, "c2", null, 1L);
        assertThat(c2After).containsEntry("queuedCount", 0L);
        assertThat(((List<Map<String, Object>>) c2After.get("students")).getFirst())
                .containsEntry("alreadyQueued", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sectionFilter_worksWithoutAClassFilter_andBlankFiltersMeanAll() {
        Map<String, Object> s2 = repo.absentees(DAY, null, "s2", 1L);
        assertThat((List<Map<String, Object>>) s2.get("students"))
                .extracting(s -> s.get("studentId")).containsExactly(4L);

        Map<String, Object> blank = repo.absentees(DAY, "", "  ", 1L);
        assertThat(blank).containsEntry("totalAbsent", 4);        // whole school, not the other school

        assertThat(repo.notifyAbsentees(DAY, null, "s2", 1L, 42L)).containsEntry("queued", 1);
        assertThat(queuedStudentIds()).containsExactly(4L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void absentees_onlyListsAbsentStudents_withContactAndClassSectionLabel() {
        mark(DAY, "c1", "s1", 2, "LATE");                          // Bala becomes LATE
        Map<String, Object> result = repo.absentees(DAY, "c1", "s1", 1L);
        List<Map<String, Object>> students = (List<Map<String, Object>>) result.get("students");
        assertThat(students).extracting(s -> s.get("studentId")).containsExactly(1L, 3L);
        assertThat(students.getFirst())
                .containsEntry("fullName", "Asha").containsEntry("admissionNo", "ADM1")
                .containsEntry("classSection", "Class 1-A").containsEntry("status", "ABSENT")
                .containsEntry("parentContact", "919000000001").containsEntry("hasContact", true)
                .containsEntry("alreadyQueued", false).containsEntry("schoolId", 1L);
        assertThat(students.get(1)).containsEntry("hasContact", false);
        assertThat(result).containsEntry("date", "2026-04-06").containsEntry("sectionId", "s1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schoolScope_restrictsBothReadAndNotify_toTheRequestedSchool() {
        assertThat((List<Map<String, Object>>) repo.absentees(DAY, null, null, 2L).get("students"))
                .extracting(s -> s.get("studentId")).containsExactly(5L);

        assertThat(repo.notifyAbsentees(DAY, null, null, 2L, 42L)).containsEntry("queued", 1);
        assertThat(queuedStudentIds()).containsExactly(5L);
        assertThat(jdbc.sql("SELECT school_id FROM attendance.absentee_notifications").query(Long.class).list())
                .containsExactly(2L);
    }

    // ── what gets written ───────────────────────────────────────────────────────

    @Test
    void notify_stampsTheActorItIsGivenAndTheV2PolicyEvidence() {
        repo.notifyAbsentees(DAY, "c1", "s1", 1L, 42L);
        Map<String, Object> row = jdbc.sql("""
                        SELECT school_id, class_id, section_id, academic_year_id, attendance_date, parent_contact,
                               channel, status, queued_by, guardian_id, consent_event_id, consent_notice_version,
                               policy_version, policy_decision, destination_sha256, message
                        FROM attendance.absentee_notifications WHERE student_id = 1
                        """)
                .query((rs, n) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("schoolId", rs.getLong("school_id"));
                    m.put("classId", rs.getString("class_id"));
                    m.put("sectionId", rs.getString("section_id"));
                    m.put("academicYearId", rs.getString("academic_year_id"));
                    m.put("date", rs.getObject("attendance_date", LocalDate.class));
                    m.put("parentContact", rs.getString("parent_contact"));
                    m.put("channel", rs.getString("channel"));
                    m.put("status", rs.getString("status"));
                    m.put("queuedBy", rs.getObject("queued_by", Long.class));
                    m.put("guardianId", rs.getString("guardian_id"));
                    m.put("consentEventId", rs.getString("consent_event_id"));
                    m.put("consentNoticeVersion", rs.getString("consent_notice_version"));
                    m.put("policyVersion", rs.getString("policy_version"));
                    m.put("policyDecision", rs.getString("policy_decision"));
                    m.put("destinationSha256", rs.getString("destination_sha256"));
                    m.put("message", rs.getString("message"));
                    return m;
                })
                .single();
        assertThat(row).containsEntry("schoolId", 1L).containsEntry("classId", "c1").containsEntry("sectionId", "s1")
                .containsEntry("academicYearId", AcademicCalendar.currentAcademicYearId(jdbc, 1L))
                .containsEntry("date", DAY)
                .containsEntry("parentContact", "919000000001")
                .containsEntry("channel", "WHATSAPP").containsEntry("status", "QUEUED")
                .containsEntry("queuedBy", 42L)
                .containsEntry("guardianId", "guardian-1").containsEntry("consentEventId", "consent-1")
                .containsEntry("consentNoticeVersion", "notice-v1")
                .containsEntry("policyVersion", "guardian-communications.v2")
                .containsEntry("policyDecision", "ALLOW")
                .containsEntry("destinationSha256", GuardianCommunicationPolicy.destinationSha256("WHATSAPP", "919000000001"));
        assertThat(String.valueOf(row.get("message")))
                .startsWith("Dear Parent, Asha (Class 1-A) was marked absent on ")
                .contains(" 2026 at Test School.");
    }

    @Test
    void notify_withNoActor_leavesQueuedByNull_ratherThanFailing() {
        assertThat(repo.notifyAbsentees(DAY, "c1", "s1", 1L, null)).containsEntry("queued", 1);
        assertThat(jdbc.sql("SELECT queued_by IS NULL FROM attendance.absentee_notifications WHERE student_id = 1")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void notify_onADayWithNoAbsentees_isAnEmptyResultNotAnError() {
        Map<String, Object> result = repo.notifyAbsentees(DAY.plusDays(1), null, null, 1L, 42L);
        assertThat(result).containsEntry("queued", 0).containsEntry("skippedNoContact", 0)
                .containsEntry("skippedAlreadyQueued", 0).containsEntry("suppressedCount", 0);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static void consentedGuardian(long studentId, long schoolId, String phone) {
        String guardianId = "guardian-" + studentId;
        jdbc.sql("""
                INSERT INTO student.guardians (id, school_id, full_name, phone, contact_verified_at, status)
                VALUES (:id, :schoolId, :name, :phone, now(), 'ACTIVE')
                """)
                .param("id", guardianId).param("schoolId", schoolId)
                .param("name", "Guardian " + studentId).param("phone", phone)
                .update();
        jdbc.sql("""
                INSERT INTO student.student_guardians
                    (id, school_id, student_id, guardian_id, relationship, is_primary, receives_notifications)
                VALUES (:id, :schoolId, :studentId, :guardianId, 'FATHER', TRUE, TRUE)
                """)
                .param("id", "link-" + studentId).param("schoolId", schoolId)
                .param("studentId", studentId).param("guardianId", guardianId)
                .update();
        jdbc.sql("""
                INSERT INTO student.student_consent_events
                    (id, school_id, student_id, guardian_id, purpose, status, notice_version, evidence_source)
                VALUES (:id, :schoolId, :studentId, :guardianId, 'SCHOOL_COMMUNICATIONS', 'GRANTED',
                        'notice-v1', 'SIGNED_FORM')
                """)
                .param("id", "consent-" + studentId).param("schoolId", schoolId)
                .param("studentId", studentId).param("guardianId", guardianId)
                .update();
    }

    /** {@code mark(date, classId, sectionId, studentId, status, studentId, status, ...)} */
    private static void mark(LocalDate date, String classId, String sectionId, Object... pairs) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            records.add(Map.of("studentId", pairs[i], "status", pairs[i + 1], "remarks", ""));
        }
        repo.saveSectionRegister(Map.of(
                "date", date.toString(), "classId", classId, "sectionId", sectionId,
                "records", records));
    }

    private static List<Long> queuedStudentIds() {
        return jdbc.sql("SELECT student_id FROM attendance.absentee_notifications WHERE status = 'QUEUED' ORDER BY student_id")
                .query(Long.class).list();
    }
}

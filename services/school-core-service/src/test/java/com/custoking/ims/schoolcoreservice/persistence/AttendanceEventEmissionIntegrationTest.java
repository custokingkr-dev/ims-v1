package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * Proves that attendance mutations (Reporting Decoupling SP3) emit
 * {@code attendance-daily.upserted.v1} to the shared {@code tenant_school.outbox_events} table
 * in the SAME transaction as the {@code attendance.attendance_daily} write, mirroring the SP1
 * {@code ReferenceEventEmissionIntegrationTest} pattern for school-core reference events.
 */
class AttendanceEventEmissionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource ds;
    static AttendanceReadRepository repo;
    static final LocalDate DAY = LocalDate.parse("2024-03-04");

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
        StudentPhotoStorage photo = Mockito.mock(StudentPhotoStorage.class);
        Mockito.when(photo.toDisplayUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        OutboxWriter outbox = new OutboxWriter(JdbcClient.create(ds), new ObjectMapper(), "tenant_school");
        repo = new AttendanceReadRepository(JdbcClient.create(ds), photo, outbox, "attendance");
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void seed() throws Exception {
        AcademicCalendar.AcademicYear academicYear =
                AcademicCalendar.currentAcademicYear(AcademicCalendar.DEFAULT_ACADEMIC_YEAR_START_MONTH);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM attendance.attendance_student_records");
            st.execute("DELETE FROM attendance.attendance_daily");
            st.execute("DELETE FROM tenant_school.outbox_events");
            st.execute("DELETE FROM student.students");
            st.execute("DELETE FROM tenant_school.school_sections");
            st.execute("DELETE FROM tenant_school.school_classes");
            st.execute("DELETE FROM tenant_school.schools");
            st.execute("DELETE FROM tenant_school.academic_years");

            st.execute("INSERT INTO tenant_school.academic_years(id, label, active) VALUES ('"
                    + academicYear.id() + "','" + academicYear.label() + "',true)");
            st.execute("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) " +
                    "VALUES (1,'Test School','TST',true, now())");
            st.execute("INSERT INTO tenant_school.school_classes(id, name, sort_order) VALUES ('c1','Class 1',1)");
            st.execute("INSERT INTO tenant_school.school_sections(id, name, teacher_name, active, school_class_id, school_id) " +
                    "VALUES ('s1','A','Ms Rao',true,'c1',1)");
            for (int i = 1; i <= 4; i++) {
                st.execute("INSERT INTO student.students" +
                        "(id, admission_no, roll_no, full_name, school_id, class_id, section_id, academic_year_id) VALUES " +
                        "(" + i + ",'ADM" + i + "','" + i + "','Student " + i + "',1,'c1','s1','"
                                + academicYear.id() + "')");
            }
        }
    }

    private long countOutbox() {
        return JdbcClient.create(ds).sql("SELECT count(*) FROM tenant_school.outbox_events "
                        + "WHERE event_type = 'attendance-daily.upserted.v1'")
                .query(Long.class)
                .single();
    }

    @Test
    void saveSectionRegisterEmitsAttendanceDailyUpsertedInSameTransaction() {
        String academicYearId = AcademicCalendar.currentAcademicYearId(JdbcClient.create(ds), 1L);
        Map<String, Object> result = repo.saveSectionRegister(Map.of(
                "date", DAY.toString(), "classId", "c1", "sectionId", "s1", "schoolId", 1,
                "records", List.of(
                        Map.of("studentId", 1, "status", "PRESENT", "remarks", ""),
                        Map.of("studentId", 2, "status", "LATE", "remarks", ""),
                        Map.of("studentId", 3, "status", "LEAVE", "remarks", ""),
                        Map.of("studentId", 4, "status", "ABSENT", "remarks", ""))));
        assertThat(result).isNotNull();

        Map<String, Object> row = JdbcClient.create(ds).sql("""
                        SELECT event_type, event_key, aggregate_type, aggregate_id, school_id, payload::text AS payload
                        FROM tenant_school.outbox_events
                        WHERE event_type = 'attendance-daily.upserted.v1'
                        ORDER BY id DESC LIMIT 1
                        """)
                .query((rs, n) -> Map.<String, Object>of(
                        "eventType", rs.getString("event_type"),
                        "eventKey", rs.getString("event_key"),
                        "aggregateType", rs.getString("aggregate_type"),
                        "aggregateId", rs.getString("aggregate_id"),
                        "schoolId", rs.getLong("school_id"),
                        "payload", rs.getString("payload")))
                .single();

        assertThat(row.get("eventType")).isEqualTo("attendance-daily.upserted.v1");
        assertThat(row.get("aggregateType")).isEqualTo("AttendanceDaily");
        assertThat(row.get("eventKey")).isEqualTo("AttendanceDailyUpserted:" + row.get("aggregateId"));
        assertThat(row.get("schoolId")).isEqualTo(1L);
        String payload = String.valueOf(row.get("payload"));
        assertThat(payload).contains("\"sectionId\": \"s1\"")
                .contains("\"classId\": \"c1\"")
                .contains("\"academicYearId\": \"" + academicYearId + "\"")
                .contains("\"presentCount\": 1")
                .contains("\"lateCount\": 1")
                .contains("\"leaveCount\": 1")
                .contains("\"absentCount\": 1")
                .contains("\"totalEnrolled\": 4");
    }

    @Test
    void submitAttendanceSectionEmitsAttendanceDailyUpsertedEvent() {
        long before = countOutbox();
        repo.submitAttendanceSection(Map.of(
                "date", DAY.toString(), "classId", "c1", "sectionId", "s1", "schoolId", 1));

        assertThat(countOutbox()).isEqualTo(before + 1);
    }
}

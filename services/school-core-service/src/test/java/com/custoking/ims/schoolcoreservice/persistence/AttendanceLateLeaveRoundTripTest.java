package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

class AttendanceLateLeaveRoundTripTest {

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
        repo = new AttendanceReadRepository(JdbcClient.create(ds), photo, "attendance");
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM attendance.attendance_student_records");
            st.execute("DELETE FROM attendance.attendance_daily");
            st.execute("DELETE FROM student.students");
            st.execute("DELETE FROM tenant_school.school_sections");
            st.execute("DELETE FROM tenant_school.school_classes");
            st.execute("DELETE FROM tenant_school.schools");
            st.execute("DELETE FROM tenant_school.academic_years");

            st.execute("INSERT INTO tenant_school.academic_years(id, label, active) VALUES ('AY','2024-25',true)");
            st.execute("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) " +
                    "VALUES (1,'Test School','TST',true, now())");
            st.execute("INSERT INTO tenant_school.school_classes(id, name, sort_order) VALUES ('c1','Class 1',1)");
            st.execute("INSERT INTO tenant_school.school_sections(id, name, teacher_name, active, school_class_id, school_id) " +
                    "VALUES ('s1','A','Ms Rao',true,'c1',1)");
            // 4 students in c1/s1 — one for each status.
            for (int i = 1; i <= 4; i++) {
                st.execute("INSERT INTO student.students" +
                        "(id, admission_no, roll_no, full_name, school_id, class_id, section_id, academic_year_id) VALUES " +
                        "(" + i + ",'ADM" + i + "','" + i + "','Student " + i + "',1,'c1','s1','AY')");
            }
        }
    }

    @Test
    void saveThenRead_computesBuckets_andWritesLateLeaveToDaily() {
        // P, Late, Leave, Absent — one each.
        repo.saveSectionRegister(Map.of(
                "date", DAY.toString(), "classId", "c1", "sectionId", "s1", "schoolId", 1,
                "records", List.of(
                        Map.of("studentId", 1, "status", "PRESENT", "remarks", ""),
                        Map.of("studentId", 2, "status", "LATE", "remarks", "bus late"),
                        Map.of("studentId", 3, "status", "LEAVE", "remarks", "sick"),
                        Map.of("studentId", 4, "status", "ABSENT", "remarks", ""))));

        Map<String, Object> reg = repo.sectionRegister(DAY, "c1", "s1", 1L);
        assertThat(reg.get("presentCount")).isEqualTo(1);
        assertThat(reg.get("lateCount")).isEqualTo(1);
        assertThat(reg.get("leaveCount")).isEqualTo(1);
        assertThat(reg.get("absentCount")).isEqualTo(1);
        assertThat(reg.get("totalStudents")).isEqualTo(4);
        // attended (P+Late)=2 / denom (P+Late+Absent)=3 -> 66.7; Leave excluded.
        assertThat(((Number) reg.get("presentPercent")).doubleValue()).isEqualTo(66.7);

        // late_count / leave_count persisted on the day rollup.
        Map<String, Object> daily = JdbcClient.create(ds).sql(
                "SELECT present_count, absent_count, late_count, leave_count FROM attendance.attendance_daily " +
                "WHERE section_id = 's1' AND attendance_date = :d AND academic_year_id = 'AY'")
                .param("d", DAY)
                .query((rs, n) -> Map.<String, Object>of(
                        "present", rs.getInt("present_count"),
                        "absent", rs.getInt("absent_count"),
                        "late", rs.getInt("late_count"),
                        "leave", rs.getInt("leave_count")))
                .single();
        assertThat(daily).containsEntry("present", 1).containsEntry("absent", 1)
                .containsEntry("late", 1).containsEntry("leave", 1);
    }

    @Test
    void dailySummary_surfacesBucketsAndAggregatePercent() {
        repo.saveSectionRegister(Map.of(
                "date", DAY.toString(), "classId", "c1", "sectionId", "s1", "schoolId", 1,
                "records", List.of(
                        Map.of("studentId", 1, "status", "PRESENT", "remarks", ""),
                        Map.of("studentId", 2, "status", "LATE", "remarks", ""),
                        Map.of("studentId", 3, "status", "LEAVE", "remarks", ""),
                        Map.of("studentId", 4, "status", "ABSENT", "remarks", ""))));

        Map<String, Object> summary = repo.dailySummary(DAY, 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) summary.get("sections");
        Map<String, Object> s1 = sections.stream()
                .filter(s -> "s1".equals(s.get("sectionId"))).findFirst().orElseThrow();
        assertThat(s1.get("lateCount")).isEqualTo(1);
        assertThat(s1.get("leaveCount")).isEqualTo(1);
        assertThat(((Number) s1.get("presentPercent")).doubleValue()).isEqualTo(66.7);
        assertThat(((Number) summary.get("overallPercent")).doubleValue()).isEqualTo(66.7);
    }

    @Test
    void unknownStatus_isRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repo.saveSectionRegister(Map.of(
                "date", DAY.toString(), "classId", "c1", "sectionId", "s1", "schoolId", 1,
                "records", List.of(Map.of("studentId", 1, "status", "HALF_DAY", "remarks", "")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid attendance status");
    }
}

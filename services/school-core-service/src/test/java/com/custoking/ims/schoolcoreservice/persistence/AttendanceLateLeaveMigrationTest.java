package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.*;

class AttendanceLateLeaveMigrationTest {

    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("attendance").defaultSchema("attendance")
                .locations("classpath:db/migration/attendance")
                .load().migrate();
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    private void seedDaily(Statement st) throws SQLException {
        st.execute("INSERT INTO attendance.attendance_daily " +
                "(id, attendance_date, total_enrolled, present_count, absent_count, locked, " +
                " school_class_id, section_id, academic_year_id, school_id) VALUES " +
                "('d-late','2024-02-01',4,1,1,false,'c1','s1','y1',10)");
    }

    @Test
    void lateLeaveColumnsExistAndDefaultToZero() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            seedDaily(st);
            try (ResultSet rs = st.executeQuery(
                    "SELECT late_count, leave_count FROM attendance.attendance_daily WHERE id = 'd-late'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("late_count")).isZero();
                assertThat(rs.getInt("leave_count")).isZero();
            }
            st.execute("DELETE FROM attendance.attendance_daily WHERE id = 'd-late'");
        }
    }

    @Test
    void statusCheckAcceptsAllFourValues() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            seedDaily(st);
            for (String status : new String[] {"PRESENT", "ABSENT", "LATE", "LEAVE"}) {
                st.execute("INSERT INTO attendance.attendance_student_records " +
                        "(id, attendance_daily_id, student_id, school_id, attendance_date, " +
                        " academic_year_id, class_id, section_id, status) VALUES " +
                        "('r-" + status + "','d-late'," + status.hashCode() + ",10,'2024-02-01','y1','c1','s1','" + status + "')");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM attendance.attendance_student_records WHERE attendance_daily_id = 'd-late'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(4);
            }
            st.execute("DELETE FROM attendance.attendance_student_records WHERE attendance_daily_id = 'd-late'");
            st.execute("DELETE FROM attendance.attendance_daily WHERE id = 'd-late'");
        }
    }

    @Test
    void statusCheckRejectsUnknownValue() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            seedDaily(st);
            assertThatThrownBy(() -> st.execute("INSERT INTO attendance.attendance_student_records " +
                    "(id, attendance_daily_id, student_id, school_id, attendance_date, " +
                    " academic_year_id, class_id, section_id, status) VALUES " +
                    "('r-bad','d-late',777,10,'2024-02-01','y1','c1','s1','HALF_DAY')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("attendance_student_records_status_check");
            st.execute("DELETE FROM attendance.attendance_daily WHERE id = 'd-late'");
        }
    }
}

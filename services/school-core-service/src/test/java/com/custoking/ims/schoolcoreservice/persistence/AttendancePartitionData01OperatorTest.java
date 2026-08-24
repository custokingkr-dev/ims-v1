package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttendancePartitionData01OperatorTest {

    private static final List<String> FORWARD = List.of(
            "00_preflight.sql", "10_freeze.sql", "20_build.sql",
            "30_verify.sql", "40_cutover.sql");

    PostgreSQLContainer<?> postgres;
    Path operatorSql;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        operatorSql = findOperatorSql();
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner");
        postgres.start();

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "owner", "owner")
                .schemas("attendance")
                .defaultSchema("attendance")
                .locations("classpath:db/migration/attendance")
                .load()
                .migrate();

        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA attendance TO app_rt");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON attendance.attendance_student_records TO app_rt");
            statement.execute("INSERT INTO attendance.attendance_daily " +
                    "(id, attendance_date, total_enrolled, present_count, absent_count, locked, " +
                    " school_class_id, section_id, academic_year_id, school_id) VALUES " +
                    "('d23','2023-06-01',1,1,0,false,'c1','s1','y23',10)," +
                    "('d24','2024-06-01',2,1,1,false,'c1','s1','y24',10)," +
                    "('d25','2025-06-01',1,1,0,false,'c2','s2','y25',20)," +
                    "('d27','2027-06-01',1,1,0,false,'c1','s1','y27',10)");
            statement.execute("INSERT INTO attendance.attendance_student_records " +
                    "(id, attendance_daily_id, student_id, school_id, attendance_date, " +
                    " academic_year_id, class_id, section_id, status, remarks) VALUES " +
                    "('r23','d23',101,10,'2023-06-01','y23','c1','s1','PRESENT','a')," +
                    "('r24','d24',101,10,'2024-06-01','y24','c1','s1','ABSENT','b')," +
                    "('r25','d25',201,20,'2025-06-01','y25','c2','s2','LATE','c')");
        }

        for (String file : List.of("00_preflight.sql", "10_freeze.sql", "20_build.sql",
                "30_verify.sql", "40_cutover.sql", "50_finalize.sql",
                "90_rollback_before_resume.sql")) {
            postgres.copyFileToContainer(MountableFile.forHostPath(operatorSql.resolve(file)), "/tmp/" + file);
        }
    }

    @AfterEach
    void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void fullFlywayV1ToV9ThenData01PreservesSchemaRlsAndGlobalUniqueness() throws Exception {
        for (String script : FORWARD) {
            assertPsqlSuccess(script, maintenanceOptions());
        }

        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            assertThat(singleString(statement,
                    "SELECT relkind::text FROM pg_class WHERE oid=" +
                            "'attendance.attendance_student_records'::regclass")).isEqualTo("p");
            assertThat(singleLong(statement,
                    "SELECT count(*) FROM attendance.attendance_student_records")).isEqualTo(3);
            assertThat(singleLong(statement,
                    "SELECT count(*) FROM attendance.attendance_student_record_identity")).isEqualTo(3);

            // Exact AttendanceReadRepository contract: every repeat save proposes a fresh UUID,
            // then upserts on (student_id, attendance_date, academic_year_id).
            statement.execute("INSERT INTO attendance.attendance_student_records " +
                    "(id, attendance_daily_id, student_id, school_id, attendance_date, academic_year_id, " +
                    " class_id, section_id, status) VALUES " +
                    "('fresh-request-uuid','d24',101,10,'2024-06-01','y24','c1','s1','LEAVE') " +
                    "ON CONFLICT (student_id, attendance_date, academic_year_id) DO UPDATE SET " +
                    "status=EXCLUDED.status");
            assertThat(singleString(statement,
                    "SELECT id FROM attendance.attendance_student_records " +
                            "WHERE student_id=101 AND attendance_date='2024-06-01'"))
                    .isEqualTo("r24");
            assertThat(singleString(statement,
                    "SELECT status FROM attendance.attendance_student_records WHERE id='r24'"))
                    .isEqualTo("LEAVE");
            assertThat(singleLong(statement,
                    "SELECT count(*) FROM attendance.attendance_student_record_identity")).isEqualTo(3);

            assertThatThrownBy(() -> statement.execute("INSERT INTO attendance.attendance_student_records " +
                    "(id, attendance_daily_id, student_id, school_id, attendance_date, academic_year_id, " +
                    " class_id, section_id, status) VALUES " +
                    "('r23','d27',999,10,'2027-06-01','y27','c1','s1','PRESENT')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("duplicate attendance student record identity");

            assertThatThrownBy(() -> statement.execute("INSERT INTO attendance.attendance_student_records " +
                    "(id, attendance_daily_id, student_id, school_id, attendance_date, academic_year_id, " +
                    " class_id, section_id, status) VALUES " +
                    "('duplicate-daily-student','d24',101,10,'2027-06-01','y27','c1','s1','PRESENT')"))
                    .isInstanceOf(SQLException.class);

            statement.execute("INSERT INTO attendance.attendance_student_records " +
                    "(id, attendance_daily_id, student_id, school_id, attendance_date, academic_year_id, " +
                    " class_id, section_id, status) VALUES " +
                    "('r27','d27',301,10,'2027-06-01','y27','c1','s1','LEAVE')");
            assertThat(singleLong(statement,
                    "SELECT post_cutover_write_statements FROM " +
                            "attendance.attendance_student_records_data01_control")).isEqualTo(3);

            String plan = singleString(statement,
                    "EXPLAIN (FORMAT TEXT) SELECT * FROM attendance.attendance_student_records " +
                            "WHERE attendance_date='2024-06-01'");
            assertThat(plan).contains("attendance_student_records_y2024")
                    .doesNotContain("attendance_student_records_y2023")
                    .doesNotContain("attendance_student_records_default");
        }

        try (Connection app = DriverManager.getConnection(postgres.getJdbcUrl(), "app_rt", "app_rt");
             Statement statement = app.createStatement()) {
            statement.execute("SET app.current_school_id='10'");
            assertThat(singleLong(statement,
                    "SELECT count(*) FROM attendance.attendance_student_records")).isEqualTo(3);
            assertThatThrownBy(() -> statement.execute("INSERT INTO attendance.attendance_student_records " +
                    "(id, attendance_daily_id, student_id, school_id, attendance_date, academic_year_id, " +
                    " class_id, section_id, status) VALUES " +
                    "('cross-tenant','d25',999,20,'2026-06-01','y26','c2','s2','PRESENT')"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(error.getMessage())
                            .containsIgnoringCase("row-level security"));
        }

        Container.ExecResult refusedRollback = runPsql("90_rollback_before_resume.sql", maintenanceOptions());
        assertThat(refusedRollback.getExitCode()).isNotZero();
        assertThat(refusedRollback.getStderr()).contains("Rollback refused");

        assertPsqlSuccess("50_finalize.sql",
                "-c app.data01_maintenance_approved=DATA-01 " +
                        "-c app.data01_finalize_approved=DROP-LEGACY-DATA-01");
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            assertThat(singleString(statement,
                    "SELECT phase FROM attendance.attendance_student_records_data01_control"))
                    .isEqualTo("FINALIZED");
            assertThat(singleString(statement,
                    "SELECT to_regclass('attendance.attendance_student_records_data01_unpartitioned')::text"))
                    .isNull();
            assertThat(singleLong(statement,
                    "SELECT count(*) FROM pg_constraint WHERE conrelid=" +
                            "'attendance.attendance_student_records'::regclass AND conname IN " +
                            "('attendance_student_records_pkey','fk_attendance_student_records_daily'," +
                            "'uk_attendance_student_daily_student','uk_attendance_student_date_year'," +
                            "'attendance_student_records_status_check')")).isEqualTo(5);
        }
    }

    @Test
    void rollbackBeforeResumeRestoresUnpartitionedFlywayContract() throws Exception {
        for (String script : FORWARD) {
            assertPsqlSuccess(script, maintenanceOptions());
        }
        assertPsqlSuccess("90_rollback_before_resume.sql", maintenanceOptions());

        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            assertThat(singleString(statement,
                    "SELECT relkind::text FROM pg_class WHERE oid=" +
                            "'attendance.attendance_student_records'::regclass")).isEqualTo("r");
            assertThat(singleLong(statement,
                    "SELECT count(*) FROM attendance.attendance_student_records")).isEqualTo(3);
            assertThat(singleString(statement,
                    "SELECT phase FROM attendance.attendance_student_records_data01_control"))
                    .isEqualTo("ROLLED_BACK");
            assertThat(singleLong(statement,
                    "SELECT count(*) FROM pg_trigger WHERE tgrelid=" +
                            "'attendance.attendance_student_records'::regclass " +
                            "AND tgname='data01_source_write_freeze' AND NOT tgisinternal")).isZero();
            statement.execute("INSERT INTO attendance.attendance_student_records " +
                    "(id, attendance_daily_id, student_id, school_id, attendance_date, academic_year_id, " +
                    " class_id, section_id, status) VALUES " +
                    "('after-rollback','d27',301,10,'2027-06-01','y27','c1','s1','PRESENT')");
        }
    }

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "owner", "owner");
    }

    private void assertPsqlSuccess(String script, String options) throws Exception {
        Container.ExecResult result = runPsql(script, options);
        assertThat(result.getExitCode())
                .withFailMessage(() -> script + " failed\nstdout:\n" + result.getStdout() +
                        "\nstderr:\n" + result.getStderr())
                .isZero();
    }

    private Container.ExecResult runPsql(String script, String options) throws Exception {
        return postgres.execInContainer(
                "env", "PGOPTIONS=" + options,
                "psql", "--no-psqlrc", "--set=ON_ERROR_STOP=1",
                "--username=owner", "--dbname=" + postgres.getDatabaseName(),
                "--file=/tmp/" + script);
    }

    private static String maintenanceOptions() {
        return "-c app.data01_maintenance_approved=DATA-01";
    }

    private static long singleLong(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String singleString(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static Path findOperatorSql() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && cursor != null; depth++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve("scripts/sql/attendance-data01");
            if (Files.isRegularFile(candidate.resolve("00_preflight.sql"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate scripts/sql/attendance-data01 from test working directory");
    }
}

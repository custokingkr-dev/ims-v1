package com.custoking.ims.schoolcoreservice.observability;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AttendanceStorageHealthReporterIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16")
            .withUsername("owner")
            .withPassword("owner");

    static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas("attendance")
                .defaultSchema("attendance")
                .locations("classpath:db/migration/attendance")
                .load()
                .migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    @Test
    void snapshotCoversCurrentHeapAndFuturePartitionChildrenWithoutCountingRows() {
        jdbc.sql("""
                INSERT INTO attendance.attendance_daily
                    (id, attendance_date, total_enrolled, present_count, absent_count, locked,
                     school_class_id, section_id, academic_year_id, school_id)
                VALUES ('daily-1', DATE '2026-08-24', 1, 1, 0, false, '10', '10-A', '2026', 1)
                """).update();
        jdbc.sql("""
                INSERT INTO attendance.attendance_student_records
                    (id, attendance_daily_id, student_id, school_id, attendance_date,
                     academic_year_id, class_id, section_id, status)
                VALUES ('record-1', 'daily-1', 1, 1, DATE '2026-08-24', '2026', '10', '10-A', 'PRESENT')
                """).update();
        jdbc.sql("ANALYZE attendance.attendance_student_records").update();

        var current = new AttendanceStorageHealthReporter(jdbc).snapshot();

        assertThat(current.approximateRows()).isEqualTo(1);
        assertThat(current.heapBytes()).isPositive();
        assertThat(current.indexBytes()).isPositive();

        jdbc.sql("ALTER TABLE attendance.attendance_student_records RENAME TO attendance_student_records_legacy")
                .update();
        jdbc.sql("""
                CREATE TABLE attendance.attendance_student_records (
                    id text NOT NULL,
                    attendance_date date NOT NULL
                ) PARTITION BY RANGE (attendance_date)
                """).update();
        jdbc.sql("""
                CREATE TABLE attendance.attendance_student_records_2026
                PARTITION OF attendance.attendance_student_records
                FOR VALUES FROM (DATE '2026-01-01') TO (DATE '2027-01-01')
                """).update();
        jdbc.sql("""
                INSERT INTO attendance.attendance_student_records
                VALUES ('partitioned-1', DATE '2026-08-24'), ('partitioned-2', DATE '2026-08-25')
                """).update();
        jdbc.sql("ANALYZE attendance.attendance_student_records_2026").update();

        var partitioned = new AttendanceStorageHealthReporter(jdbc).snapshot();

        assertThat(partitioned.approximateRows()).isEqualTo(2);
        assertThat(partitioned.heapBytes()).isPositive();
    }
}

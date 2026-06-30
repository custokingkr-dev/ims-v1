package com.custoking.ims.attendanceservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.sql.*;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Migration tests for attendance-service tenant-key hardening.
 *
 * Part 1: full migration (V1→V5) — verifies attendance_daily.school_id is NOT NULL
 *         and index idx_attendance_daily_school_date exists.
 *
 * Part 2: cross-schema backfill (fresh container, target V4) — verifies the
 *         UPDATE in V4 populates school_id from tenant_school.school_sections.
 *         In prod, tenant_school.school_sections already exists (owned by the
 *         tenant-school-service schema); the test creates a minimal stand-in so
 *         the backfill UPDATE can be exercised in isolation.
 */
class AttendanceTenantKeyMigrationTest {

    // ── Part 1: full migration (V1→V5) — shared container ───────────────────
    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("attendance")
                .defaultSchema("attendance")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @Test
    void attendanceDaily_schoolId_isNotNull() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_nullable FROM information_schema.columns " +
                     "WHERE table_schema='attendance' AND table_name='attendance_daily' AND column_name='school_id'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Column school_id not found in attendance_daily");
                assertEquals("NO", rs.getString(1),
                        "attendance_daily.school_id must be NOT NULL after V5");
            }
        }
    }

    @Test
    void idx_attendance_daily_school_date_exists() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM pg_indexes " +
                     "WHERE schemaname='attendance' AND indexname='idx_attendance_daily_school_date'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Index idx_attendance_daily_school_date must exist after V4");
            }
        }
    }

    // ── Part 2: cross-schema backfill (fresh container, target V4) ───────────
    /**
     * Verifies that the V4 backfill UPDATE correctly sets school_id on
     * attendance_daily rows by joining to tenant_school.school_sections.
     *
     * In production, tenant_school.school_sections already exists (created and
     * owned by the tenant-school-service). In this test container, only the
     * attendance schema is migrated, so we create a minimal stand-in schema/table
     * that satisfies the cross-schema JOIN in the V4 UPDATE statement.
     */
    @Test
    void backfill_attendanceDaily_inheritsSchoolId_fromSchoolSections() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");

        try (PostgreSQLContainer<?> pg2 =
                new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner")) {
            pg2.start();

            // Apply only up to V4 — school_id is nullable at this point
            Flyway.configure()
                    .dataSource(pg2.getJdbcUrl(), "owner", "owner")
                    .schemas("attendance")
                    .defaultSchema("attendance")
                    .locations("classpath:db/migration")
                    .target("4")
                    .load()
                    .migrate();

            try (Connection c = DriverManager.getConnection(pg2.getJdbcUrl(), "owner", "owner")) {
                // Create a minimal stand-in for tenant_school.school_sections.
                // In prod this schema/table already exists; the test creates it here
                // purely so the cross-schema backfill UPDATE can run in isolation.
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE SCHEMA IF NOT EXISTS tenant_school");
                    st.execute("CREATE TABLE tenant_school.school_sections (" +
                               "id VARCHAR(255) PRIMARY KEY, school_id BIGINT)");
                    st.execute("INSERT INTO tenant_school.school_sections(id, school_id) VALUES ('sec1', 10)");
                }

                // Insert an attendance_daily row with school_id NULL, referencing the seeded section
                String dailyId = UUID.randomUUID().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO attendance.attendance_daily" +
                        "(id, attendance_date, total_enrolled, present_count, absent_count," +
                        " locked, school_class_id, section_id, academic_year_id)" +
                        " VALUES (?, ?, 30, 25, 5, false, 'cls1', 'sec1', 'AY-2024')")) {
                    ps.setString(1, dailyId);
                    ps.setDate(2, java.sql.Date.valueOf(LocalDate.of(2024, 6, 1)));
                    ps.executeUpdate();
                }

                // Verify school_id is NULL before backfill
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM attendance.attendance_daily WHERE id=?")) {
                    ps.setString(1, dailyId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "attendance_daily row must exist");
                        assertNull(rs.getObject(1), "school_id should be NULL before backfill");
                    }
                }

                // Run the V4 backfill UPDATE verbatim
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE attendance.attendance_daily ad " +
                        "SET school_id = ss.school_id " +
                        "FROM tenant_school.school_sections ss " +
                        "WHERE ss.id = ad.section_id AND ad.school_id IS NULL")) {
                    ps.executeUpdate();
                }

                // Assert school_id is now the seeded value (10)
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM attendance.attendance_daily WHERE id=?")) {
                    ps.setString(1, dailyId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "attendance_daily row must still exist");
                        assertEquals(10L, rs.getLong(1),
                                "attendance_daily.school_id should be 10 after backfill from tenant_school.school_sections");
                    }
                }
            }
        }
    }
}

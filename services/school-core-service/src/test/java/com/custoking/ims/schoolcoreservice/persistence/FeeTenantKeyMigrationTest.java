package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Migration tests for fee-service tenant-key hardening.
 *
 * Part 1: full migration (V1→V6) — verifies fee_assignments.school_id and
 *         payment_records.school_id are NOT NULL, and both indexes exist.
 *
 * Part 2: cross-schema backfill (fresh container, target V5) — verifies that
 *         the V5 backfill UPDATE correctly sets school_id on fee_assignments and
 *         payment_records rows by joining to student.students.
 *         In prod, student.students already exists (owned by the student-service).
 *         In this test, we create a minimal stand-in so the backfill can be
 *         exercised in isolation.
 *
 * Part 3: V5 guard TRUE-path — seeds student.students BEFORE V5 runs so the
 *         DO-block guard resolves TRUE and Flyway's own execution of V5 performs
 *         the backfill. Proves the guarded UPDATE inside V5 is actually executed.
 */
class FeeTenantKeyMigrationTest {

    // ── Part 1: full migration (V1→V6) — shared container ───────────────────
    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("fee")
                .defaultSchema("fee")
                .locations("classpath:db/migration/fee")
                .load()
                .migrate();
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @Test
    void feeAssignments_schoolId_isNotNull() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_nullable FROM information_schema.columns " +
                     "WHERE table_schema='fee' AND table_name='fee_assignments' AND column_name='school_id'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Column school_id not found in fee_assignments");
                assertEquals("NO", rs.getString(1),
                        "fee_assignments.school_id must be NOT NULL after V6");
            }
        }
    }

    @Test
    void paymentRecords_schoolId_isNotNull() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_nullable FROM information_schema.columns " +
                     "WHERE table_schema='fee' AND table_name='payment_records' AND column_name='school_id'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Column school_id not found in payment_records");
                assertEquals("NO", rs.getString(1),
                        "payment_records.school_id must be NOT NULL after V6");
            }
        }
    }

    @Test
    void idx_fee_assignments_school_year_student_exists() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM pg_indexes " +
                     "WHERE schemaname='fee' AND indexname='idx_fee_assignments_school_year_student'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Index idx_fee_assignments_school_year_student must exist after V5");
            }
        }
    }

    @Test
    void idx_payment_records_school_paid_exists() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM pg_indexes " +
                     "WHERE schemaname='fee' AND indexname='idx_payment_records_school_paid'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Index idx_payment_records_school_paid must exist after V5");
            }
        }
    }

    // ── Part 2: cross-schema backfill (fresh container, explicit UPDATE at V5) ─
    /**
     * Verifies that the V5 backfill UPDATE correctly sets school_id on
     * fee_assignments and payment_records rows by joining to student.students.
     */
    @Test
    void backfill_feeAssignmentsAndPaymentRecords_inheritsSchoolId_fromStudentStudents() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");

        try (PostgreSQLContainer<?> pg2 =
                new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner")) {
            pg2.start();

            // Apply only up to V5 — school_id is nullable at this point
            Flyway.configure()
                    .dataSource(pg2.getJdbcUrl(), "owner", "owner")
                    .schemas("fee")
                    .defaultSchema("fee")
                    .locations("classpath:db/migration/fee")
                    .target("5")
                    .load()
                    .migrate();

            try (Connection c = DriverManager.getConnection(pg2.getJdbcUrl(), "owner", "owner")) {
                // Create a minimal stand-in for student.students.
                // In prod this schema/table already exists; the test creates it here
                // purely so the cross-schema backfill UPDATE can run in isolation.
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE SCHEMA IF NOT EXISTS student");
                    st.execute("CREATE TABLE student.students (id BIGINT PRIMARY KEY, school_id BIGINT)");
                    st.execute("INSERT INTO student.students(id, school_id) VALUES (1, 10)");
                }

                // Seed a fee_band (required by FK on fee_assignments)
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO fee.fee_bands(id, name, class_from, class_to, discount, academic_year_id) " +
                        "VALUES ('band-1', 'Band A', 1, 5, 0.0, 'AY-2024')")) {
                    ps.executeUpdate();
                }

                // Insert a fee_assignments row with student_id=1, school_id NULL
                String assignmentId = "assign-backfill-1";
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO fee.fee_assignments(id, band_discount, manual_discount, surcharge, " +
                        "net_payable, paid_amount, student_id, band_id, academic_year_id, version) " +
                        "VALUES (?, 0.0, 0.0, 0.0, 1000, 0, 1, 'band-1', 'AY-2024', 0)")) {
                    ps.setString(1, assignmentId);
                    ps.executeUpdate();
                }

                // Insert a payment_records row with student_id=1, school_id NULL
                String paymentId = "pay-backfill-1";
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO fee.payment_records(id, amount, student_id, assignment_id, version) " +
                        "VALUES (?, 500, 1, ?, 0)")) {
                    ps.setString(1, paymentId);
                    ps.setString(2, assignmentId);
                    ps.executeUpdate();
                }

                // Verify school_id is NULL before backfill
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM fee.fee_assignments WHERE id=?")) {
                    ps.setString(1, assignmentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "fee_assignments row must exist");
                        assertNull(rs.getObject(1), "fee_assignments.school_id should be NULL before backfill");
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM fee.payment_records WHERE id=?")) {
                    ps.setString(1, paymentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "payment_records row must exist");
                        assertNull(rs.getObject(1), "payment_records.school_id should be NULL before backfill");
                    }
                }

                // Run the V5 backfill UPDATE verbatim for fee_assignments
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE fee.fee_assignments fa " +
                        "SET school_id = s.school_id " +
                        "FROM student.students s " +
                        "WHERE s.id = fa.student_id AND fa.school_id IS NULL")) {
                    ps.executeUpdate();
                }

                // Run the V5 backfill UPDATE verbatim for payment_records
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE fee.payment_records pr " +
                        "SET school_id = s.school_id " +
                        "FROM student.students s " +
                        "WHERE s.id = pr.student_id AND pr.school_id IS NULL")) {
                    ps.executeUpdate();
                }

                // Assert fee_assignments.school_id is now 10
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM fee.fee_assignments WHERE id=?")) {
                    ps.setString(1, assignmentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "fee_assignments row must still exist");
                        assertEquals(10L, rs.getLong(1),
                                "fee_assignments.school_id should be 10 after backfill from student.students");
                    }
                }

                // Assert payment_records.school_id is now 10
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM fee.payment_records WHERE id=?")) {
                    ps.setString(1, paymentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "payment_records row must still exist");
                        assertEquals(10L, rs.getLong(1),
                                "payment_records.school_id should be 10 after backfill from student.students");
                    }
                }
            }
        }
    }

    // ── Part 3: V5 guard TRUE-path — backfill performed BY the migration ─────
    /**
     * Seeds student.students BEFORE V5 runs so the DO-block guard resolves TRUE
     * and Flyway's own execution of V5 performs the backfill.
     * Proves the guarded UPDATE inside V5 is actually executed — not just a
     * hand-copied UPDATE in the test code.
     */
    @Test
    void v5_guard_truePath_backfill_performedByMigration() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");

        try (PostgreSQLContainer<?> pg3 =
                new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner")) {
            pg3.start();

            // Step 1: migrate to V4 — fee_assignments and payment_records exist
            //         but school_id column does NOT yet exist
            Flyway.configure()
                    .dataSource(pg3.getJdbcUrl(), "owner", "owner")
                    .schemas("fee")
                    .defaultSchema("fee")
                    .locations("classpath:db/migration/fee")
                    .target("4")
                    .load()
                    .migrate();

            try (Connection c = DriverManager.getConnection(pg3.getJdbcUrl(), "owner", "owner")) {
                // Step 2: create student.students stand-in BEFORE V5 runs,
                // so the DO-block guard (to_regclass check) resolves TRUE during migration
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE SCHEMA IF NOT EXISTS student");
                    st.execute("CREATE TABLE student.students (id BIGINT PRIMARY KEY, school_id BIGINT)");
                    st.execute("INSERT INTO student.students(id, school_id) VALUES (1, 10)");
                }

                // Step 3: seed a fee_band (required by FK on fee_assignments)
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO fee.fee_bands(id, name, class_from, class_to, discount, academic_year_id) " +
                        "VALUES ('band-guard', 'Band Guard', 1, 5, 0.0, 'AY-2024')")) {
                    ps.executeUpdate();
                }

                // Step 4: seed fee_assignments and payment_records rows;
                // school_id column does not exist yet (V5 adds it) so we omit it here
                String assignmentId = "assign-guard-1";
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO fee.fee_assignments(id, band_discount, manual_discount, surcharge, " +
                        "net_payable, paid_amount, student_id, band_id, academic_year_id, version) " +
                        "VALUES (?, 0.0, 0.0, 0.0, 1000, 0, 1, 'band-guard', 'AY-2024', 0)")) {
                    ps.setString(1, assignmentId);
                    ps.executeUpdate();
                }

                String paymentId = "pay-guard-1";
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO fee.payment_records(id, amount, student_id, assignment_id, version) " +
                        "VALUES (?, 500, 1, ?, 0)")) {
                    ps.setString(1, paymentId);
                    ps.setString(2, assignmentId);
                    ps.executeUpdate();
                }
            }

            // Step 5: run V5 — guard resolves TRUE, migration performs the backfill itself
            Flyway.configure()
                    .dataSource(pg3.getJdbcUrl(), "owner", "owner")
                    .schemas("fee")
                    .defaultSchema("fee")
                    .locations("classpath:db/migration/fee")
                    .target("5")
                    .load()
                    .migrate();

            // Step 6: assert school_id was populated BY THE MIGRATION's own guarded UPDATE,
            // not by any hand-copied SQL in test code
            try (Connection c = DriverManager.getConnection(pg3.getJdbcUrl(), "owner", "owner")) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM fee.fee_assignments WHERE id=?")) {
                    ps.setString(1, "assign-guard-1");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "fee_assignments row must exist after V5");
                        assertEquals(10L, rs.getLong(1),
                                "fee_assignments.school_id must be 10 after V5 migration performed the guarded backfill");
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM fee.payment_records WHERE id=?")) {
                    ps.setString(1, "pay-guard-1");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "payment_records row must exist after V5");
                        assertEquals(10L, rs.getLong(1),
                                "payment_records.school_id must be 10 after V5 migration performed the guarded backfill");
                    }
                }
            }
        }
    }
}

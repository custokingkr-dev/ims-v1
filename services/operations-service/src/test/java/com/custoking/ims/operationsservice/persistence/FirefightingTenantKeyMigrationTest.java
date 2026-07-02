package com.custoking.ims.operationsservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class FirefightingTenantKeyMigrationTest {

    // ── Part 1: full migration (V1→V4) — shared container ───────────────────
    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("firefighting")
                .defaultSchema("firefighting")
                .locations("classpath:db/migration/firefighting")
                .load()
                .migrate();
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    private boolean isNotNull(String table, String column) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_nullable FROM information_schema.columns " +
                     "WHERE table_schema='firefighting' AND table_name=? AND column_name=?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Column " + column + " not found in table " + table);
                return "NO".equals(rs.getString(1));
            }
        }
    }

    @Test
    void firefightingRequests_schoolId_isNotNull() throws Exception {
        assertTrue(isNotNull("firefighting_requests", "school_id"),
                "firefighting_requests.school_id must be NOT NULL after V3");
    }

    @Test
    void ffQuotations_schoolId_isNotNull() throws Exception {
        assertTrue(isNotNull("ff_quotations", "school_id"),
                "ff_quotations.school_id must be NOT NULL after V4");
    }

    @Test
    void idx_ffQuotations_schoolRequest_exists() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM pg_indexes " +
                     "WHERE schemaname='firefighting' AND indexname='idx_ff_quotations_school_request'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Index idx_ff_quotations_school_request must exist after V3");
            }
        }
    }

    // ── Part 2a: V3 guard TRUE-path — backfill performed BY the migration ───────
    /**
     * Closes the coverage gap where the existing Part-2 test runs Flyway to target('3')
     * and then re-runs the UPDATE manually via JDBC.  That means the real guarded UPDATE
     * inside V3 is never exercised by tests.
     *
     * This test seeds a firefighting_requests parent (school=10) and an ff_quotations
     * child (no school_id column yet) BEFORE running V3, so Flyway's own execution of
     * V3 performs the backfill UPDATE itself.
     */
    @Test
    void v3_backfill_performedByMigration() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");

        try (PostgreSQLContainer<?> pg3 =
                new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner")) {
            pg3.start();

            // Step 1: migrate to V2 — ff_quotations exists but has no school_id column yet
            Flyway.configure()
                    .dataSource(pg3.getJdbcUrl(), "owner", "owner")
                    .schemas("firefighting")
                    .defaultSchema("firefighting")
                    .locations("classpath:db/migration/firefighting")
                    .target("2")
                    .load()
                    .migrate();

            try (Connection c = DriverManager.getConnection(pg3.getJdbcUrl(), "owner", "owner")) {
                // Step 2: seed a firefighting_requests parent with school_id=10
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO firefighting.firefighting_requests" +
                        "(code, estimated_budget, school_id, version) VALUES ('REQ-GUARD', 0, 10, 0)")) {
                    ps.executeUpdate();
                }

                // Step 3: seed an ff_quotations child — no school_id column exists yet at V2
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO firefighting.ff_quotations" +
                        "(id, amount, is_custoking, is_recommended, request_id) " +
                        "VALUES ('Q-GUARD', 0, false, false, 'REQ-GUARD')")) {
                    ps.executeUpdate();
                }
            }

            // Step 4: run V3 — adds school_id column and backfills it from the parent request
            Flyway.configure()
                    .dataSource(pg3.getJdbcUrl(), "owner", "owner")
                    .schemas("firefighting")
                    .defaultSchema("firefighting")
                    .locations("classpath:db/migration/firefighting")
                    .target("3")
                    .load()
                    .migrate();

            // Step 5: assert school_id was populated BY THE MIGRATION's own UPDATE,
            // not by any hand-copied SQL in test code
            try (Connection c = DriverManager.getConnection(pg3.getJdbcUrl(), "owner", "owner");
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT school_id FROM firefighting.ff_quotations WHERE id = 'Q-GUARD'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "ff_quotations row Q-GUARD must exist after V3");
                    assertEquals(10L, rs.getLong(1),
                            "ff_quotations.school_id must be 10 after V3 migration performed the backfill");
                }
            }
        }
    }

    // ── Part 2: backfill logic (fresh container, target V3) ──────────────────
    @Test
    void backfill_quotationInheritsSchoolId_fromParentRequest() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");

        try (PostgreSQLContainer<?> pg2 =
                new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner")) {
            pg2.start();

            // Apply only up to V3 — ff_quotations.school_id is nullable at this point
            Flyway.configure()
                    .dataSource(pg2.getJdbcUrl(), "owner", "owner")
                    .schemas("firefighting")
                    .defaultSchema("firefighting")
                    .locations("classpath:db/migration/firefighting")
                    .target("3")
                    .load()
                    .migrate();

            try (Connection c = DriverManager.getConnection(pg2.getJdbcUrl(), "owner", "owner")) {
                // INSERT a firefighting_requests row with school_id=10
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO firefighting.firefighting_requests" +
                        "(code, estimated_budget, school_id, version) VALUES ('REQ1', 0, 10, 0)")) {
                    ps.executeUpdate();
                }

                // INSERT an ff_quotations row with school_id NULL (simulates pre-backfill state)
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO firefighting.ff_quotations" +
                        "(id, amount, is_custoking, is_recommended, request_id) VALUES ('Q1', 0, false, false, 'REQ1')")) {
                    ps.executeUpdate();
                }

                // Verify the quotation's school_id is NULL before backfill
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM firefighting.ff_quotations WHERE id='Q1'");
                     ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Quotation Q1 must exist");
                    assertNull(rs.getObject(1), "school_id should be NULL before backfill");
                }

                // Run the V3 backfill UPDATE statement
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE firefighting.ff_quotations q " +
                        "SET school_id = r.school_id " +
                        "FROM firefighting.firefighting_requests r " +
                        "WHERE r.code = q.request_id AND q.school_id IS NULL")) {
                    ps.executeUpdate();
                }

                // Assert the quotation's school_id is now 10
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM firefighting.ff_quotations WHERE id='Q1'");
                     ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Quotation Q1 must exist after backfill");
                    assertEquals(10L, rs.getLong(1),
                            "ff_quotations.school_id should be 10 after backfill from parent request");
                }
            }
        }
    }
}

package com.custoking.ims.firefightingservice.persistence;

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
                .locations("classpath:db/migration")
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
                    .locations("classpath:db/migration")
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

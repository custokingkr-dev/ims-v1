package com.custoking.ims.identityservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class AuthSessionMigrationTest {

    // ── Part 1: full migration (V1→V2) — shared container ───────────────────
    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("identity")
                .defaultSchema("identity")
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
                     "WHERE table_schema='identity' AND table_name=? AND column_name=?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Column " + column + " not found in table " + table);
                return "NO".equals(rs.getString(1));
            }
        }
    }

    @Test
    void columns_areNotNull() throws Exception {
        assertTrue(isNotNull("auth_sessions", "family_id"),
                "auth_sessions.family_id must be NOT NULL after V2");
        assertTrue(isNotNull("auth_sessions", "status"),
                "auth_sessions.status must be NOT NULL after V2");
    }

    // ── Part 2: backfill logic (fresh container, V1→V2) ─────────────────────
    @Test
    void backfill_assignsFamilyAndActive() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");

        try (PostgreSQLContainer<?> pg2 =
                new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner")) {
            pg2.start();

            // Step 1: migrate only to V1 — family_id and status columns do not exist yet
            Flyway.configure()
                    .dataSource(pg2.getJdbcUrl(), "owner", "owner")
                    .schemas("identity")
                    .defaultSchema("identity")
                    .locations("classpath:db/migration")
                    .target("1")
                    .load()
                    .migrate();

            try (Connection c = DriverManager.getConnection(pg2.getJdbcUrl(), "owner", "owner")) {
                // Step 2: seed an app_users row (V1 NOT NULL columns only)
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO identity.app_users(full_name, email, password_hash, role, created_at) " +
                        "VALUES ('Test User', 'test@example.com', 'hash', 'ADMIN', now())")) {
                    ps.executeUpdate();
                }

                // Step 3: retrieve the generated user id
                long userId;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM identity.app_users WHERE email = 'test@example.com'");
                     ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "app_users row must exist");
                    userId = rs.getLong(1);
                }

                // Step 4: seed an auth_sessions row using V1 columns only — no family_id/status yet
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO identity.auth_sessions(id, user_id, access_token_hash, refresh_token_hash, created_at, expires_at) " +
                        "VALUES ('sess-1', ?, 'a', 'r', now(), now() + interval '1 day')")) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
            }

            // Step 5: run V2 — adds family_id/status columns and backfills existing rows
            Flyway.configure()
                    .dataSource(pg2.getJdbcUrl(), "owner", "owner")
                    .schemas("identity")
                    .defaultSchema("identity")
                    .locations("classpath:db/migration")
                    .target("2")
                    .load()
                    .migrate();

            // Step 6: assert the row was backfilled by the migration
            try (Connection c = DriverManager.getConnection(pg2.getJdbcUrl(), "owner", "owner");
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT family_id, status FROM identity.auth_sessions WHERE id = 'sess-1'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "auth_sessions row sess-1 must exist after V2");
                assertEquals("sess-1", rs.getString("family_id"),
                        "family_id must equal id after V2 backfill");
                assertEquals("ACTIVE", rs.getString("status"),
                        "status must be ACTIVE after V2 backfill");
            }
        }
    }
}

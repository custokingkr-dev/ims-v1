package com.custoking.ims.operationsservice.security;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class FirefightingRlsIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource appRt; // app_rt pool wrapped by TenantAwareDataSource

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping RLS integration test");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();

        // Migrate as the owner (owns tables → bypasses RLS, like appuser in prod).
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("firefighting").defaultSchema("firefighting")
                .locations("classpath:db/migration/firefighting")
                .load().migrate();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // Unprivileged runtime role, subject to RLS.
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA firefighting TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA firefighting TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA firefighting TO app_rt");

            // Seed firefighting_requests: school 10 (A) x2, school 20 (B) x1 — as owner (bypasses RLS).
            st.execute("INSERT INTO firefighting.firefighting_requests" +
                    "(code, estimated_budget, school_id, version) VALUES " +
                    "('FF-001', 0, 10, 0)," +
                    "('FF-002', 0, 10, 0)," +
                    "('FF-003', 0, 20, 0)");

            // Seed ff_quotations: 2 rows for school 10, 1 row for school 20 — reusing parent codes above.
            st.execute("INSERT INTO firefighting.ff_quotations" +
                    "(id, amount, is_custoking, is_recommended, request_id, school_id) VALUES " +
                    "('Q-001', 1000, false, false, 'FF-001', 10)," +
                    "('Q-002', 2000, false, true,  'FF-002', 10)," +
                    "('Q-003', 3000, true,  false, 'FF-003', 20)");
        }

        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        pool.setMaximumPoolSize(2);
        appRt = new TenantAwareDataSource(pool);
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (PG != null) PG.stop();
    }

    @AfterEach
    void clearCtx() { TenantContext.clear(); }

    private long countRows() throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM firefighting.firefighting_requests")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countQuotations() throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM firefighting.ff_quotations")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countRows());
    }

    @Test
    void schoolB_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, countRows());
    }

    @Test
    void superadmin_seesAll() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, countRows());
    }

    @Test
    void noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, countRows());
    }

    @Test
    void ffQuotations_schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countQuotations());
    }

    @Test
    void requestCode_usesGlobalSequence_notRlsScopedMax() throws Exception {
        // Regression: request-code minting used MAX(numeric(code))+1, which runs under RLS
        // scoped to the caller's own school. School A (10) sees only FF-001/FF-002, so the
        // scoped max is 2 and the old code minted FF-003 — already owned globally by school B
        // (20) — causing a duplicate-key 500 on the global code PK. The V9 sequence is seeded
        // from the GLOBAL max (3, visible to the RLS-exempt owner at migration time), so
        // nextval() returns past it regardless of the caller's RLS scope. No collision.
        // In prod the request rows pre-exist when V9 runs, so V9 seeds the sequence from the
        // global max. This harness inserts the seed rows AFTER migrate(), so align the sequence
        // to the global max as the RLS-exempt owner does — reproducing the prod ordering.
        try (Connection owner = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = owner.createStatement()) {
            st.execute("SELECT setval('firefighting.seq_firefighting_request_code', " +
                    "(SELECT MAX(NULLIF(regexp_replace(code, '[^0-9]', '', 'g'), '')::bigint) " +
                    " FROM firefighting.firefighting_requests WHERE code LIKE 'FF-%'))");
        }

        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT COALESCE(MAX(NULLIF(regexp_replace(code, '[^0-9]+', '', 'g'), '')::int), 0) " +
                    "FROM firefighting.firefighting_requests")) {
                rs.next();
                assertEquals(2, rs.getInt(1), "RLS scopes school A's visible max to 2 (the old, buggy basis)");
            }
            // The global sequence mints past the GLOBAL max (3) even under school A's RLS scope,
            // so it never collides with school B's existing FF-003 (the old MAX+1 minted FF-003).
            try (ResultSet rs = st.executeQuery(
                    "SELECT nextval('firefighting.seq_firefighting_request_code')")) {
                rs.next();
                assertTrue(rs.getLong(1) >= 4,
                        "sequence must mint from the global max (3), not the RLS-scoped max (2)");
            }
        }
    }

    @Test
    void withCheck_blocksCrossTenantInsert() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO firefighting.firefighting_requests" +
                    "(code, estimated_budget, school_id, version) " +
                    "VALUES ('FF-X', 0, 20, 0)"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }
}

package com.custoking.ims.firefightingservice.security;

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
                .locations("classpath:db/migration")
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

package com.custoking.ims.schoolcoreservice.security;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class CatalogRlsIntegrationTest {

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
                .schemas("catalog").defaultSchema("catalog")
                .locations("classpath:db/migration/catalog")
                .load().migrate();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // Unprivileged runtime role, subject to RLS.
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA catalog TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA catalog TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA catalog TO app_rt");

            // Seed catalog_orders: school 10 (A) x2, school 20 (B) x1, school 30 (C) x1 —
            // as owner (bypasses RLS).
            st.execute("INSERT INTO catalog.catalog_orders " +
                    "(id, category, subtotal, gst, total_amount, school_id, version, status) VALUES " +
                    "('o1','STATIONERY',1000,180,1180,10,0,'APPROVED')," +
                    "('o2','NOTEBOOKS',2000,360,2360,10,0,'APPROVED')," +
                    "('o3','UNIFORMS',3000,540,3540,20,0,'APPROVED')," +
                    "('o4','UNIFORMS',4000,720,4720,30,0,'APPROVED')");

            // Seed annual_plan_items: 2 rows for school 10, 1 row for school 20.
            st.execute("INSERT INTO catalog.annual_plan_items " +
                    "(id, estimated_amount, school_id, academic_year_id) VALUES " +
                    "('ap1', 5000, 10, 'AY-2025')," +
                    "('ap2', 6000, 10, 'AY-2025')," +
                    "('ap3', 7000, 20, 'AY-2025')");
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
             ResultSet rs = st.executeQuery("SELECT count(*) FROM catalog.catalog_orders")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countAnnualPlanItems() throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM catalog.annual_plan_items")) {
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
        assertEquals(4, countRows());
    }

    @Test
    void noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, countRows());
    }

    @Test
    void annualPlanItems_schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countAnnualPlanItems());
    }

    /**
     * Sets the operator-scope GUC transaction-locally (is_local = true) — exactly mirroring
     * {@code CatalogReadRepository.allowCrossSchoolReadForOperations} — inside an explicit
     * transaction on the given (pooled) connection, so the value is guaranteed to reset on
     * commit and never leaks to whichever test/request borrows the same physical connection
     * next from the (size-2) HikariCP pool.
     */
    private void setOperatorSchoolsLocal(Connection c, String csv) throws SQLException {
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.operator_schools', ?, true)")) {
            ps.setString(1, csv);
            ps.execute();
        }
    }

    private void endTransaction(Connection c) throws SQLException {
        c.commit();
        c.setAutoCommit(true);
    }

    @Test
    void operations_withOperatorScope_seesOnlyAssignedSchools() throws Exception {
        TenantContext.set(new TenantContext(4L, "op@x", "OPERATIONS", null, null));
        try (Connection c = appRt.getConnection()) {
            setOperatorSchoolsLocal(c, "10,20");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM catalog.catalog_orders")) {
                rs.next();
                // o1, o2 (school 10) + o3 (school 20) = 3; o4 (school 30) excluded.
                assertEquals(3, rs.getLong(1));
            }
            endTransaction(c);
        }
    }

    @Test
    void operations_emptyOperatorScope_seesNothing() throws Exception {
        TenantContext.set(new TenantContext(4L, "op@x", "OPERATIONS", null, null));
        try (Connection c = appRt.getConnection()) {
            setOperatorSchoolsLocal(c, "");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM catalog.catalog_orders")) {
                rs.next();
                assertEquals(0, rs.getLong(1));
            }
            endTransaction(c);
        }
    }

    @Test
    void operations_markDelivered_outsideOperatorScope_isBlockedByWithCheck() throws Exception {
        TenantContext.set(new TenantContext(4L, "op@x", "OPERATIONS", null, null));
        try (Connection c = appRt.getConnection()) {
            setOperatorSchoolsLocal(c, "10,20");
            try (Statement st = c.createStatement()) {
                // o4 belongs to school 30, outside the operator's assigned set: the USING clause
                // makes it invisible to this transaction, so the UPDATE matches (and changes) 0 rows.
                int updated = st.executeUpdate("UPDATE catalog.catalog_orders SET status = 'DELIVERED' WHERE id = 'o4'");
                assertEquals(0, updated);
            }
            endTransaction(c);
        }
        // Confirm — via the RLS-bypassing owner connection — that o4 was left untouched.
        try (Connection owner = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = owner.createStatement();
             ResultSet rs = st.executeQuery("SELECT status FROM catalog.catalog_orders WHERE id = 'o4'")) {
            rs.next();
            assertEquals("APPROVED", rs.getString(1));
        }
    }

    @Test
    void operations_markDelivered_withinOperatorScope_succeeds() throws Exception {
        TenantContext.set(new TenantContext(4L, "op@x", "OPERATIONS", null, null));
        try (Connection c = appRt.getConnection()) {
            setOperatorSchoolsLocal(c, "10,20");
            try (Statement st = c.createStatement()) {
                int updated = st.executeUpdate("UPDATE catalog.catalog_orders SET status = 'DELIVERED' WHERE id = 'o3'");
                assertEquals(1, updated);
            }
            endTransaction(c);
        }
        try (Connection owner = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = owner.createStatement();
             ResultSet rs = st.executeQuery("SELECT status FROM catalog.catalog_orders WHERE id = 'o3'")) {
            rs.next();
            assertEquals("DELIVERED", rs.getString(1));
        }
    }

    @Test
    void withCheck_blocksCrossTenantInsert() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO catalog.catalog_orders " +
                    "(id, category, subtotal, gst, total_amount, school_id, version) " +
                    "VALUES ('oX','STATIONERY',100,18,118,20,0)"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }
}

package com.custoking.ims.workflowservice.security;

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

class WorkflowRlsIntegrationTest {

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
                .schemas("workflow").defaultSchema("workflow")
                .locations("classpath:db/migration")
                .load().migrate();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // Unprivileged runtime role, subject to RLS.
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA workflow TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA workflow TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA workflow TO app_rt");

            // Seed workflow_instances: school 10 (A) x2, school 20 (B) x1 — as owner (bypasses RLS).
            // SUPPLY_ORDER_DEFAULT definition is seeded by V1 migration.
            st.execute("INSERT INTO workflow.workflow_instances" +
                    "(definition_id, entity_type, entity_id, school_id) VALUES " +
                    "('SUPPLY_ORDER_DEFAULT', 'ORDER', 'ORD-A1', 10)," +
                    "('SUPPLY_ORDER_DEFAULT', 'ORDER', 'ORD-A2', 10)," +
                    "('SUPPLY_ORDER_DEFAULT', 'ORDER', 'ORD-B1', 20)");

            // Retrieve the seeded instance IDs for FK references in workflow_actions.
            long instanceA1, instanceA2, instanceB1;
            try (ResultSet rs = st.executeQuery(
                    "SELECT id, entity_id FROM workflow.workflow_instances " +
                    "WHERE entity_id IN ('ORD-A1','ORD-A2','ORD-B1') ORDER BY entity_id")) {
                rs.next(); instanceA1 = rs.getLong("id");  // ORD-A1
                rs.next(); instanceA2 = rs.getLong("id");  // ORD-A2
                rs.next(); instanceB1 = rs.getLong("id");  // ORD-B1
            }

            // Seed workflow_actions: 2 rows school 10, 1 row school 20.
            st.execute("INSERT INTO workflow.workflow_actions" +
                    "(instance_id, step_order, action, school_id) VALUES " +
                    "(" + instanceA1 + ", 0, 'SUBMIT', 10)," +
                    "(" + instanceA2 + ", 0, 'SUBMIT', 10)," +
                    "(" + instanceB1 + ", 0, 'SUBMIT', 20)");
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

    private long countInstances() throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM workflow.workflow_instances")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countActions() throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM workflow.workflow_actions")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countInstances());
    }

    @Test
    void schoolB_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, countInstances());
    }

    @Test
    void superadmin_seesAll() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, countInstances());
    }

    @Test
    void noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, countInstances());
    }

    @Test
    void workflowActions_schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countActions());
    }

    @Test
    void withCheck_blocksCrossTenantInsert() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO workflow.workflow_instances" +
                    "(definition_id, entity_type, entity_id, school_id) " +
                    "VALUES ('SUPPLY_ORDER_DEFAULT', 'ORDER', 'ORD-X', 20)"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }
}

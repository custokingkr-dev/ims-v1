package com.custoking.ims.schoolcoreservice.security;

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

class TenantSchoolRlsIntegrationTest {

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
                .schemas("tenant_school").defaultSchema("tenant_school")
                .locations("classpath:db/migration/tenant_school")
                .load().migrate();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // Unprivileged runtime role, subject to RLS.
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA tenant_school TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tenant_school TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA tenant_school TO app_rt");

            // Seed schools: A id=10, B id=20 — as owner (bypasses RLS).
            st.execute("INSERT INTO tenant_school.schools " +
                    "(id, name, short_code, active, created_at) VALUES " +
                    "(10,'School A','SCH-A',true, now())," +
                    "(20,'School B','SCH-B',true, now())");

            // Seed staff_members: 2 rows for school A, 1 row for school B.
            st.execute("INSERT INTO tenant_school.staff_members " +
                    "(name, designation, department, monthly_salary, payroll_status, school_id) VALUES " +
                    "('Alice','Teacher','Academics',50000,'ACTIVE',10)," +
                    "('Bob','Teacher','Academics',52000,'ACTIVE',10)," +
                    "('Carol','Teacher','Academics',51000,'ACTIVE',20)");

            // Seed school_classes + school_sections: 2 rows for school A, 1 for school B.
            st.execute("INSERT INTO tenant_school.school_classes (id, name, sort_order) VALUES " +
                    "('CLS-1','Class 1',1)");
            st.execute("INSERT INTO tenant_school.school_sections " +
                    "(id, name, teacher_name, active, school_class_id, school_id) VALUES " +
                    "('SEC-A1','Section A1','Alice',true,'CLS-1',10)," +
                    "('SEC-A2','Section A2','Bob',true,'CLS-1',10)," +
                    "('SEC-B1','Section B1','Carol',true,'CLS-1',20)");

            // Seed school_module_entitlements: 2 rows for school A, 1 for school B.
            st.execute("INSERT INTO tenant_school.school_module_entitlements " +
                    "(school_id, module_code, enabled) VALUES " +
                    "(10,'FEES',true)," +
                    "(10,'ATTENDANCE',true)," +
                    "(20,'FEES',true)");

            // Seed a zone + zone_school_mappings row (Group C, bypass-only).
            st.execute("INSERT INTO tenant_school.zones (id, name, code) VALUES (1,'Zone 1','Z1')");
            st.execute("INSERT INTO tenant_school.zone_school_mappings (zone_id, school_id) VALUES (1, 10)");
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

    private long countRows(String table) throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---- staff_members (Group A) ----

    @Test
    void staffMembers_schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countRows("tenant_school.staff_members"));
    }

    @Test
    void staffMembers_schoolB_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, countRows("tenant_school.staff_members"));
    }

    @Test
    void staffMembers_superadmin_seesAll() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, countRows("tenant_school.staff_members"));
    }

    @Test
    void staffMembers_noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, countRows("tenant_school.staff_members"));
    }

    // ---- school_sections (Group A) ----

    @Test
    void schoolSections_schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countRows("tenant_school.school_sections"));
    }

    @Test
    void schoolSections_schoolB_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, countRows("tenant_school.school_sections"));
    }

    @Test
    void schoolSections_superadmin_seesAll() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, countRows("tenant_school.school_sections"));
    }

    @Test
    void schoolSections_noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, countRows("tenant_school.school_sections"));
    }

    // ---- school_module_entitlements (Group A) ----

    @Test
    void moduleEntitlements_schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countRows("tenant_school.school_module_entitlements"));
    }

    @Test
    void moduleEntitlements_schoolB_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, countRows("tenant_school.school_module_entitlements"));
    }

    @Test
    void moduleEntitlements_superadmin_seesAll() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, countRows("tenant_school.school_module_entitlements"));
    }

    @Test
    void moduleEntitlements_noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, countRows("tenant_school.school_module_entitlements"));
    }

    // ---- schools (Group B — keyed on id, not school_id) ----

    @Test
    void schools_schoolA_seesOnlyOwnRow() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM tenant_school.schools")) {
            assertTrue(rs.next());
            assertEquals(10L, rs.getLong(1));
            assertFalse(rs.next());
        }
    }

    @Test
    void schools_schoolB_seesOnlyOwnRow() throws Exception {
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM tenant_school.schools")) {
            assertTrue(rs.next());
            assertEquals(20L, rs.getLong(1));
            assertFalse(rs.next());
        }
    }

    @Test
    void schools_superadmin_seesAll() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(2, countRows("tenant_school.schools"));
    }

    @Test
    void schools_noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, countRows("tenant_school.schools"));
    }

    // ---- WITH CHECK: cross-tenant insert blocked (staff_members) ----

    @Test
    void withCheck_blocksCrossTenantInsert() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO tenant_school.staff_members " +
                    "(name, designation, department, monthly_salary, payroll_status, school_id) " +
                    "VALUES ('Eve','Teacher','Academics',40000,'ACTIVE',20)"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }

    // ---- zone_school_mappings (Group C — bypass-only) ----

    @Test
    void zoneSchoolMappings_nonSuperadmin_seesZeroRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(0, countRows("tenant_school.zone_school_mappings"));
    }

    @Test
    void zoneSchoolMappings_superadmin_seesRow() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(1, countRows("tenant_school.zone_school_mappings"));
    }
}

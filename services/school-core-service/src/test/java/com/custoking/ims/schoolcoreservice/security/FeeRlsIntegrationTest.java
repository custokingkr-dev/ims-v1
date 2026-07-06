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

class FeeRlsIntegrationTest {

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
                .schemas("fee").defaultSchema("fee")
                .locations("classpath:db/migration/fee")
                .load().migrate();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // Unprivileged runtime role, subject to RLS.
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA fee TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA fee TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA fee TO app_rt");

            // Seed a fee_band (required by FK on fee_assignments). Owned by school 10 (arbitrary —
            // fee_bands RLS is exercised separately by FeeBandSchoolScopeRepoTest); this fixture
            // only needs the band to exist for the fee_assignments FK.
            st.execute("INSERT INTO fee.fee_bands(id, name, class_from, class_to, discount, academic_year_id, school_id) " +
                    "VALUES ('band-rls-1', 'Band RLS', 1, 12, 0.0, 'AY-2024', 10)");

            // Seed fee_assignments: school 10 (A) x2, school 20 (B) x1 — as owner (bypasses RLS).
            // Each row needs a unique (student_id, academic_year_id) combination.
            st.execute("INSERT INTO fee.fee_assignments" +
                    "(id, band_discount, manual_discount, surcharge, net_payable, paid_amount, " +
                    " student_id, band_id, academic_year_id, version, school_id) VALUES " +
                    "('fa-a1', 0.0, 0.0, 0.0, 5000, 0, 1, 'band-rls-1', 'AY-2024', 0, 10)," +
                    "('fa-a2', 0.0, 0.0, 0.0, 5000, 0, 2, 'band-rls-1', 'AY-2024', 0, 10)," +
                    "('fa-b1', 0.0, 0.0, 0.0, 5000, 0, 3, 'band-rls-1', 'AY-2024', 0, 20)");

            // Seed payment_records: school 10 x2, school 20 x1 — as owner.
            // assignment_id is nullable; student_id is NOT NULL.
            st.execute("INSERT INTO fee.payment_records" +
                    "(id, amount, student_id, assignment_id, version, school_id) VALUES " +
                    "('pr-a1', 1000, 1, 'fa-a1', 0, 10)," +
                    "('pr-a2', 2000, 2, 'fa-a2', 0, 10)," +
                    "('pr-b1', 1500, 3, 'fa-b1', 0, 20)");
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

    private long countAssignments() throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM fee.fee_assignments")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countPaymentRecords() throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM fee.payment_records")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countAssignments());
    }

    @Test
    void schoolB_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, countAssignments());
    }

    @Test
    void superadmin_seesAll() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, countAssignments());
    }

    @Test
    void noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, countAssignments());
    }

    @Test
    void paymentRecords_schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countPaymentRecords());
    }

    @Test
    void withCheck_blocksCrossTenantInsert() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO fee.fee_assignments" +
                    "(id, band_discount, manual_discount, surcharge, net_payable, paid_amount, " +
                    " student_id, band_id, academic_year_id, version, school_id) " +
                    "VALUES ('fa-cross', 0.0, 0.0, 0.0, 5000, 0, 99, 'band-rls-1', 'AY-2024', 0, 20)"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }
}

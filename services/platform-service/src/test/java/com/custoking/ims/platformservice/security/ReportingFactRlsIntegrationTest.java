package com.custoking.ims.platformservice.security;

import com.custoking.ims.platformservice.persistence.AttendanceFactReadRepository;
import com.custoking.ims.platformservice.persistence.CatalogFactReadRepository;
import com.custoking.ims.platformservice.persistence.DimensionProjectionRepository;
import com.custoking.ims.platformservice.persistence.FeeFactReadRepository;
import com.custoking.ims.platformservice.persistence.FirefightingFactReadRepository;
import com.custoking.ims.platformservice.persistence.StudentReviewFactReadRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the RLS backstop added on the reporting fact/dim tables in
 * {@code reporting/V22__enable_rls_facts_dims.sql}, and — critically — that the
 * {@link ProjectorRls#allow(JdbcClient)} transaction-local bypass keeps every contextless
 * projector upsert working even though those same tables are now RLS-protected for ordinary
 * reads. Mirrors {@link ReportingRlsIntegrationTest}'s Testcontainers + app_rt NOBYPASSRLS +
 * TenantAwareDataSource harness.
 */
class ReportingFactRlsIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource ownerDs; // owner pool: bypasses RLS, used to seed/verify
    static DataSource appRt;   // app_rt pool wrapped by TenantAwareDataSource: subject to RLS
    static JdbcClient appRtJdbc;
    // Mirrors the real @Transactional proxy: binds ONE connection to the thread for the duration
    // of the callback, exactly like Spring's declarative transaction management does in the
    // running app, so ProjectorRls.allow()'s transaction-local set_config and the subsequent
    // upsert statement land on the same physical connection/transaction.
    static TransactionTemplate txTemplate;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping RLS integration test");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();

        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("reporting").defaultSchema("reporting")
                .locations("classpath:db/migration/reporting")
                .load().migrate();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA reporting TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA reporting TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA reporting TO app_rt");

            // Seed: school 10 (A) x2, school 20 (B) x1 per fact/dim table under test.
            st.execute("INSERT INTO reporting.fact_attendance_daily (id, school_id, attendance_date, present_count) VALUES " +
                    "('att-a1', 10, '2026-01-01', 10), ('att-a2', 10, '2026-01-02', 12), ('att-b1', 20, '2026-01-01', 8)");
            st.execute("INSERT INTO reporting.fact_fee_assignment (id, school_id, student_id, net_payable) VALUES " +
                    "('fee-a1', 10, 1, 1000), ('fee-a2', 10, 2, 2000), ('fee-b1', 20, 3, 3000)");
            st.execute("INSERT INTO reporting.dim_student (id, school_id, full_name) VALUES " +
                    "(101, 10, 'Student A1'), (102, 10, 'Student A2'), (201, 20, 'Student B1')");
        }

        HikariDataSource ownerPool = new HikariDataSource();
        ownerPool.setJdbcUrl(PG.getJdbcUrl());
        ownerPool.setUsername("owner");
        ownerPool.setPassword("owner");
        ownerPool.setMaximumPoolSize(2);
        ownerDs = ownerPool;

        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        pool.setMaximumPoolSize(4);
        appRt = new TenantAwareDataSource(pool);
        appRtJdbc = JdbcClient.create(appRt);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(appRt));
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (PG != null) PG.stop();
    }

    @AfterEach
    void clearCtx() { TenantContext.clear(); }

    // Filters to only the @BeforeAll-seeded rows (id LIKE 'seed%') so these isolation assertions
    // are immune to test-order pollution from the (3) projector-bypass tests below, which insert
    // their own 'proj-*' rows into the same shared tables.
    private long count(String table, String seedIdPrefix) throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM reporting." + table + " WHERE id LIKE '" + seedIdPrefix + "%'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countDimStudentSeed() throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM reporting.dim_student WHERE id < 1000")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countAsOwner(String sql) throws SQLException {
        try (Connection c = ownerDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // --- (1) isolation reads, representative fact + dim tables ---

    @Test
    void fact_attendance_daily_schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, count("fact_attendance_daily", "att-"));
    }

    @Test
    void fact_attendance_daily_schoolB_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, count("fact_attendance_daily", "att-"));
    }

    @Test
    void fact_attendance_daily_superadmin_seesAll() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, count("fact_attendance_daily", "att-"));
    }

    @Test
    void fact_attendance_daily_noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, count("fact_attendance_daily", "att-"));
    }

    @Test
    void fact_fee_assignment_isolation() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, count("fact_fee_assignment", "fee-"));
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, count("fact_fee_assignment", "fee-"));
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, count("fact_fee_assignment", "fee-"));
        TenantContext.clear();
        assertEquals(0, count("fact_fee_assignment", "fee-"));
    }

    @Test
    void dim_student_isolation() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countDimStudentSeed());
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, countDimStudentSeed());
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, countDimStudentSeed());
        TenantContext.clear();
        assertEquals(0, countDimStudentSeed());
    }

    // --- (2) WITH CHECK blocks cross-tenant raw insert ---

    @Test
    void withCheck_blocksCrossTenantInsert_factAttendanceDaily() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO reporting.fact_attendance_daily (id, school_id, attendance_date) " +
                    "VALUES ('mallory-1', 20, '2026-01-01')"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }

    @Test
    void withCheck_blocksCrossTenantInsert_factFeeAssignment() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO reporting.fact_fee_assignment (id, school_id, student_id) " +
                    "VALUES ('mallory-fee', 20, 999)"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }

    @Test
    void withCheck_blocksCrossTenantInsert_dimStudent() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO reporting.dim_student (id, school_id, full_name) " +
                    "VALUES (999, 20, 'Mallory')"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }

    // --- (3) CRITICAL: with NO TenantContext, the real projector upsert methods still succeed ---
    // (proves ProjectorRls.allow() is wired into every one of the 8 write methods; if a
    // projector forgot the bypass line, WITH CHECK would reject the write and these would fail.)

    @Test
    void attendanceFactRepository_upsert_succeedsWithNoTenantContext() throws Exception {
        TenantContext.clear();
        AttendanceFactReadRepository repo = new AttendanceFactReadRepository(appRtJdbc);
        txTemplate.executeWithoutResult(status -> repo
                .upsert("proj-att-1", 10L, java.time.LocalDate.of(2026, 2, 1), "class-1", "sec-1",
                        "ay-1", 20, 2, 0, 0, 22));
        assertEquals(1, countAsOwner("SELECT count(*) FROM reporting.fact_attendance_daily WHERE id = 'proj-att-1'"));
    }

    @Test
    void feeFactRepository_upsertFeeAssignment_succeedsWithNoTenantContext() throws Exception {
        TenantContext.clear();
        FeeFactReadRepository repo = new FeeFactReadRepository(appRtJdbc);
        txTemplate.executeWithoutResult(status -> repo
                .upsertFeeAssignment("proj-fee-1", 1L, 10L, "ay-1", 5000L, 1000L, 4000L, "PARTIAL",
                        OffsetDateTime.now()));
        assertEquals(1, countAsOwner("SELECT count(*) FROM reporting.fact_fee_assignment WHERE id = 'proj-fee-1'"));
    }

    @Test
    void feeFactRepository_upsertPayment_succeedsWithNoTenantContext() throws Exception {
        TenantContext.clear();
        FeeFactReadRepository repo = new FeeFactReadRepository(appRtJdbc);
        txTemplate.executeWithoutResult(status -> repo
                .upsertPayment("proj-pay-1", "proj-fee-1", 10L, 1L, 1000L, OffsetDateTime.now()));
        assertEquals(1, countAsOwner("SELECT count(*) FROM reporting.fact_payment WHERE id = 'proj-pay-1'"));
    }

    @Test
    void catalogFactRepository_upsert_succeedsWithNoTenantContext() throws Exception {
        TenantContext.clear();
        CatalogFactReadRepository repo = new CatalogFactReadRepository(appRtJdbc);
        txTemplate.executeWithoutResult(status -> repo
                .upsert("proj-cat-1", 10L, "STATIONERY", "PLACED", 1000L, "APPROVED", null,
                        OffsetDateTime.now(), null, "PENDING", "notes"));
        assertEquals(1, countAsOwner("SELECT count(*) FROM reporting.fact_catalog_order WHERE id = 'proj-cat-1'"));
    }

    @Test
    void firefightingFactRepository_upsert_succeedsWithNoTenantContext() throws Exception {
        TenantContext.clear();
        FirefightingFactReadRepository repo = new FirefightingFactReadRepository(appRtJdbc);
        txTemplate.executeWithoutResult(status -> repo
                .upsert("proj-ff-1", "Extinguisher refill", "MAINTENANCE", "HIGH", "OPEN", 5000L, 10L,
                        null, null, OffsetDateTime.now(), null, null, null, null, null, null,
                        OffsetDateTime.now()));
        assertEquals(1, countAsOwner("SELECT count(*) FROM reporting.fact_firefighting_request WHERE code = 'proj-ff-1'"));
    }

    @Test
    void studentReviewFactRepository_upsert_succeedsWithNoTenantContext() throws Exception {
        TenantContext.clear();
        StudentReviewFactReadRepository repo = new StudentReviewFactReadRepository(appRtJdbc);
        txTemplate.executeWithoutResult(status -> repo.upsert("proj-rev-1", 10L, "campaign-1", "PENDING"));
        assertEquals(1, countAsOwner("SELECT count(*) FROM reporting.fact_student_review_item WHERE id = 'proj-rev-1'"));
    }

    @Test
    void studentReviewFactRepository_updateCampaignStatus_succeedsWithNoTenantContext() throws Exception {
        TenantContext.clear();
        StudentReviewFactReadRepository repo = new StudentReviewFactReadRepository(appRtJdbc);
        txTemplate.executeWithoutResult(status -> repo.upsert("proj-rev-2", 10L, "campaign-2", "PENDING"));
        txTemplate.executeWithoutResult(status -> repo.updateCampaignStatus("campaign-2", "COMPLETED"));
        assertEquals(1, countAsOwner(
                "SELECT count(*) FROM reporting.fact_student_review_item WHERE id = 'proj-rev-2' AND campaign_status = 'COMPLETED'"));
    }

    @Test
    void dimensionProjectionRepository_upsertSection_succeedsWithNoTenantContext() throws Exception {
        TenantContext.clear();
        DimensionProjectionRepository repo = new DimensionProjectionRepository(appRtJdbc);
        txTemplate.executeWithoutResult(status -> repo
                .upsertSection("proj-sec-1", "Section A", 10L, "class-1", "Class 1", true, "Teacher A"));
        assertEquals(1, countAsOwner("SELECT count(*) FROM reporting.dim_section WHERE id = 'proj-sec-1'"));
    }

    @Test
    void dimensionProjectionRepository_upsertStudent_succeedsWithNoTenantContext() throws Exception {
        TenantContext.clear();
        DimensionProjectionRepository repo = new DimensionProjectionRepository(appRtJdbc);
        txTemplate.executeWithoutResult(status -> repo
                .upsertStudent(9001L, 10L, "adm-1", "Student Proj", "1", "class-1", "sec-1",
                        "parent@x", "9999999999", true, java.math.BigDecimal.valueOf(95.5), "Father Proj"));
        assertEquals(1, countAsOwner("SELECT count(*) FROM reporting.dim_student WHERE id = 9001"));
    }
}

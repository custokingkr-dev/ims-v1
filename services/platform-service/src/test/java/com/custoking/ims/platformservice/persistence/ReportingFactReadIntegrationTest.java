package com.custoking.ims.platformservice.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the SP7 (read-rewrite, phase 1) swap of the fully-unblocked reporting reads onto the
 * local {@code reporting.fact_*} tables: {@code vendorDues}, {@code reorderSignals} and the
 * school-scoped {@code commandCenterSummary} KPIs must read ONLY the reporting schema.
 *
 * <p>The database migrates ONLY {@code reporting} — the {@code catalog}/{@code fee}/
 * {@code firefighting}/{@code attendance}/{@code student}/{@code tenant_school} schemas are never
 * created — so any surviving cross-schema read would fail with "relation does not exist". Passing
 * proves these reads are decoupled.
 */
class ReportingFactReadIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingReadRepository reporting;

    @BeforeAll
    static void setUpContainer() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping reporting fact read-swap integration test");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("reporting").defaultSchema("reporting")
                .locations("classpath:db/migration/reporting")
                .load().migrate();
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("owner");
        pool.setPassword("owner");
        pool.setMaximumPoolSize(2);
        dataSource = pool;
        jdbcClient = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        reporting = new ReportingReadRepository(jdbcClient);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String t : List.of("fact_catalog_order", "fact_firefighting_request", "fact_payment",
                    "fact_fee_assignment", "fact_attendance_daily", "command_center_actions")) {
                st.execute("TRUNCATE reporting." + t);
            }
        }
    }

    private void seedCatalogOrder(String id, long schoolId, String category, String status, long totalPaise) {
        jdbcClient.sql("""
                INSERT INTO reporting.fact_catalog_order (id, school_id, category, status, total_amount, created_at, updated_at)
                VALUES (:id, :s, :cat, :st, :amt, now(), now())
                """).param("id", id).param("s", schoolId).param("cat", category).param("st", status).param("amt", totalPaise).update();
    }

    private void seedFirefighting(String code, long schoolId, String status, Long winnerAmount) {
        jdbcClient.sql("""
                INSERT INTO reporting.fact_firefighting_request (code, title, category, status, estimated_budget, school_id, winner_amount, created_at, updated_at)
                VALUES (:c, :c, 'PLUMBING', :st, 9000, :s, :wa, now(), now())
                """).param("c", code).param("st", status).param("s", schoolId).param("wa", winnerAmount).update();
    }

    @Test
    void vendorDues_readsFactsOnly() {
        seedCatalogOrder("ord-1", 100L, "BOOKS", "APPROVED", 5000L);   // unpaid → a due
        seedCatalogOrder("ord-2", 100L, "BOOKS", "PROCESSING", 999L);  // not APPROVED/FULFILLED → excluded
        seedFirefighting("ff-1", 100L, "APPROVED", 8000L);             // unpaid winner → a due
        seedFirefighting("ff-2", 100L, "AWAITING_BURSAR", null);       // not APPROVED / no winner → excluded

        Map<String, Object> dues = reporting.vendorDues(100L);
        assertEquals(1L, dues.get("catalogOrderCount"));
        assertEquals(5000L, dues.get("catalogOrderTotalPaise"));
        assertEquals(1L, dues.get("firefightingCount"));
        assertEquals(8000L, dues.get("firefightingTotalPaise"));
        assertEquals(13000L, dues.get("totalDuesPaise"));
        assertEquals(2, ((List<?>) dues.get("items")).size());
    }

    @Test
    void reorderSignals_readsFactCatalogOnly() {
        seedCatalogOrder("ord-1", 100L, "BOOKS", "APPROVED", 5000L);
        Map<String, Object> signals = reporting.reorderSignals(100L);
        // one category present; the method must run purely off reporting.fact_catalog_order
        assertEquals(1, ((List<?>) signals.get("items")).size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void commandCenterSummary_schoolScope_kpisReadFactsOnly() {
        seedCatalogOrder("ord-1", 100L, "BOOKS", "PROCESSING", 4000L);            // 1 active order
        seedFirefighting("ff-1", 100L, "APPROVED", 8000L);                        // open (non-FULFILLED)
        seedFirefighting("ff-2", 100L, "AWAITING_BURSAR", null);                  // open + pending approval
        jdbcClient.sql("""
                INSERT INTO reporting.fact_payment (id, assignment_id, school_id, student_id, amount, paid_at, updated_at)
                VALUES ('pay-1', 'fa-1', 100, 1, 12000, now(), now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_fee_assignment (id, student_id, school_id, academic_year_id, net_payable, paid_amount, due_amount, status, updated_at)
                VALUES ('fa-1', 1, 100, 'ay_2025_26', 10000, 3000, 7000, 'PARTIAL', now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_attendance_daily (id, school_id, attendance_date, academic_year_id, present_count, total_enrolled, updated_at)
                VALUES ('ad-1', 100, CURRENT_DATE, 'ay_2025_26', 5, 10, now())
                """).update();

        Map<String, Object> summary = reporting.commandCenterSummary(100L, false);
        List<Map<String, Object>> kpis = (List<Map<String, Object>>) summary.get("kpis");
        assertEquals("2", kpiValue(kpis, "open_firefighting"));      // both ff rows are non-FULFILLED
        assertEquals("1", kpiValue(kpis, "orders_in_progress"));     // ord-1 PROCESSING
        assertEquals("1 sections", kpiValue(kpis, "attendance_today"));
    }

    private static String kpiValue(List<Map<String, Object>> kpis, String key) {
        return (String) kpis.stream().filter(k -> key.equals(k.get("key"))).findFirst().orElseThrow().get("value");
    }
}

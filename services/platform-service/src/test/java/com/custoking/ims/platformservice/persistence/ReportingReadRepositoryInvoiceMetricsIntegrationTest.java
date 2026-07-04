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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the Phase 3 reporting-outbox spike's Task 4 read-swap: {@link ReportingReadRepository#invoiceStats(Long)}
 * must return equivalent sentThisMonth/paid/pending/totalInvoiced values whether sourced from the legacy
 * cross-schema {@code billing.superadmin_invoices} table or the new same-schema
 * {@code reporting.billing_invoice_read} projection populated by the outbox pipeline (Tasks 1-3).
 *
 * This test seeds ONLY {@code reporting.billing_invoice_read} (no billing schema at all is migrated here),
 * so it fails while the repository still queries {@code billing.superadmin_invoices} and passes once the
 * queries are swapped to read the reporting-local projection.
 */
class ReportingReadRepositoryInvoiceMetricsIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingReadRepository reporting;

    @BeforeAll
    static void setUpContainer() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping invoice metrics read-swap integration test");
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
            st.execute("TRUNCATE reporting.billing_invoice_read");
        }
    }

    private void seedInvoice(String id, Long schoolId, String status, String total) {
        jdbcClient.sql("""
                        INSERT INTO reporting.billing_invoice_read (id, school_id, status, total, occurred_at, updated_at)
                        VALUES (:id, :schoolId, :status, :total, now(), now())
                        """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("status", status)
                .param("total", new java.math.BigDecimal(total))
                .update();
    }

    @Test
    void invoiceStats_global_aggregatesAcrossAllSchoolsFromReadModel() {
        seedInvoice("inv-1", 10L, "Paid", "1500.00");
        seedInvoice("inv-2", 10L, "Awaiting Payment", "2500.00");
        seedInvoice("inv-3", 20L, "Paid", "3000.00");

        Map<String, Object> stats = reporting.invoiceStats(null);

        assertEquals(3L, stats.get("sentThisMonth"));
        assertEquals(2L, stats.get("paid"));
        assertEquals(1L, stats.get("pending"));
        assertEquals(7000L, ((Number) stats.get("totalInvoiced")).longValue());
    }

    @Test
    void invoiceStats_perSchool_filtersToSchoolIdFromReadModel() {
        seedInvoice("inv-1", 10L, "Paid", "1500.00");
        seedInvoice("inv-2", 10L, "Awaiting Payment", "2500.00");
        seedInvoice("inv-3", 20L, "Paid", "3000.00");

        Map<String, Object> stats = reporting.invoiceStats(10L);

        assertEquals(2L, stats.get("sentThisMonth"));
        assertEquals(1L, stats.get("paid"));
        assertEquals(1L, stats.get("pending"));
        assertEquals(4000L, ((Number) stats.get("totalInvoiced")).longValue());

        Map<String, Object> otherSchoolStats = reporting.invoiceStats(20L);
        assertEquals(1L, otherSchoolStats.get("sentThisMonth"));
        assertEquals(1L, otherSchoolStats.get("paid"));
        assertEquals(0L, otherSchoolStats.get("pending"));
        assertEquals(3000L, ((Number) otherSchoolStats.get("totalInvoiced")).longValue());
    }
}

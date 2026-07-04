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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the Phase 3 reporting-outbox spike's Task 4b read-swap: {@link ReportingReadRepository#invoices}
 * must return the identical {@link ReportingReadRepository.InvoiceRow} shape whether sourced from the
 * legacy cross-schema {@code billing.superadmin_invoices} table or the new same-schema
 * {@code reporting.billing_invoice_read} projection, now widened to all 15 invoice columns by V10.
 *
 * This test seeds ONLY {@code reporting.billing_invoice_read} (no billing schema at all is migrated
 * here) with realistic, non-trivial money amounts (to catch any precision loss on rate/amount/gst/total),
 * and asserts the school_id/status filters, ORDER BY created_at DESC, and LIMIT behavior all still hold.
 */
class ReportingReadRepositoryInvoiceListIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingReadRepository reporting;

    @BeforeAll
    static void setUpContainer() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping invoice list read-swap integration test");
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

    private void seedFullInvoice(String id, String orderRef, String school, Long schoolId, String description,
                                  int qty, long rate, long amount, long gstAmount, String total, String status,
                                  String issuedAt, String dueAt, String notes, OffsetDateTime createdAt) {
        jdbcClient.sql("""
                        INSERT INTO reporting.billing_invoice_read (
                            id, order_ref, school, school_id, description, qty, rate, amount,
                            gst_amount, total, status, issued_at, due_at, notes, created_at,
                            occurred_at, updated_at
                        ) VALUES (
                            :id, :orderRef, :school, :schoolId, :description, :qty, :rate, :amount,
                            :gstAmount, :total, :status, :issuedAt, :dueAt, :notes, :createdAt,
                            now(), now()
                        )
                        """)
                .param("id", id)
                .param("orderRef", orderRef)
                .param("school", school)
                .param("schoolId", schoolId)
                .param("description", description)
                .param("qty", qty)
                .param("rate", rate)
                .param("amount", amount)
                .param("gstAmount", gstAmount)
                .param("total", new BigDecimal(total))
                .param("status", status)
                .param("issuedAt", issuedAt)
                .param("dueAt", dueAt)
                .param("notes", notes)
                .param("createdAt", createdAt)
                .update();
    }

    @Test
    void invoices_returnsAllFifteenColumns_withNoPrecisionLoss() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        seedFullInvoice("inv-1", "ORD-1", "Greenwood High", 10L, "Annual plan",
                7, 123456789L, 864197523L, 103703703L, "967901226", "Paid",
                "2026-01-01", "2026-01-15", "GST invoice for annual plan", createdAt);

        List<ReportingReadRepository.InvoiceRow> rows = reporting.invoices(10L, null, 10);

        assertEquals(1, rows.size());
        ReportingReadRepository.InvoiceRow row = rows.get(0);
        assertEquals("inv-1", row.id());
        assertEquals("ORD-1", row.orderRef());
        assertEquals("Greenwood High", row.school());
        assertEquals(10L, row.schoolId());
        assertEquals("Annual plan", row.description());
        assertEquals(7, row.qty());
        assertEquals(123456789L, row.rate());
        assertEquals(864197523L, row.amount());
        assertEquals(103703703L, row.gstAmount());
        assertEquals(967901226L, row.total());
        assertEquals("Paid", row.status());
        assertEquals("2026-01-01", row.issuedAt());
        assertEquals("2026-01-15", row.dueAt());
        assertEquals("GST invoice for annual plan", row.notes());
        assertEquals(createdAt.toInstant(), row.createdAt().toInstant());
    }

    @Test
    void invoices_filtersBySchoolIdAndStatus_ordersByCreatedAtDesc_andRespectsLimit() {
        OffsetDateTime t1 = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-01-02T10:00:00Z");
        OffsetDateTime t3 = OffsetDateTime.parse("2026-01-03T10:00:00Z");

        seedFullInvoice("inv-1", "ORD-1", "School A", 10L, "d1", 1, 1000L, 1000L, 120L, "1120",
                "Paid", "2026-01-01", "2026-01-15", null, t1);
        seedFullInvoice("inv-2", "ORD-2", "School A", 10L, "d2", 1, 2000L, 2000L, 240L, "2240",
                "Awaiting payment", "2026-01-02", "2026-01-16", null, t2);
        seedFullInvoice("inv-3", "ORD-3", "School B", 20L, "d3", 1, 3000L, 3000L, 360L, "3360",
                "Paid", "2026-01-03", "2026-01-17", null, t3);

        List<ReportingReadRepository.InvoiceRow> schoolAInvoices = reporting.invoices(10L, null, 10);
        assertEquals(2, schoolAInvoices.size());
        // ORDER BY created_at DESC: inv-2 (t2) before inv-1 (t1).
        assertEquals("inv-2", schoolAInvoices.get(0).id());
        assertEquals("inv-1", schoolAInvoices.get(1).id());

        List<ReportingReadRepository.InvoiceRow> paidOnly = reporting.invoices(null, "Paid", 10);
        assertEquals(2, paidOnly.size());
        assertEquals("inv-3", paidOnly.get(0).id());
        assertEquals("inv-1", paidOnly.get(1).id());

        List<ReportingReadRepository.InvoiceRow> limited = reporting.invoices(null, null, 1);
        assertEquals(1, limited.size());
        assertEquals("inv-3", limited.get(0).id());
    }
}

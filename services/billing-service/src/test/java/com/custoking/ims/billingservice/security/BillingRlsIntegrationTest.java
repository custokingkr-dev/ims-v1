package com.custoking.ims.billingservice.security;

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

/**
 * Proves branch-keyed RLS on billing_customers/billing_invoices/billing_payments (mirrors
 * school-core's FeeRlsIntegrationTest), and that the deliberately-excluded outbox_events table
 * remains fully readable/writable with NO TenantContext set — the OutboxRelay runs on a
 * @Scheduled thread with no tenant context, so RLS on that table would silently stop the relay.
 */
class BillingRlsIntegrationTest {

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
                .schemas("billing").defaultSchema("billing")
                .locations("classpath:db/migration")
                .load().migrate();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // Unprivileged runtime role, subject to RLS.
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA billing TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA billing TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA billing TO app_rt");

            // Seed billing_customers: branch 10 x2, branch 20 x1 — as owner (bypasses RLS).
            st.execute("INSERT INTO billing.billing_customers(code, name, branch_id, branch_name) VALUES " +
                    "('CUST-A1', 'Customer A1', 10, 'Branch A')," +
                    "('CUST-A2', 'Customer A2', 10, 'Branch A')," +
                    "('CUST-B1', 'Customer B1', 20, 'Branch B')");

            // Seed billing_invoices: branch 10 x2 (against the two branch-10 customers),
            // branch 20 x1 (against the branch-20 customer) — as owner.
            st.execute("INSERT INTO billing.billing_invoices" +
                    "(invoice_no, customer_id, branch_id, branch_name, invoice_date, due_date, " +
                    " subtotal, discount_amount, tax_amount, grand_total, balance_amount, " +
                    " status, payment_status, approval_status) " +
                    "SELECT 'INV-A1', id, 10, 'Branch A', current_date, current_date, 1000, 0, 0, 1000, 1000, " +
                    "       'OPEN', 'UNPAID', 'APPROVED' FROM billing.billing_customers WHERE code = 'CUST-A1'");
            st.execute("INSERT INTO billing.billing_invoices" +
                    "(invoice_no, customer_id, branch_id, branch_name, invoice_date, due_date, " +
                    " subtotal, discount_amount, tax_amount, grand_total, balance_amount, " +
                    " status, payment_status, approval_status) " +
                    "SELECT 'INV-A2', id, 10, 'Branch A', current_date, current_date, 2000, 0, 0, 2000, 2000, " +
                    "       'OPEN', 'UNPAID', 'APPROVED' FROM billing.billing_customers WHERE code = 'CUST-A2'");
            st.execute("INSERT INTO billing.billing_invoices" +
                    "(invoice_no, customer_id, branch_id, branch_name, invoice_date, due_date, " +
                    " subtotal, discount_amount, tax_amount, grand_total, balance_amount, " +
                    " status, payment_status, approval_status) " +
                    "SELECT 'INV-B1', id, 20, 'Branch B', current_date, current_date, 1500, 0, 0, 1500, 1500, " +
                    "       'OPEN', 'UNPAID', 'APPROVED' FROM billing.billing_customers WHERE code = 'CUST-B1'");

            // Seed billing_payments: branch 10 x2, branch 20 x1 — as owner.
            st.execute("INSERT INTO billing.billing_payments" +
                    "(invoice_id, branch_id, branch_name, payment_date, amount, payment_mode, received_by) " +
                    "SELECT id, 10, 'Branch A', current_date, 500, 'CASH', 'tester' " +
                    "FROM billing.billing_invoices WHERE invoice_no = 'INV-A1'");
            st.execute("INSERT INTO billing.billing_payments" +
                    "(invoice_id, branch_id, branch_name, payment_date, amount, payment_mode, received_by) " +
                    "SELECT id, 10, 'Branch A', current_date, 700, 'CASH', 'tester' " +
                    "FROM billing.billing_invoices WHERE invoice_no = 'INV-A2'");
            st.execute("INSERT INTO billing.billing_payments" +
                    "(invoice_id, branch_id, branch_name, payment_date, amount, payment_mode, received_by) " +
                    "SELECT id, 20, 'Branch B', current_date, 900, 'CASH', 'tester' " +
                    "FROM billing.billing_invoices WHERE invoice_no = 'INV-B1'");

            // Seed outbox_events: no tenant column semantics matter here, just needs rows to exist.
            st.execute("INSERT INTO billing.outbox_events" +
                    "(event_key, event_type, aggregate_type, aggregate_id, school_id, payload) VALUES " +
                    "('evt-1', 'billing.invoice-upserted.v1', 'BillingInvoice', 'evt-1', 10, '{}'::jsonb)");
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

    private long count(String table) throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM billing." + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void branch10_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, count("billing_customers"));
        assertEquals(2, count("billing_invoices"));
        assertEquals(2, count("billing_payments"));
    }

    @Test
    void branch20_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, count("billing_customers"));
        assertEquals(1, count("billing_invoices"));
        assertEquals(1, count("billing_payments"));
    }

    @Test
    void superadmin_seesAll() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, count("billing_customers"));
        assertEquals(3, count("billing_invoices"));
        assertEquals(3, count("billing_payments"));
    }

    @Test
    void noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, count("billing_customers"));
        assertEquals(0, count("billing_invoices"));
        assertEquals(0, count("billing_payments"));
    }

    @Test
    void withCheck_blocksCrossBranchInsert() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO billing.billing_customers(code, name, branch_id, branch_name) " +
                    "VALUES ('CUST-CROSS', 'Cross Customer', 20, 'Branch B')"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }

    @Test
    void outboxEvents_remainsFullyAccessible_withNoTenantContext() throws Exception {
        TenantContext.clear();
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM billing.outbox_events")) {
                rs.next();
                assertEquals(1, rs.getLong(1), "outbox_events must remain visible with no TenantContext");
            }
            int updated = st.executeUpdate(
                    "UPDATE billing.outbox_events SET published_at = now() WHERE event_key = 'evt-1'");
            assertEquals(1, updated, "outbox_events must remain updatable with no TenantContext");
        }
    }
}

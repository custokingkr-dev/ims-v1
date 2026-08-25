package com.custoking.ims.billingservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyInvoiceConsolidationIntegrationTest {

    static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void migrate() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "owner", "owner")
                .schemas("billing")
                .defaultSchema("billing")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void eligibleLegacyInvoiceIsMirroredAndUpdatedIdempotently() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO billing.superadmin_invoices
                        (id, order_ref, school, school_id, description, qty, rate, amount,
                         gst_amount, total, status, issued_at, due_at, notes, created_at)
                    VALUES
                        ('INV-LEGACY-1', 'ORDER-1', 'Mapped School', 42, 'Books', 2, 500,
                         1000, 120, 1120, 'Paid', '2026-08-01', '2026-08-15', 'legacy', now())
                    """);

            assertEquals(1, scalar(statement, """
                    SELECT count(*) FROM billing.billing_invoices
                    WHERE legacy_source = 'superadmin_invoices'
                      AND legacy_source_id = 'INV-LEGACY-1'
                    """));
            assertEquals(1120, scalar(statement, """
                    SELECT grand_total FROM billing.billing_invoices
                    WHERE legacy_source_id = 'INV-LEGACY-1'
                    """));
            assertEquals(1, scalar(statement, """
                    SELECT count(*) FROM billing.billing_payments
                    WHERE legacy_source_id = 'INV-LEGACY-1' AND amount = 1120
                    """));

            statement.execute("""
                    UPDATE billing.superadmin_invoices
                    SET qty = 3, amount = 1500, gst_amount = 180, total = 1680
                    WHERE id = 'INV-LEGACY-1'
                    """);

            assertEquals(1, scalar(statement, """
                    SELECT count(*) FROM billing.billing_invoices
                    WHERE legacy_source_id = 'INV-LEGACY-1'
                    """));
            assertEquals(1680, scalar(statement, """
                    SELECT grand_total FROM billing.billing_invoices
                    WHERE legacy_source_id = 'INV-LEGACY-1'
                    """));
            assertEquals(1680, scalar(statement, """
                    SELECT line_total FROM billing.billing_invoice_items
                    WHERE legacy_source_id = 'INV-LEGACY-1'
                    """));

            statement.execute("""
                    UPDATE billing.superadmin_invoices
                    SET status = 'Awaiting payment'
                    WHERE id = 'INV-LEGACY-1'
                    """);
            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM billing.billing_payments
                    WHERE legacy_source_id = 'INV-LEGACY-1'
                    """));
        }
    }

    @Test
    void missingSchoolIsReportedAndNeverGuessed() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO billing.superadmin_invoices
                        (id, school, school_id, description, qty, rate, amount,
                         gst_amount, total, status, issued_at, due_at, created_at)
                    VALUES
                        ('INV-UNMAPPED', 'Unknown', NULL, 'Unmapped', 1, 100,
                         100, 12, 112, 'Awaiting payment', 'not-a-date', NULL, now())
                    """);

            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM billing.billing_invoices
                    WHERE legacy_source_id = 'INV-UNMAPPED'
                    """));
            assertEquals(1, scalar(statement, """
                    SELECT count(*) FROM billing.legacy_invoice_migration_issues
                    WHERE legacy_invoice_id = 'INV-UNMAPPED'
                      AND issue = 'SCHOOL_MAPPING_REQUIRED'
                    """));
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "owner", "owner");
    }

    private static long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}

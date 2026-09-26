package com.custoking.ims.billingservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;

class BillingInvoiceStatisticsIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient jdbc;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    @BeforeAll static void start() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16");
        pg.start();
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .schemas("billing").defaultSchema("billing").locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }
    @AfterAll static void stop() { if (pg != null) pg.stop(); }
    @BeforeEach void clear() { jdbc.sql("DELETE FROM billing.superadmin_invoices").update(); }

    @Test void aggregatesAllInvoicesAndUsesIssueDateNotTheLastFiveHundredRows() {
        jdbc.sql("""
                INSERT INTO billing.superadmin_invoices
                    (id, qty, rate, amount, gst_amount, total, status, issued_at, created_at)
                SELECT 'INV-' || n, 1, 100, 100, 0, 100,
                       CASE WHEN n <= 501 THEN 'Paid' ELSE 'Awaiting payment' END,
                       CASE WHEN n <= 501 THEN '2026-08-31' ELSE '2026-09-01' END,
                       '2026-09-26T00:00:00Z'::timestamptz
                FROM generate_series(1, 503) n
                """).update();
        var stats = statistics("UTC").current();
        assertThat(stats).containsEntry("sentThisMonth", 2L).containsEntry("paid", 501L)
                .containsEntry("pending", 2L).containsEntry("totalInvoiced", 50300L)
                .containsEntry("periodStart", "2026-09-01").containsEntry("periodEndExclusive", "2026-10-01");
    }

    @Test void invalidLegacyDatesFallBackToCreationInTheReportingZoneAndEndIsExclusive() {
        jdbc.sql("""
                INSERT INTO billing.superadmin_invoices
                    (id, qty, rate, amount, gst_amount, total, issued_at, created_at)
                VALUES ('start',1,100,100,0,100,'not a date','2026-08-31T19:00:00Z'),
                       ('end',1,100,100,0,100,NULL,'2026-09-30T19:00:00Z'),
                       ('invalid',1,100,100,0,100,'2026-09-99','2026-09-10T00:00:00Z'),
                       ('issued',1,100,100,0,100,'2026-10-01','2026-09-10T00:00:00Z'),
                       ('start-extra',1,100,100,0,100,NULL,'2026-08-31T20:00:00Z')
                """).update();
        assertThat(statistics("Asia/Kolkata").current()).containsEntry("sentThisMonth", 3L)
                .containsEntry("reportingTimeZone", "Asia/Kolkata");
        assertThat(statistics("UTC").current()).containsEntry("sentThisMonth", 2L);
    }

    @Test void emptyDatasetReturnsZeroTotals() {
        assertThat(statistics("UTC").current()).containsEntry("sentThisMonth", 0L)
                .containsEntry("paid", 0L).containsEntry("pending", 0L).containsEntry("totalInvoiced", 0L);
    }
    private BillingInvoiceStatistics statistics(String zone) {
        return new BillingInvoiceStatistics(jdbc, "billing.superadmin_invoices", CLOCK, ZoneId.of(zone));
    }
}

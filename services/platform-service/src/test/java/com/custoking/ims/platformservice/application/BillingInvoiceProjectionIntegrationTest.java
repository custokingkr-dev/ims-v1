package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.projection.BillingInvoiceProjector;
import com.custoking.ims.platformservice.application.projection.ReferenceDimensionProjector;
import com.custoking.ims.platformservice.persistence.BillingInvoiceReadRepository;
import com.custoking.ims.platformservice.persistence.DimensionProjectionRepository;
import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository.ReportingEventInboxRecord;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the reporting.billing_invoice_read projection (Phase 3 reporting-outbox spike, Task 3):
 * a billing.invoice-upserted.v1 inbox event is projected into the read model, and the projection
 * is idempotent at two layers — the inbox dedups by eventId, and the read-model repository upsert
 * is ON CONFLICT (id) DO UPDATE, so a later event for the same invoice id updates in place rather
 * than duplicating a row.
 */
class BillingInvoiceProjectionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingEventInboxRepository inbox;
    private ReportingCommandRepository commands;
    private BillingInvoiceReadRepository billingInvoiceRead;
    private ReportingEventInboxProcessor processor;

    @BeforeAll
    static void setUpContainer() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping billing invoice projection integration test");
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
        inbox = new ReportingEventInboxRepository(jdbcClient);
        commands = new ReportingCommandRepository(jdbcClient);
        billingInvoiceRead = new BillingInvoiceReadRepository(jdbcClient);
        ObjectMapper objectMapper = new ObjectMapper();
        processor = new ReportingEventInboxProcessor(inbox, commands, java.util.List.of(
                new BillingInvoiceProjector(billingInvoiceRead, objectMapper),
                new ReferenceDimensionProjector(new DimensionProjectionRepository(jdbcClient), objectMapper)), 50);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE reporting.reporting_event_inbox, reporting.command_center_feed, reporting.billing_invoice_read");
        }
    }

    @AfterEach
    void cleanupPool() {
        // no-op: pool is shared/static across tests, truncated per test in setUp()
    }

    private void feedEvent(String eventId, String invoiceId, Long schoolId, String status, String total) {
        String payload = "{\"id\":\"" + invoiceId + "\",\"schoolId\":" + schoolId
                + ",\"status\":\"" + status + "\",\"total\":" + total + "}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"billing.invoice-upserted.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId,
                null,
                "billing.invoice-upserted.v1",
                "v1",
                "Invoice",
                invoiceId,
                schoolId,
                null,
                Optional.of(OffsetDateTime.now()),
                OffsetDateTime.now(),
                envelope,
                payload
        ));
    }

    private Map<String, Object> readRow(String invoiceId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT school_id, status, total FROM reporting.billing_invoice_read WHERE id = ?")) {
            ps.setString(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a row for invoice " + invoiceId);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schoolId", rs.getLong("school_id"));
                row.put("status", rs.getString("status"));
                row.put("total", rs.getBigDecimal("total"));
                return row;
            }
        }
    }

    private long countRows(String invoiceId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.billing_invoice_read WHERE id = ?")) {
            ps.setString(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void projectsInvoiceUpsertedEventIntoReadModel() throws Exception {
        String invoiceId = "inv-" + UUID.randomUUID();
        feedEvent(UUID.randomUUID().toString(), invoiceId, 10L, "SENT", "1500.00");

        int processed = processor.processBatch();

        assertEquals(1, processed);
        Map<String, Object> row = readRow(invoiceId);
        assertEquals(10L, row.get("schoolId"));
        assertEquals("SENT", row.get("status"));
        assertEquals(0, new BigDecimal("1500.00").compareTo((BigDecimal) row.get("total")));
    }

    @Test
    void repositoryUpsert_sameInvoiceIdTwice_updatesInPlace_doesNotDuplicate() {
        // Direct proof of the ON CONFLICT (id) DO UPDATE behaviour, independent of the inbox dedup layer.
        String invoiceId = "inv-" + UUID.randomUUID();
        OffsetDateTime firstOccurred = OffsetDateTime.now().minusHours(1);
        OffsetDateTime secondOccurred = OffsetDateTime.now();

        billingInvoiceRead.upsert(invoiceId, 10L, "SENT", new BigDecimal("1500.00"), firstOccurred);
        billingInvoiceRead.upsert(invoiceId, 10L, "PAID", new BigDecimal("1500.00"), secondOccurred);

        long count = jdbcClient.sql("SELECT count(*) FROM reporting.billing_invoice_read WHERE id = :id")
                .param("id", invoiceId)
                .query(Long.class)
                .single();
        String status = jdbcClient.sql("SELECT status FROM reporting.billing_invoice_read WHERE id = :id")
                .param("id", invoiceId)
                .query(String.class)
                .single();
        assertEquals(1, count);
        assertEquals("PAID", status);
    }

    @Test
    void sameEventIdReplayed_isDedupedByInbox_doesNotDuplicateOrReprocess() throws Exception {
        String invoiceId = "inv-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        feedEvent(eventId, invoiceId, 10L, "SENT", "1500.00");
        int firstBatch = processor.processBatch();
        assertEquals(1, firstBatch);

        // Replay: same eventId arrives again (e.g. at-least-once redelivery). The inbox's
        // ON CONFLICT (event_id) DO NOTHING means this is a no-op insert, and since the row
        // is already PROCESSED it won't be picked up by findReceivedForProjection again.
        feedEvent(eventId, invoiceId, 10L, "SENT", "1500.00");
        int secondBatch = processor.processBatch();

        assertEquals(0, secondBatch, "replayed event with the same eventId must not be reprocessed");
        assertEquals(1, countRows(invoiceId));
    }

    @Test
    void changedStatusEventForSameInvoiceId_updatesReadModel_doesNotDuplicate() throws Exception {
        String invoiceId = "inv-" + UUID.randomUUID();
        feedEvent(UUID.randomUUID().toString(), invoiceId, 10L, "SENT", "1500.00");
        processor.processBatch();

        // A genuinely new event (new eventId) for the SAME invoice id, with a changed status.
        feedEvent(UUID.randomUUID().toString(), invoiceId, 10L, "PAID", "1500.00");
        processor.processBatch();

        assertEquals(1, countRows(invoiceId));
        Map<String, Object> row = readRow(invoiceId);
        assertEquals("PAID", row.get("status"));
    }

    /**
     * Task 4b: the outbox payload was widened from {id, schoolId, status, total} to the FULL
     * 15-column invoice shape. Proves the processor parses and stores every column, and that
     * replaying a later event for the same invoice id updates all 15 columns via
     * ON CONFLICT (id) DO UPDATE without duplicating the row.
     */
    private void feedFullEvent(String eventId, String invoiceId, Long schoolId, String status, String total) {
        String payload = "{"
                + "\"id\":\"" + invoiceId + "\","
                + "\"orderRef\":\"ORD-" + invoiceId + "\","
                + "\"school\":\"Test School\","
                + "\"schoolId\":" + schoolId + ","
                + "\"description\":\"Annual plan\","
                + "\"qty\":3,"
                + "\"rate\":100000,"
                + "\"amount\":300000,"
                + "\"gstAmount\":36000,"
                + "\"total\":" + total + ","
                + "\"status\":\"" + status + "\","
                + "\"issuedAt\":\"2026-01-01\","
                + "\"dueAt\":\"2026-01-15\","
                + "\"notes\":\"first note\","
                + "\"createdAt\":\"2026-01-01T10:00:00Z\""
                + "}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"billing.invoice-upserted.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId,
                null,
                "billing.invoice-upserted.v1",
                "v1",
                "Invoice",
                invoiceId,
                schoolId,
                null,
                Optional.of(OffsetDateTime.now()),
                OffsetDateTime.now(),
                envelope,
                payload
        ));
    }

    private Map<String, Object> readFullRow(String invoiceId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT order_ref, school, school_id, description, qty, rate, amount, gst_amount, "
                             + "total, status, issued_at, due_at, notes, created_at "
                             + "FROM reporting.billing_invoice_read WHERE id = ?")) {
            ps.setString(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a row for invoice " + invoiceId);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderRef", rs.getString("order_ref"));
                row.put("school", rs.getString("school"));
                row.put("schoolId", rs.getLong("school_id"));
                row.put("description", rs.getString("description"));
                row.put("qty", rs.getInt("qty"));
                row.put("rate", rs.getLong("rate"));
                row.put("amount", rs.getLong("amount"));
                row.put("gstAmount", rs.getLong("gst_amount"));
                row.put("total", rs.getBigDecimal("total"));
                row.put("status", rs.getString("status"));
                row.put("issuedAt", rs.getString("issued_at"));
                row.put("dueAt", rs.getString("due_at"));
                row.put("notes", rs.getString("notes"));
                row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                return row;
            }
        }
    }

    @Test
    void fullInvoicePayload_projectsAllFifteenColumns() throws Exception {
        String invoiceId = "inv-" + UUID.randomUUID();
        feedFullEvent(UUID.randomUUID().toString(), invoiceId, 10L, "SENT", "336000");

        int processed = processor.processBatch();

        assertEquals(1, processed);
        Map<String, Object> row = readFullRow(invoiceId);
        assertEquals("ORD-" + invoiceId, row.get("orderRef"));
        assertEquals("Test School", row.get("school"));
        assertEquals(10L, row.get("schoolId"));
        assertEquals("Annual plan", row.get("description"));
        assertEquals(3, row.get("qty"));
        assertEquals(100000L, row.get("rate"));
        assertEquals(300000L, row.get("amount"));
        assertEquals(36000L, row.get("gstAmount"));
        assertEquals(0, new BigDecimal("336000").compareTo((BigDecimal) row.get("total")));
        assertEquals("SENT", row.get("status"));
        assertEquals("2026-01-01", row.get("issuedAt"));
        assertEquals("2026-01-15", row.get("dueAt"));
        assertEquals("first note", row.get("notes"));
        assertEquals(OffsetDateTime.parse("2026-01-01T10:00:00Z"), row.get("createdAt"));
    }

    @Test
    void fullInvoicePayload_replayedWithChangedFields_updatesAllColumns_doesNotDuplicate() throws Exception {
        String invoiceId = "inv-" + UUID.randomUUID();
        feedFullEvent(UUID.randomUUID().toString(), invoiceId, 10L, "SENT", "336000");
        processor.processBatch();

        // A genuinely new event (new eventId) for the SAME invoice id, with several changed fields.
        String secondEventId = UUID.randomUUID().toString();
        String payload = "{"
                + "\"id\":\"" + invoiceId + "\","
                + "\"orderRef\":\"ORD-" + invoiceId + "\","
                + "\"school\":\"Renamed School\","
                + "\"schoolId\":10,"
                + "\"description\":\"Annual plan v2\","
                + "\"qty\":5,"
                + "\"rate\":100000,"
                + "\"amount\":500000,"
                + "\"gstAmount\":60000,"
                + "\"total\":560000,"
                + "\"status\":\"PAID\","
                + "\"issuedAt\":\"2026-01-01\","
                + "\"dueAt\":\"2026-01-15\","
                + "\"notes\":\"updated note\","
                + "\"createdAt\":\"2026-01-01T10:00:00Z\""
                + "}";
        String envelope = "{\"eventId\":\"" + secondEventId + "\",\"eventType\":\"billing.invoice-upserted.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                secondEventId, null, "billing.invoice-upserted.v1", "v1", "Invoice", invoiceId, 10L, null,
                Optional.of(OffsetDateTime.now()), OffsetDateTime.now(), envelope, payload));
        processor.processBatch();

        assertEquals(1, countRows(invoiceId));
        Map<String, Object> row = readFullRow(invoiceId);
        assertEquals("Renamed School", row.get("school"));
        assertEquals("Annual plan v2", row.get("description"));
        assertEquals(5, row.get("qty"));
        assertEquals(500000L, row.get("amount"));
        assertEquals(60000L, row.get("gstAmount"));
        assertEquals(0, new BigDecimal("560000").compareTo((BigDecimal) row.get("total")));
        assertEquals("PAID", row.get("status"));
        assertEquals("updated note", row.get("notes"));
    }
}

package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.projection.FeeFactProjector;
import com.custoking.ims.platformservice.persistence.FeeFactReadRepository;
import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository.ReportingEventInboxRecord;
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
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the reporting.fact_fee_assignment / fact_payment projections (Reporting Decoupling
 * SP2, fee): school-core fee events land in the reporting fact read-models via
 * ReportingEventInboxProcessor, and the projection is idempotent (inbox dedups by eventId,
 * FeeFactReadRepository upserts are ON CONFLICT (id) DO UPDATE). Mirrors
 * DimensionProjectionIntegrationTest's SP1 pattern.
 */
class FeeFactProjectionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingEventInboxRepository inbox;
    private ReportingCommandRepository commands;
    private FeeFactReadRepository facts;
    private ReportingEventInboxProcessor processor;

    @BeforeAll
    static void setUpContainer() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping fee fact projection integration test");
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
        facts = new FeeFactReadRepository(jdbcClient);
        ObjectMapper objectMapper = new ObjectMapper();
        processor = new ReportingEventInboxProcessor(inbox, commands, java.util.List.of(
                new FeeFactProjector(facts, objectMapper)), 50);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE reporting.reporting_event_inbox, reporting.command_center_feed, "
                    + "reporting.fact_fee_assignment, reporting.fact_payment");
        }
    }

    private void feedFeeAssignmentEvent(String eventId, String assignmentId, long schoolId,
                                         long netPayable, long paidAmount, String status) {
        String payload = "{\"id\":\"" + assignmentId + "\",\"studentId\":1,\"schoolId\":" + schoolId
                + ",\"academicYearId\":\"ay1\",\"netPayable\":" + netPayable + ",\"paidAmount\":" + paidAmount
                + ",\"dueAmount\":" + Math.max(0, netPayable - paidAmount) + ",\"status\":\"" + status + "\""
                + ",\"assignedAt\":\"2025-01-01T00:00:00Z\"}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"fee-assignment.upserted.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId, null, "fee-assignment.upserted.v1", "v1", "FeeAssignment", assignmentId, schoolId,
                null, Optional.of(OffsetDateTime.now()), OffsetDateTime.now(), envelope, payload));
    }

    private void feedPaymentEvent(String eventId, String paymentId, String assignmentId, long schoolId, long amount) {
        String payload = "{\"id\":\"" + paymentId + "\",\"assignmentId\":\"" + assignmentId + "\",\"schoolId\":" + schoolId
                + ",\"studentId\":1,\"amount\":" + amount + ",\"paidAt\":\"2025-01-01T00:00:00Z\"}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"payment.recorded.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId, null, "payment.recorded.v1", "v1", "Payment", paymentId, schoolId,
                null, Optional.of(OffsetDateTime.now()), OffsetDateTime.now(), envelope, payload));
    }

    @Test
    void projectsFeeAssignmentUpsertedEventIntoFactFeeAssignment() throws Exception {
        feedFeeAssignmentEvent(UUID.randomUUID().toString(), "fa-1", 7L, 500000, 0, "Overdue");

        int processed = processor.processBatch();

        assertEquals(1, processed);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT school_id, net_payable, paid_amount, due_amount, status, assigned_at FROM reporting.fact_fee_assignment WHERE id = ?")) {
            ps.setString(1, "fa-1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a fact_fee_assignment row for id fa-1");
                assertEquals(7L, rs.getLong("school_id"));
                assertEquals(500000L, rs.getLong("net_payable"));
                assertEquals(0L, rs.getLong("paid_amount"));
                assertEquals(500000L, rs.getLong("due_amount"));
                assertEquals("Overdue", rs.getString("status"));
                assertTrue(rs.getObject("assigned_at") != null, "expected assigned_at to be set");
            }
        }
    }

    @Test
    void projectsPaymentRecordedEventIntoFactPayment() throws Exception {
        feedPaymentEvent(UUID.randomUUID().toString(), "pay-1", "fa-1", 7L, 500000);

        int processed = processor.processBatch();

        assertEquals(1, processed);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT assignment_id, school_id, amount FROM reporting.fact_payment WHERE id = ?")) {
            ps.setString(1, "pay-1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a fact_payment row for id pay-1");
                assertEquals("fa-1", rs.getString("assignment_id"));
                assertEquals(7L, rs.getLong("school_id"));
                assertEquals(500000L, rs.getLong("amount"));
            }
        }
    }

    @Test
    void replayedFeeAssignmentEvent_isDedupedByInbox_lastWriteWinsOnUpsert() throws Exception {
        feedFeeAssignmentEvent(UUID.randomUUID().toString(), "fa-2", 7L, 500000, 0, "Overdue");
        processor.processBatch();

        // A later upsert (different eventId, same assignment id) reflects the newer state.
        feedFeeAssignmentEvent(UUID.randomUUID().toString(), "fa-2", 7L, 500000, 500000, "Paid");
        int processed = processor.processBatch();

        assertEquals(1, processed);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) AS cnt, MAX(status) AS status FROM reporting.fact_fee_assignment WHERE id = 'fa-2'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("cnt"), "upsert by id must not duplicate the row");
                assertEquals("Paid", rs.getString("status"));
            }
        }
    }

    @Test
    void feeFactEvents_projectButDoNotCreateCommandCenterFeedRows() throws Exception {
        String eventId = UUID.randomUUID().toString();
        feedFeeAssignmentEvent(eventId, "fa-3", 7L, 500000, 0, "Overdue");

        int processed = processor.processBatch();

        assertEquals(1, processed);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.command_center_feed WHERE source_type = 'EVENT_INBOX' AND source_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1), "fee fact events must not create command_center_feed rows");
            }
        }
    }
}

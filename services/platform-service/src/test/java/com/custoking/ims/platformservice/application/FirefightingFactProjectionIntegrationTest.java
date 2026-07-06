package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.projection.FirefightingFactProjector;
import com.custoking.ims.platformservice.persistence.FirefightingFactReadRepository;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the reporting.fact_firefighting_request projection (SP6 reporting-outbox
 * decoupling): a firefighting-request.upserted.v1 inbox event is projected into the read
 * model, and the projection is idempotent — the inbox dedups by eventId, and the read-model
 * repository upsert is ON CONFLICT (code) DO UPDATE, so a later event for the same request
 * code updates in place rather than duplicating a row.
 */
class FirefightingFactProjectionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingEventInboxRepository inbox;
    private ReportingCommandRepository commands;
    private FirefightingFactReadRepository firefightingFactRead;
    private ReportingEventInboxProcessor processor;

    @BeforeAll
    static void setUpContainer() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping firefighting fact projection integration test");
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
        firefightingFactRead = new FirefightingFactReadRepository(jdbcClient);
        ObjectMapper objectMapper = new ObjectMapper();
        processor = new ReportingEventInboxProcessor(inbox, commands, java.util.List.of(
                new FirefightingFactProjector(firefightingFactRead, objectMapper)), 50);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE reporting.reporting_event_inbox, reporting.command_center_feed, reporting.fact_firefighting_request");
        }
    }

    private void feedEvent(String eventId, String code, Long schoolId, String status, Long estimatedBudget) {
        String payload = "{\"code\":\"" + code + "\",\"title\":\"Fix generator\",\"category\":\"Services & AMC\","
                + "\"urgency\":\"HIGH\",\"status\":\"" + status + "\",\"estimatedBudget\":" + estimatedBudget
                + ",\"schoolId\":" + schoolId + ",\"createdAt\":\"2026-01-01T10:00:00Z\"}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"firefighting-request.upserted.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId,
                null,
                "firefighting-request.upserted.v1",
                "v1",
                "FirefightingRequest",
                code,
                schoolId,
                null,
                Optional.of(OffsetDateTime.now()),
                OffsetDateTime.now(),
                envelope,
                payload
        ));
    }

    private Map<String, Object> readRow(String code) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT school_id, status, estimated_budget, category, urgency "
                             + "FROM reporting.fact_firefighting_request WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a row for request " + code);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schoolId", rs.getLong("school_id"));
                row.put("status", rs.getString("status"));
                row.put("estimatedBudget", rs.getLong("estimated_budget"));
                row.put("category", rs.getString("category"));
                row.put("urgency", rs.getString("urgency"));
                return row;
            }
        }
    }

    private long countRows(String code) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.fact_firefighting_request WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void projectsFirefightingRequestUpsertedEventIntoReadModel() throws Exception {
        String code = "FF-" + UUID.randomUUID();
        feedEvent(UUID.randomUUID().toString(), code, 10L, "DRAFT", 5000L);

        int processed = processor.processBatch();

        assertEquals(1, processed);
        Map<String, Object> row = readRow(code);
        assertEquals(10L, row.get("schoolId"));
        assertEquals("DRAFT", row.get("status"));
        assertEquals(5000L, row.get("estimatedBudget"));
        assertEquals("Services & AMC", row.get("category"));
        assertEquals("HIGH", row.get("urgency"));
    }

    @Test
    void sameEventIdReplayed_isDedupedByInbox_doesNotDuplicateOrReprocess() throws Exception {
        String code = "FF-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        feedEvent(eventId, code, 10L, "DRAFT", 5000L);
        int firstBatch = processor.processBatch();
        assertEquals(1, firstBatch);

        feedEvent(eventId, code, 10L, "DRAFT", 5000L);
        int secondBatch = processor.processBatch();

        assertEquals(0, secondBatch, "replayed event with the same eventId must not be reprocessed");
        assertEquals(1, countRows(code));
    }

    @Test
    void changedStatusEventForSameCode_updatesReadModel_doesNotDuplicate() throws Exception {
        String code = "FF-" + UUID.randomUUID();
        feedEvent(UUID.randomUUID().toString(), code, 10L, "AWAITING_BURSAR", 5000L);
        processor.processBatch();

        feedEvent(UUID.randomUUID().toString(), code, 10L, "APPROVED", 5000L);
        processor.processBatch();

        assertEquals(1, countRows(code));
        Map<String, Object> row = readRow(code);
        assertEquals("APPROVED", row.get("status"));
    }

    @Test
    void firefightingEvent_isNotFeedWorthy_doesNotCreateCommandCenterFeedRow() throws Exception {
        String code = "FF-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        feedEvent(eventId, code, 10L, "DRAFT", 5000L);

        processor.processBatch();

        assertTrue(!commands.feedSourceExists("EVENT_INBOX", eventId),
                "firefighting-request.upserted.v1 must not create a command_center_feed row");
    }
}

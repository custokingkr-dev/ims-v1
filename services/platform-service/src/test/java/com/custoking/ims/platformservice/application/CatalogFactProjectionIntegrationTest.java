package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.projection.CatalogFactProjector;
import com.custoking.ims.platformservice.persistence.CatalogFactReadRepository;
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
 * Proves the reporting.fact_catalog_order projection (Reporting Decoupling SP4): school-core
 * catalog-order.upserted.v1 events land in the reporting fact read-model via
 * ReportingEventInboxProcessor, and the projection is idempotent — the inbox dedups by
 * eventId, and CatalogFactReadRepository upserts are ON CONFLICT (id) DO UPDATE, so replaying
 * the same event never duplicates a row. Mirrors DimensionProjectionIntegrationTest (SP1).
 */
class CatalogFactProjectionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingEventInboxRepository inbox;
    private ReportingCommandRepository commands;
    private CatalogFactReadRepository catalogFactRead;
    private ReportingEventInboxProcessor processor;

    @BeforeAll
    static void setUpContainer() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping catalog fact projection integration test");
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
        catalogFactRead = new CatalogFactReadRepository(jdbcClient);
        ObjectMapper objectMapper = new ObjectMapper();
        processor = new ReportingEventInboxProcessor(inbox, commands, java.util.List.of(
                new CatalogFactProjector(catalogFactRead, objectMapper)), 50);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE reporting.reporting_event_inbox, reporting.command_center_feed, "
                    + "reporting.fact_catalog_order");
        }
    }

    private void feedCatalogOrderEvent(String eventId, String orderId, long schoolId, String status) {
        String payload = "{\"id\":\"" + orderId + "\",\"schoolId\":" + schoolId + ",\"category\":\"STATIONERY\","
                + "\"status\":\"" + status + "\",\"totalAmount\":1180,\"superadminApprovalStatus\":\"PENDING\","
                + "\"designStatus\":\"NOT_REQUIRED\",\"notes\":\"Please expedite\"}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"catalog-order.upserted.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId,
                null,
                "catalog-order.upserted.v1",
                "v1",
                "CatalogOrder",
                orderId,
                schoolId,
                null,
                Optional.of(OffsetDateTime.now()),
                OffsetDateTime.now(),
                envelope,
                payload
        ));
    }

    private long countOrderRows(String orderId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.fact_catalog_order WHERE id = ?")) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void projectsCatalogOrderUpsertedEventIntoFactCatalogOrder() throws Exception {
        feedCatalogOrderEvent(UUID.randomUUID().toString(), "CK-1001", 7L, "DRAFT");

        int processed = processor.processBatch();

        assertEquals(1, processed);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT school_id, category, status, total_amount, notes FROM reporting.fact_catalog_order WHERE id = ?")) {
            ps.setString(1, "CK-1001");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a fact_catalog_order row for CK-1001");
                assertEquals(7L, rs.getLong("school_id"));
                assertEquals("STATIONERY", rs.getString("category"));
                assertEquals("DRAFT", rs.getString("status"));
                assertEquals(1180L, rs.getLong("total_amount"));
                assertEquals("Please expedite", rs.getString("notes"));
            }
        }
    }

    @Test
    void sameEventIdReplayed_isDedupedByInbox_doesNotDuplicateFactRow() throws Exception {
        String eventId = UUID.randomUUID().toString();
        feedCatalogOrderEvent(eventId, "CK-1002", 7L, "DRAFT");
        int firstBatch = processor.processBatch();
        assertEquals(1, firstBatch);

        feedCatalogOrderEvent(eventId, "CK-1002", 7L, "DRAFT");
        int secondBatch = processor.processBatch();

        assertEquals(0, secondBatch, "replayed event with the same eventId must not be reprocessed");
        assertEquals(1, countOrderRows("CK-1002"));
    }

    @Test
    void laterEventForSameOrder_upsertsLatestStatus() throws Exception {
        feedCatalogOrderEvent(UUID.randomUUID().toString(), "CK-1003", 7L, "DRAFT");
        processor.processBatch();

        feedCatalogOrderEvent(UUID.randomUUID().toString(), "CK-1003", 7L, "AWAITING_APPROVAL");
        int processed = processor.processBatch();

        assertEquals(1, processed);
        assertEquals(1, countOrderRows("CK-1003"));
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM reporting.fact_catalog_order WHERE id = ?")) {
            ps.setString(1, "CK-1003");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("AWAITING_APPROVAL", rs.getString("status"));
            }
        }
    }

    @Test
    void catalogOrderUpsertedEvent_projectsToFact_butDoesNotCreateCommandCenterFeedRow() throws Exception {
        String eventId = UUID.randomUUID().toString();
        feedCatalogOrderEvent(eventId, "CK-1004", 7L, "DRAFT");

        int processed = processor.processBatch();

        assertEquals(1, processed);
        assertEquals(1, countOrderRows("CK-1004"));
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.command_center_feed WHERE source_type = 'EVENT_INBOX' AND source_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1),
                        "catalog fact projection must not create command_center_feed rows");
            }
        }
    }
}

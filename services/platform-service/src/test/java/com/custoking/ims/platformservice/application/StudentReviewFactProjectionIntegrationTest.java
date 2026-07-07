package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.projection.StudentReviewProjector;
import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository.ReportingEventInboxRecord;
import com.custoking.ims.platformservice.persistence.StudentReviewFactReadRepository;
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
 * Proves the reporting.fact_student_review_item projection (Reporting Decoupling SP7,
 * student-review): school-core student-review events land in the reporting fact read-model via
 * ReportingEventInboxProcessor, and the projection is idempotent (inbox dedups by eventId,
 * StudentReviewFactReadRepository upserts are ON CONFLICT (id) DO UPDATE). Mirrors
 * FeeFactProjectionIntegrationTest's SP2 pattern.
 */
class StudentReviewFactProjectionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingEventInboxRepository inbox;
    private ReportingCommandRepository commands;
    private StudentReviewFactReadRepository facts;
    private ReportingEventInboxProcessor processor;

    @BeforeAll
    static void setUpContainer() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping student review fact projection integration test");
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
        facts = new StudentReviewFactReadRepository(jdbcClient);
        ObjectMapper objectMapper = new ObjectMapper();
        processor = new ReportingEventInboxProcessor(inbox, commands, java.util.List.of(
                new StudentReviewProjector(facts, objectMapper)), 50);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE reporting.reporting_event_inbox, reporting.command_center_feed, "
                    + "reporting.fact_student_review_item");
        }
    }

    private void feedReviewItemEvent(String eventId, String itemId, long schoolId, String campaignId, String status) {
        String payload = "{\"id\":\"" + itemId + "\",\"schoolId\":" + schoolId
                + ",\"campaignId\":\"" + campaignId + "\",\"status\":\"" + status + "\"}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"student-review-item.upserted.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId, null, "student-review-item.upserted.v1", "v1", "StudentReviewItem", itemId, schoolId,
                null, Optional.of(OffsetDateTime.now()), OffsetDateTime.now(), envelope, payload));
    }

    private void feedCampaignCompletedEvent(String eventId, long schoolId, String campaignId) {
        String payload = "{\"campaignId\":\"" + campaignId + "\",\"schoolId\":" + schoolId + ",\"status\":\"COMPLETED\"}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"student-review-campaign.completed.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId, null, "student-review-campaign.completed.v1", "v1", "StudentReviewCampaign", campaignId, schoolId,
                null, Optional.of(OffsetDateTime.now()), OffsetDateTime.now(), envelope, payload));
    }

    @Test
    void campaignCompletedEvent_flipsCampaignStatusForAllItemsOfThatCampaign() throws Exception {
        feedReviewItemEvent(UUID.randomUUID().toString(), "item-a", 7L, "camp-x", "PENDING");
        feedReviewItemEvent(UUID.randomUUID().toString(), "item-b", 7L, "camp-x", "COMPLETED");
        feedReviewItemEvent(UUID.randomUUID().toString(), "item-c", 7L, "camp-y", "PENDING");
        processor.processBatch();

        feedCampaignCompletedEvent(UUID.randomUUID().toString(), 7L, "camp-x");
        int processed = processor.processBatch();
        assertEquals(1, processed);

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT campaign_id, campaign_status FROM reporting.fact_student_review_item ORDER BY id")) {
            try (ResultSet rs = ps.executeQuery()) {
                int completedCamp = 0, activeCamp = 0;
                while (rs.next()) {
                    if ("camp-x".equals(rs.getString("campaign_id"))) {
                        assertEquals("COMPLETED", rs.getString("campaign_status"));
                        completedCamp++;
                    } else {
                        assertEquals("ACTIVE", rs.getString("campaign_status"));
                        activeCamp++;
                    }
                }
                assertEquals(2, completedCamp, "both camp-x items flip to COMPLETED");
                assertEquals(1, activeCamp, "camp-y stays ACTIVE");
            }
        }
    }

    @Test
    void projectsStudentReviewItemUpsertedEventIntoFactStudentReviewItem() throws Exception {
        feedReviewItemEvent(UUID.randomUUID().toString(), "item-1", 7L, "campaign-1", "PENDING");

        int processed = processor.processBatch();

        assertEquals(1, processed);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT school_id, campaign_id, status FROM reporting.fact_student_review_item WHERE id = ?")) {
            ps.setString(1, "item-1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a fact_student_review_item row for id item-1");
                assertEquals(7L, rs.getLong("school_id"));
                assertEquals("campaign-1", rs.getString("campaign_id"));
                assertEquals("PENDING", rs.getString("status"));
            }
        }
    }

    @Test
    void replayedReviewItemEvent_isDedupedByInbox_lastWriteWinsOnUpsert() throws Exception {
        feedReviewItemEvent(UUID.randomUUID().toString(), "item-2", 7L, "campaign-1", "PENDING");
        processor.processBatch();

        // A later upsert (different eventId, same item id) reflects the newer state.
        feedReviewItemEvent(UUID.randomUUID().toString(), "item-2", 7L, "campaign-1", "COMPLETED");
        int processed = processor.processBatch();

        assertEquals(1, processed);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) AS cnt, MAX(status) AS status FROM reporting.fact_student_review_item WHERE id = 'item-2'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("cnt"), "upsert by id must not duplicate the row");
                assertEquals("COMPLETED", rs.getString("status"));
            }
        }
    }

    @Test
    void reviewItemEvents_projectButDoNotCreateCommandCenterFeedRows() throws Exception {
        String eventId = UUID.randomUUID().toString();
        feedReviewItemEvent(eventId, "item-3", 7L, "campaign-1", "PENDING");

        int processed = processor.processBatch();

        assertEquals(1, processed);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.command_center_feed WHERE source_type = 'EVENT_INBOX' AND source_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1), "student-review fact events must not create command_center_feed rows");
            }
        }
    }
}

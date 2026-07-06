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
 * Proves the reporting.dim_school / dim_section / dim_academic_year projections (Reporting
 * Decoupling SP1, Task 5): school-core reference events land in the reporting dimension
 * read-models via ReportingEventInboxProcessor, and the projection is idempotent at two
 * layers — the inbox dedups by eventId, and DimensionProjectionRepository upserts are
 * ON CONFLICT (id) DO UPDATE, so replaying the same event never duplicates a row.
 */
class DimensionProjectionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingEventInboxRepository inbox;
    private ReportingCommandRepository commands;
    private BillingInvoiceReadRepository billingInvoiceRead;
    private DimensionProjectionRepository dims;
    private ReportingEventInboxProcessor processor;

    @BeforeAll
    static void setUpContainer() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping dimension projection integration test");
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
        dims = new DimensionProjectionRepository(jdbcClient);
        ObjectMapper objectMapper = new ObjectMapper();
        processor = new ReportingEventInboxProcessor(inbox, commands, java.util.List.of(
                new BillingInvoiceProjector(billingInvoiceRead, objectMapper),
                new ReferenceDimensionProjector(dims, objectMapper)), 50);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE reporting.reporting_event_inbox, reporting.command_center_feed, "
                    + "reporting.dim_school, reporting.dim_section, reporting.dim_academic_year");
        }
    }

    private void feedSchoolEvent(String eventId, long schoolId) {
        String payload = "{\"id\":" + schoolId + ",\"name\":\"S7\",\"shortCode\":\"S7\",\"active\":true}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"school.upserted.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId,
                null,
                "school.upserted.v1",
                "v1",
                "School",
                String.valueOf(schoolId),
                schoolId,
                null,
                Optional.of(OffsetDateTime.now()),
                OffsetDateTime.now(),
                envelope,
                payload
        ));
    }

    private long countSchoolRows(long schoolId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.dim_school WHERE id = ?")) {
            ps.setLong(1, schoolId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void projectsSchoolUpsertedEventIntoDimSchool() throws Exception {
        feedSchoolEvent(UUID.randomUUID().toString(), 7L);

        int processed = processor.processBatch();

        assertEquals(1, processed);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, short_code, active FROM reporting.dim_school WHERE id = ?")) {
            ps.setLong(1, 7L);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a dim_school row for id 7");
                assertEquals("S7", rs.getString("name"));
                assertEquals("S7", rs.getString("short_code"));
                assertTrue(rs.getBoolean("active"));
            }
        }
    }

    @Test
    void sameEventIdReplayed_isDedupedByInbox_doesNotDuplicateDimSchool() throws Exception {
        String eventId = UUID.randomUUID().toString();
        feedSchoolEvent(eventId, 7L);
        int firstBatch = processor.processBatch();
        assertEquals(1, firstBatch);

        // Replay: same eventId arrives again (e.g. at-least-once redelivery). The inbox's
        // ON CONFLICT (event_id) DO NOTHING means this is a no-op insert, and since the row
        // is already PROCESSED it won't be picked up by findReceivedForProjection again.
        feedSchoolEvent(eventId, 7L);
        int secondBatch = processor.processBatch();

        assertEquals(0, secondBatch, "replayed event with the same eventId must not be reprocessed");
        assertEquals(1, countSchoolRows(7L));
    }

    @Test
    void schoolUpsertedEvent_projectsToDimSchool_butDoesNotCreateCommandCenterFeedRow() throws Exception {
        String eventId = UUID.randomUUID().toString();
        feedSchoolEvent(eventId, 7L);

        int processed = processor.processBatch();

        assertEquals(1, processed);
        assertEquals(1, countSchoolRows(7L), "dimension projection must still happen for reference events");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.command_center_feed WHERE source_type = 'EVENT_INBOX' AND source_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1),
                        "dimension/reference events must not create command_center_feed rows");
            }
        }
    }
}

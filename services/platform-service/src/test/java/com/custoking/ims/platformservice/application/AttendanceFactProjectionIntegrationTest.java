package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.projection.AttendanceFactProjector;
import com.custoking.ims.platformservice.persistence.AttendanceFactReadRepository;
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
 * Proves the reporting.fact_attendance_daily projection (Reporting Decoupling SP3): school-core
 * attendance-daily.upserted.v1 events land in the reporting fact read-model via
 * ReportingEventInboxProcessor, idempotently (dedup by eventId at the inbox layer, ON CONFLICT
 * (id) DO UPDATE at the fact-table layer), and are NOT feed-worthy (mirrors
 * DimensionProjectionIntegrationTest for SP1).
 */
class AttendanceFactProjectionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingEventInboxRepository inbox;
    private ReportingCommandRepository commands;
    private AttendanceFactReadRepository attendanceFacts;
    private ReportingEventInboxProcessor processor;

    @BeforeAll
    static void setUpContainer() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping attendance fact projection integration test");
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
        attendanceFacts = new AttendanceFactReadRepository(jdbcClient);
        ObjectMapper objectMapper = new ObjectMapper();
        processor = new ReportingEventInboxProcessor(inbox, commands, java.util.List.of(
                new AttendanceFactProjector(attendanceFacts, objectMapper)), 50);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE reporting.reporting_event_inbox, reporting.command_center_feed, "
                    + "reporting.fact_attendance_daily");
        }
    }

    private void feedAttendanceDailyEvent(String eventId, String attendanceDailyId, long schoolId) {
        String payload = "{\"id\":\"" + attendanceDailyId + "\",\"schoolId\":" + schoolId
                + ",\"date\":\"2024-03-04\",\"classId\":\"c1\",\"sectionId\":\"s1\",\"academicYearId\":\"AY\","
                + "\"presentCount\":1,\"absentCount\":1,\"lateCount\":1,\"leaveCount\":1,\"totalEnrolled\":4}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"attendance-daily.upserted.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId,
                null,
                "attendance-daily.upserted.v1",
                "v1",
                "AttendanceDaily",
                attendanceDailyId,
                schoolId,
                null,
                Optional.of(OffsetDateTime.now()),
                OffsetDateTime.now(),
                envelope,
                payload
        ));
    }

    private long countFactRows(String attendanceDailyId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.fact_attendance_daily WHERE id = ?")) {
            ps.setString(1, attendanceDailyId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void projectsAttendanceDailyUpsertedEventIntoFactAttendanceDaily() throws Exception {
        feedAttendanceDailyEvent(UUID.randomUUID().toString(), "ad-1", 7L);

        int processed = processor.processBatch();

        assertEquals(1, processed);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT school_id, class_id, section_id, academic_year_id,
                            present_count, absent_count, late_count, leave_count, total_enrolled
                     FROM reporting.fact_attendance_daily WHERE id = ?
                     """)) {
            ps.setString(1, "ad-1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a fact_attendance_daily row for id ad-1");
                assertEquals(7L, rs.getLong("school_id"));
                assertEquals("c1", rs.getString("class_id"));
                assertEquals("s1", rs.getString("section_id"));
                assertEquals("AY", rs.getString("academic_year_id"));
                assertEquals(1, rs.getInt("present_count"));
                assertEquals(1, rs.getInt("absent_count"));
                assertEquals(1, rs.getInt("late_count"));
                assertEquals(1, rs.getInt("leave_count"));
                assertEquals(4, rs.getInt("total_enrolled"));
            }
        }
    }

    @Test
    void sameEventIdReplayed_isDedupedByInbox_doesNotDuplicateFactRow() throws Exception {
        String eventId = UUID.randomUUID().toString();
        feedAttendanceDailyEvent(eventId, "ad-2", 7L);
        int firstBatch = processor.processBatch();
        assertEquals(1, firstBatch);

        feedAttendanceDailyEvent(eventId, "ad-2", 7L);
        int secondBatch = processor.processBatch();

        assertEquals(0, secondBatch, "replayed event with the same eventId must not be reprocessed");
        assertEquals(1, countFactRows("ad-2"));
    }

    @Test
    void attendanceDailyUpsertedEvent_projectsToFact_butDoesNotCreateCommandCenterFeedRow() throws Exception {
        String eventId = UUID.randomUUID().toString();
        feedAttendanceDailyEvent(eventId, "ad-3", 7L);

        int processed = processor.processBatch();

        assertEquals(1, processed);
        assertEquals(1, countFactRows("ad-3"));
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.command_center_feed WHERE source_type = 'EVENT_INBOX' AND source_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1),
                        "attendance-daily events must not create command_center_feed rows");
            }
        }
    }
}

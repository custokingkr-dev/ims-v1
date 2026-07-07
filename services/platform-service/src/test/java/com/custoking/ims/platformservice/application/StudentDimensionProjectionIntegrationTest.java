package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.projection.StudentDimensionProjector;
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
 * Proves the reporting.dim_student projection (Reporting Decoupling SP5): school-core
 * student.upserted.v1 events land in reporting.dim_student via ReportingEventInboxProcessor,
 * mirroring DimensionProjectionIntegrationTest's (SP1) shape for dim_school/dim_section.
 */
class StudentDimensionProjectionIntegrationTest {

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
                "Docker not available — skipping student dimension projection integration test");
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
                new StudentDimensionProjector(dims, objectMapper)), 50);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE reporting.reporting_event_inbox, reporting.command_center_feed, "
                    + "reporting.dim_student");
        }
    }

    private void feedStudentEvent(String eventId, long studentId, long schoolId, String fullName) {
        String payload = "{\"id\":" + studentId + ",\"schoolId\":" + schoolId
                + ",\"admissionNo\":\"ADM-" + studentId + "\",\"fullName\":\"" + fullName + "\","
                + "\"rollNo\":\"7\",\"classId\":\"c1\",\"sectionId\":\"s1\","
                + "\"parentContact\":\"9876500000\",\"phone\":\"9876500000\",\"active\":true,"
                + "\"attendancePercent\":92.5,\"fatherName\":\"John Doe\"}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"student.upserted.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId,
                null,
                "student.upserted.v1",
                "v1",
                "Student",
                String.valueOf(studentId),
                schoolId,
                null,
                Optional.of(OffsetDateTime.now()),
                OffsetDateTime.now(),
                envelope,
                payload
        ));
    }

    private long countStudentRows(long studentId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.dim_student WHERE id = ?")) {
            ps.setLong(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void projectsStudentUpsertedEventIntoDimStudent() throws Exception {
        feedStudentEvent(UUID.randomUUID().toString(), 42L, 7L, "Jane Doe");

        int processed = processor.processBatch();

        assertEquals(1, processed);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT full_name, admission_no, school_id, active, attendance_percent, father_name "
                             + "FROM reporting.dim_student WHERE id = ?")) {
            ps.setLong(1, 42L);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a dim_student row for id 42");
                assertEquals("Jane Doe", rs.getString("full_name"));
                assertEquals("ADM-42", rs.getString("admission_no"));
                assertEquals(7L, rs.getLong("school_id"));
                assertTrue(rs.getBoolean("active"));
                assertEquals(0, java.math.BigDecimal.valueOf(92.5).compareTo(rs.getBigDecimal("attendance_percent")));
                assertEquals("John Doe", rs.getString("father_name"));
            }
        }
    }

    @Test
    void sameEventIdReplayed_isDedupedByInbox_doesNotDuplicateDimStudent() throws Exception {
        String eventId = UUID.randomUUID().toString();
        feedStudentEvent(eventId, 42L, 7L, "Jane Doe");
        int firstBatch = processor.processBatch();
        assertEquals(1, firstBatch);

        feedStudentEvent(eventId, 42L, 7L, "Jane Doe");
        int secondBatch = processor.processBatch();

        assertEquals(0, secondBatch, "replayed event with the same eventId must not be reprocessed");
        assertEquals(1, countStudentRows(42L));
    }

    @Test
    void studentUpsertedEvent_projectsToDimStudent_butDoesNotCreateCommandCenterFeedRow() throws Exception {
        String eventId = UUID.randomUUID().toString();
        feedStudentEvent(eventId, 42L, 7L, "Jane Doe");

        int processed = processor.processBatch();

        assertEquals(1, processed);
        assertEquals(1, countStudentRows(42L), "dimension projection must still happen for student events");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM reporting.command_center_feed WHERE source_type = 'EVENT_INBOX' AND source_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1),
                        "student dimension events must not create command_center_feed rows");
            }
        }
    }
}

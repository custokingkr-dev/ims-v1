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
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("notification").defaultSchema("notification")
                .locations("classpath:db/migration/notification")
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
            st.execute("TRUNCATE notification.notification_logs, reporting.event_student_contributions, "
                    + "reporting.academic_events, reporting.fact_payment, reporting.fact_fee_assignment, "
                    + "reporting.reporting_event_inbox, reporting.command_center_feed, "
                    + "reporting.student_projection_tombstones, reporting.dim_student CASCADE");
        }
    }

    private void feedStudentEvent(String eventId, long studentId, long schoolId, String fullName) {
        feedStudentEvent(eventId, studentId, schoolId, fullName, OffsetDateTime.now());
    }

    private void feedStudentEvent(String eventId, long studentId, long schoolId, String fullName,
                                  OffsetDateTime occurredAt) {
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
                Optional.of(occurredAt),
                OffsetDateTime.now(),
                envelope,
                payload
        ));
    }

    private void feedStudentDeletedEvent(String eventId, long studentId, long schoolId) {
        feedStudentDeletedEvent(eventId, studentId, schoolId, OffsetDateTime.now());
    }

    private void feedStudentDeletedEvent(String eventId, long studentId, long schoolId,
                                         OffsetDateTime occurredAt) {
        String payload = "{\"id\":" + studentId + ",\"schoolId\":" + schoolId + "}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"student.deleted.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId,
                null,
                "student.deleted.v1",
                "v1",
                "Student",
                String.valueOf(studentId),
                schoolId,
                null,
                Optional.of(occurredAt),
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

    @Test
    void studentDeletedEventRemovesAllStudentProjections() throws Exception {
        feedStudentEvent(UUID.randomUUID().toString(), 42L, 7L, "Jane Doe");
        assertEquals(1, processor.processBatch());
        jdbcClient.sql("""
                        INSERT INTO reporting.fact_fee_assignment
                            (id, student_id, school_id, academic_year_id, net_payable, paid_amount)
                        VALUES ('assignment-42', 42, 7, 'year-1', 10000, 5000)
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO reporting.fact_payment (id, assignment_id, school_id, student_id, amount)
                        VALUES ('payment-42', 'assignment-42', 7, 42, 5000)
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO reporting.academic_events (id, school_id, title, event_type)
                        VALUES ('event-42', 7, 'Trip', 'TRIP')
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO reporting.event_student_contributions
                            (id, event_id, student_id, school_id)
                        VALUES ('contribution-42', 'event-42', 42, 7)
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO notification.notification_logs
                            (id, school_id, student_id, channel, notification_type)
                        VALUES ('notification-42', 7, 42, 'SMS', 'ATTENDANCE')
                        """).update();

        feedStudentDeletedEvent(UUID.randomUUID().toString(), 42L, 7L);

        assertEquals(1, processor.processBatch());
        assertEquals(0L, countStudentRows(42L));
        assertEquals(0L, jdbcClient.sql("SELECT count(*) FROM reporting.fact_fee_assignment WHERE student_id = 42").query(Long.class).single());
        assertEquals(0L, jdbcClient.sql("SELECT count(*) FROM reporting.fact_payment WHERE student_id = 42").query(Long.class).single());
        assertEquals(0L, jdbcClient.sql("SELECT count(*) FROM reporting.event_student_contributions WHERE student_id = 42").query(Long.class).single());
        assertEquals(0L, jdbcClient.sql("SELECT count(*) FROM notification.notification_logs WHERE student_id = 42").query(Long.class).single());
    }

    @Test
    void staleUpsertDeliveredAfterDeleteCannotRecreateStudentProjection() throws Exception {
        OffsetDateTime deletionTime = OffsetDateTime.now();
        feedStudentDeletedEvent(UUID.randomUUID().toString(), 42L, 7L, deletionTime);
        assertEquals(1, processor.processBatch());

        feedStudentEvent(UUID.randomUUID().toString(), 42L, 7L, "Late stale create",
                deletionTime.minusSeconds(1));
        assertEquals(1, processor.processBatch());

        assertEquals(0L, countStudentRows(42L),
                "a terminal deletion tombstone must reject an out-of-order upsert");
        assertEquals(1L, jdbcClient.sql("""
                        SELECT count(*)
                        FROM reporting.student_projection_tombstones
                        WHERE student_id = 42
                        """).query(Long.class).single());
    }
}

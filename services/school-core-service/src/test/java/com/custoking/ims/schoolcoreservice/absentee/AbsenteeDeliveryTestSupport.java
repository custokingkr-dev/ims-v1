package com.custoking.ims.schoolcoreservice.absentee;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared fixtures for the absentee delivery integration tests (attendance schema only). */
final class AbsenteeDeliveryTestSupport {

    static final String OWNER = "owner";

    private AbsenteeDeliveryTestSupport() {
    }

    static PostgreSQLContainer<?> startPostgres() {
        PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16").withUsername(OWNER).withPassword(OWNER);
        pg.start();
        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), OWNER, OWNER)
                .schemas("attendance")
                .defaultSchema("attendance")
                .locations("classpath:db/migration/attendance")
                .load()
                .migrate();
        return pg;
    }

    static Connection ownerConnection(PostgreSQLContainer<?> pg) throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), OWNER, OWNER);
    }

    /** Inserts a QUEUED row that satisfies the V9 v2-evidence check constraint. */
    static void seedQueued(Connection owner, String id, long schoolId, long studentId, String createdAt) throws SQLException {
        try (Statement st = owner.createStatement()) {
            st.execute("""
                    INSERT INTO attendance.absentee_notifications
                        (id, school_id, student_id, class_id, section_id, academic_year_id,
                         attendance_date, parent_contact, channel, message, status, created_at,
                         guardian_id, consent_event_id, consent_notice_version, policy_version,
                         policy_decision, destination_sha256, policy_evaluated_at, policy_expires_at)
                    VALUES ('%s', %d, %d, 'c1', 's1', 'y1', DATE '2026-09-10', '919999999999', 'WHATSAPP',
                            'Dear Parent, your child was marked absent.', 'QUEUED', TIMESTAMPTZ '%s',
                            'guardian-%d', 'consent-1', 'notice-v1', 'guardian-communications.v2',
                            'ALLOW', '%s', now(), now() + interval '2 minutes')
                    """.formatted(id, schoolId, studentId, createdAt, studentId,
                    DispatchDecision.destinationSha256("WHATSAPP", "919999999999")));
        }
    }

    static Map<String, Object> rowState(Connection owner, String id) throws SQLException {
        try (Statement st = owner.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT status, attempts, next_attempt_at, last_error, provider, provider_message_id,
                            delivered_at, dead_lettered_at, last_attempt_at
                     FROM attendance.absentee_notifications WHERE id = '%s'
                     """.formatted(id))) {
            if (!rs.next()) throw new IllegalStateException("row missing: " + id);
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("status", rs.getString("status"));
            state.put("attempts", rs.getInt("attempts"));
            state.put("nextAttemptAt", rs.getObject("next_attempt_at", OffsetDateTime.class));
            state.put("lastError", rs.getString("last_error"));
            state.put("provider", rs.getString("provider"));
            state.put("providerMessageId", rs.getString("provider_message_id"));
            state.put("deliveredAt", rs.getObject("delivered_at", OffsetDateTime.class));
            state.put("deadLetteredAt", rs.getObject("dead_lettered_at", OffsetDateTime.class));
            state.put("lastAttemptAt", rs.getObject("last_attempt_at", OffsetDateTime.class));
            return state;
        }
    }

    /** A policy that allows every student with the seeded guardian/destination binding. */
    static AbsenteeDispatchPolicy allowAll() {
        return (schoolId, studentId, channel, sourceEventId) -> {
            OffsetDateTime now = OffsetDateTime.now();
            return DispatchDecision.allowed("guardian-" + studentId, "919999999999", "consent-1", "notice-v1",
                    channel, schoolId, studentId, now, now.plusSeconds(90), sourceEventId);
        };
    }
}

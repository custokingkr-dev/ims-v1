package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ReportingEventInboxRepository {

    private final JdbcClient jdbc;

    public ReportingEventInboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String eventId) {
        return jdbc.sql("""
                        SELECT 1
                        FROM reporting.reporting_event_inbox
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public List<ReportingEventInboxProjectionRow> findReceivedForProjection(int batchSize) {
        return jdbc.sql("""
                        SELECT event_id, event_type, aggregate_type, aggregate_id,
                               school_id, actor_user_id, occurred_at, received_at, payload,
                               trace_parent, trace_state
                        FROM reporting.reporting_event_inbox
                        WHERE status = 'RECEIVED'
                        ORDER BY received_at ASC
                        LIMIT :batchSize
                        """)
                .param("batchSize", Math.max(1, Math.min(batchSize, 500)))
                .query((rs, rowNum) -> new ReportingEventInboxProjectionRow(
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getObject("school_id", Long.class),
                        rs.getObject("actor_user_id", Long.class),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getObject("received_at", OffsetDateTime.class),
                        rs.getString("payload"),
                        rs.getString("trace_parent"),
                        rs.getString("trace_state")))
                .list();
    }

    @Transactional
    public void record(ReportingEventInboxRecord event) {
        jdbc.sql("""
                        INSERT INTO reporting.reporting_event_inbox (
                            event_id, event_key, event_type, event_version, aggregate_type, aggregate_id,
                            school_id, actor_user_id, occurred_at, received_at, status, envelope, payload,
                            trace_parent, trace_state
                        ) VALUES (
                            :eventId, :eventKey, :eventType, :eventVersion, :aggregateType, :aggregateId,
                            :schoolId, :actorUserId, :occurredAt, :receivedAt, 'RECEIVED', :envelope, :payload,
                            :traceParent, :traceState
                        )
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", event.eventId())
                .param("eventKey", event.eventKey())
                .param("eventType", event.eventType())
                .param("eventVersion", event.eventVersion())
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("schoolId", event.schoolId())
                .param("actorUserId", event.actorUserId())
                .param("occurredAt", event.occurredAt().orElse(null))
                .param("receivedAt", event.receivedAt())
                .param("envelope", event.envelope())
                .param("payload", event.payload())
                .param("traceParent", event.traceParent())
                .param("traceState", event.traceState())
                .update();
    }

    @Transactional
    public void markProcessed(String eventId) {
        jdbc.sql("""
                        UPDATE reporting.reporting_event_inbox
                        SET status = 'PROCESSED', processed_at = :processedAt, last_error = NULL
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .param("processedAt", OffsetDateTime.now())
                .update();
    }

    @Transactional
    public void markFailed(String eventId, String message) {
        jdbc.sql("""
                        UPDATE reporting.reporting_event_inbox
                        SET status = 'FAILED', last_error = :lastError
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .param("lastError", truncate(message, 1000))
                .update();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record ReportingEventInboxRecord(
            String eventId,
            String eventKey,
            String eventType,
            String eventVersion,
            String aggregateType,
            String aggregateId,
            Long schoolId,
            Long actorUserId,
            Optional<OffsetDateTime> occurredAt,
            OffsetDateTime receivedAt,
            String envelope,
            String payload,
            String traceParent,
            String traceState
    ) {
        public ReportingEventInboxRecord(
                String eventId,
                String eventKey,
                String eventType,
                String eventVersion,
                String aggregateType,
                String aggregateId,
                Long schoolId,
                Long actorUserId,
                Optional<OffsetDateTime> occurredAt,
                OffsetDateTime receivedAt,
                String envelope,
                String payload) {
            this(eventId, eventKey, eventType, eventVersion, aggregateType, aggregateId,
                    schoolId, actorUserId, occurredAt, receivedAt, envelope, payload, null, null);
        }
    }

    public record ReportingEventInboxProjectionRow(
            String eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            Long schoolId,
            Long actorUserId,
            OffsetDateTime occurredAt,
            OffsetDateTime receivedAt,
            String payload,
            String traceParent,
            String traceState
    ) {
    }
}

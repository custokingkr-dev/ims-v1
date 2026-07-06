package com.custoking.ims.operationsservice.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Polls {@code firefighting.outbox_events} for unpublished rows and relays
 * each as a canonical {@link EventEnvelope} via {@link DomainEventPublisher}.
 *
 * <p><b>At-least-once semantics:</b> each row is published BEFORE it is marked
 * published. If the process dies after {@link DomainEventPublisher#publish}
 * but before the {@code UPDATE ... SET published_at}, the row is simply
 * re-published on the next tick — consumers must dedupe by {@code eventId}
 * (the outbox row id), per {@code docs/EVENT-ENVELOPE-CONTRACT.md}. The
 * reverse order (marking published first) would risk silently dropping
 * events, so it is never done.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String SCHEMA_VERSION = "ims.event-envelope.v1";
    private static final String EVENT_VERSION = "v1";

    private final JdbcClient jdbc;
    private final DomainEventPublisher publisher;
    private final String outboxTable;
    private final int batchSize;

    public OutboxRelay(
            JdbcClient jdbc,
            DomainEventPublisher publisher,
            @Value("${operations.outbox.db.schema:firefighting}") String schema,
            @Value("${operations.outbox.relay.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.outboxTable = qualifiedTable(schema);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${operations.outbox.relay.fixed-delay-ms:10000}",
            initialDelayString = "${operations.outbox.relay.initial-delay-ms:0}")
    public void runScheduled() {
        int published = publishBatch();
        if (published > 0) {
            log.info("Outbox relay published {} event(s)", published);
        }
    }

    /**
     * Publishes up to {@code batchSize} unpublished outbox rows (oldest id
     * first, locked {@code FOR UPDATE SKIP LOCKED} so concurrent relay
     * instances don't double-publish the same row) and marks each published
     * only after {@link DomainEventPublisher#publish} returns without
     * throwing. Directly callable (e.g. from tests) — not hidden behind
     * {@link #runScheduled()}.
     */
    @Transactional
    public int publishBatch() {
        List<OutboxRow> rows = jdbc.sql("""
                        SELECT id::text AS id, event_key, event_type, aggregate_type, aggregate_id,
                               school_id, occurred_at, payload::text AS payload
                        FROM %s
                        WHERE published_at IS NULL
                        ORDER BY id
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                        """.formatted(outboxTable))
                .param("batchSize", batchSize)
                .query(OutboxRow.class)
                .list();

        for (OutboxRow row : rows) {
            publisher.publish(toEnvelope(row));
            jdbc.sql("UPDATE %s SET published_at = now(), attempts = attempts + 1 WHERE id = :id::bigint"
                            .formatted(outboxTable))
                    .param("id", row.id())
                    .update();
        }
        return rows.size();
    }

    private EventEnvelope toEnvelope(OutboxRow row) {
        return new EventEnvelope(
                SCHEMA_VERSION,
                "operations:" + row.id(),
                row.eventKey(),
                row.eventType(),
                EVENT_VERSION,
                row.aggregateType(),
                row.aggregateId(),
                row.occurredAt(),
                row.schoolId(),
                row.payload());
    }

    private String qualifiedTable(String schema) {
        String normalizedSchema = identifier(schema == null || schema.isBlank() ? "public" : schema);
        return normalizedSchema + "." + identifier("outbox_events");
    }

    private String identifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid database identifier: " + identifier);
        }
        return identifier;
    }

    public record OutboxRow(
            String id,
            String eventKey,
            String eventType,
            String aggregateType,
            String aggregateId,
            Long schoolId,
            OffsetDateTime occurredAt,
            String payload) {
    }
}

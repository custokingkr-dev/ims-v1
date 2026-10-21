package com.custoking.ims.operationsservice.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class OutboxAsyncHealthReporter {
    private static final Logger log = LoggerFactory.getLogger(OutboxAsyncHealthReporter.class);
    static final String BACKLOG_COUNT_FAILED_EVENT = "outbox_backlog_count_failed";
    private final JdbcClient jdbc;
    private final String table;
    private List<QueueBacklog> backlogs = List.of();

    public OutboxAsyncHealthReporter(JdbcClient jdbc,
                                     @Value("${operations.outbox.db.schema:firefighting}") String schema) {
        this.jdbc = jdbc;
        if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid outbox schema");
        }
        this.table = schema + ".outbox_events";
    }

    /**
     * This service's other retry queues (the notebook tracker status outbox): their backlog is added into the outbox
     * figures below, counts summed and the oldest age the larger, so the outbox log metrics and their alerts cover them.
     */
    @Autowired(required = false)
    public void configureBacklogs(List<QueueBacklog> backlogs) {
        this.backlogs = backlogs == null ? List.of() : List.copyOf(backlogs);
    }

    @Scheduled(initialDelayString = "${operations.outbox.health.initial-delay-ms:30000}",
            fixedDelayString = "${operations.outbox.health.fixed-delay-ms:60000}")
    public void report() {
        try {
            Map<String, Object> state = snapshot();
            log.info("outbox.async.health {}", kv("health", Map.of("outbox", state)));
        } catch (RuntimeException ex) {
            log.warn("outbox.async.health.failed error={}", ex.getMessage());
        }
    }

    Map<String, Object> snapshot() {
        QueueBacklog.Backlog total = jdbc.sql("""
                        SELECT (SELECT count(*) FROM %1$s
                                WHERE published_at IS NULL AND dead_lettered_at IS NULL) AS pending,
                               (SELECT count(*) FROM %1$s
                                WHERE dead_lettered_at IS NOT NULL) AS dead_letter,
                               COALESCE((SELECT EXTRACT(EPOCH FROM (now() - occurred_at))::bigint
                                         FROM %1$s
                                         WHERE published_at IS NULL AND dead_lettered_at IS NULL
                                         ORDER BY occurred_at LIMIT 1), 0) AS oldest_age
                        """.formatted(table))
                .query((rs, row) -> new QueueBacklog.Backlog(rs.getLong("pending"), rs.getLong("dead_letter"), rs.getLong("oldest_age")))
                .single();
        for (QueueBacklog backlog : backlogs) {
            // One queue that cannot be counted must not silence the outbox figures, which the alerts read every minute. Its
            // own figures drop out of the totals, so the error-level alerting is told instead.
            try {
                total = total.plus(backlog.backlog());
            } catch (RuntimeException ex) {
                log.error("outbox.async.health.backlog_failed {} {} {}", kv("event", BACKLOG_COUNT_FAILED_EVENT),
                        kv("queue", backlog.queueName()), kv("error", String.valueOf(ex.getMessage())));
            }
        }
        return Map.of(
                "pendingCount", total.pendingCount(),
                "deadLetterCount", total.deadLetterCount(),
                "oldestPendingAgeSeconds", total.oldestPendingAgeSeconds());
    }
}

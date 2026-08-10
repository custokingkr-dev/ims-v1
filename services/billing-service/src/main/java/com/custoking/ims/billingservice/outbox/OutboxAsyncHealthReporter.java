package com.custoking.ims.billingservice.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class OutboxAsyncHealthReporter {
    private static final Logger log = LoggerFactory.getLogger(OutboxAsyncHealthReporter.class);
    private final JdbcClient jdbc;
    private final String table;

    public OutboxAsyncHealthReporter(JdbcClient jdbc,
                                     @Value("${billing.db.schema:billing}") String schema) {
        this.jdbc = jdbc;
        if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid outbox schema");
        }
        this.table = schema + ".outbox_events";
    }

    @Scheduled(initialDelayString = "${billing.outbox.health.initial-delay-ms:30000}",
            fixedDelayString = "${billing.outbox.health.fixed-delay-ms:60000}")
    public void report() {
        try {
            Map<String, Object> state = snapshot();
            log.info("outbox.async.health {}", kv("health", Map.of("outbox", state)));
        } catch (RuntimeException ex) {
            log.warn("outbox.async.health.failed error={}", ex.getMessage());
        }
    }

    Map<String, Object> snapshot() {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM %1$s
                                WHERE published_at IS NULL AND dead_lettered_at IS NULL) AS pending,
                               (SELECT count(*) FROM %1$s
                                WHERE dead_lettered_at IS NOT NULL) AS dead_letter,
                               COALESCE((SELECT EXTRACT(EPOCH FROM (now() - occurred_at))::bigint
                                         FROM %1$s
                                         WHERE published_at IS NULL AND dead_lettered_at IS NULL
                                         ORDER BY occurred_at LIMIT 1), 0) AS oldest_age
                        """.formatted(table))
                .query((rs, row) -> Map.<String, Object>of(
                        "pendingCount", rs.getLong("pending"),
                        "deadLetterCount", rs.getLong("dead_letter"),
                        "oldestPendingAgeSeconds", rs.getLong("oldest_age")))
                .single();
    }
}

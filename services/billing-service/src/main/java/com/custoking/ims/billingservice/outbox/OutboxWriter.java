package com.custoking.ims.billingservice.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;

/**
 * Appends a row to {@code billing.outbox_events} using the caller's
 * Spring-managed transaction (participates via the shared {@link JdbcClient} /
 * {@code DataSource} — it never opens its own connection or transaction).
 *
 * <p>Callers (e.g. {@code BillingInvoiceService}) must invoke {@link #append}
 * from within an existing {@code @Transactional} method so that the outbox
 * row commits (or rolls back) atomically with the domain write it accompanies.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final String outboxTable;

    public OutboxWriter(JdbcClient jdbc, @Value("${billing.db.schema:billing}") String schema) {
        this.jdbc = jdbc;
        this.outboxTable = qualifiedTable(schema);
    }

    public void append(String eventType, String eventKey, String aggregateType,
                        String aggregateId, Long schoolId, String payloadJson) {
        String traceParent = currentTraceParent();
        jdbc.sql("""
                        INSERT INTO %s
                            (event_key, event_type, aggregate_type, aggregate_id, school_id, payload, trace_parent, trace_state)
                        VALUES
                            (:eventKey, :eventType, :aggregateType, :aggregateId, :schoolId, :payload::jsonb, :traceParent, :traceState)
                        """.formatted(outboxTable))
                .param("eventKey", eventKey)
                .param("eventType", eventType)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("schoolId", schoolId)
                .param("payload", payloadJson)
                .param("traceParent", traceParent)
                .param("traceState", null)
                .update();
    }

    static String currentTraceParent() {
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        if (traceId == null || spanId == null) {
            return null;
        }
        if (!traceId.matches("[0-9a-fA-F]{32}") || !spanId.matches("[0-9a-fA-F]{16}")) {
            return null;
        }
        return "00-" + traceId.toLowerCase() + "-" + spanId.toLowerCase() + "-01";
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
}

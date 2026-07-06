package com.custoking.ims.schoolcoreservice.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Appends a row to {@code tenant_school.outbox_events} using the caller's
 * Spring-managed transaction (participates via the shared {@link JdbcClient} /
 * {@code DataSource} — it never opens its own connection or transaction).
 *
 * <p>Callers must invoke {@link #append} from within an existing
 * {@code @Transactional} method so that the outbox row commits (or rolls
 * back) atomically with the domain write it accompanies.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final String outboxTable;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper objectMapper,
                         @Value("${school-core.db.schema:tenant_school}") String schema) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.outboxTable = qualifiedTable(schema);
    }

    public void append(String eventType, String eventKey, String aggregateType,
                        String aggregateId, Long schoolId, Map<String, Object> payload) {
        String payloadJson = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        jdbc.sql("""
                        INSERT INTO %s
                            (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
                        VALUES
                            (:eventKey, :eventType, :aggregateType, :aggregateId, :schoolId, :payload::jsonb)
                        """.formatted(outboxTable))
                .param("eventKey", eventKey)
                .param("eventType", eventType)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("schoolId", schoolId)
                .param("payload", payloadJson)
                .update();
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

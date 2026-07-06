package com.custoking.ims.platformservice.application.projection;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Shared payload-parsing helpers for {@link ReportingEventProjector} implementations. Extracted
 * verbatim from the original ReportingEventInboxProcessor switch/if projection logic (SP1) so
 * that each projector can parse its JSON payload identically without duplicating logic.
 */
public final class PayloadJson {

    private PayloadJson() {
    }

    public static JsonNode readPayload(ObjectMapper objectMapper, String payload) {
        if (payload == null || payload.isBlank()) {
            return objectMapper.nullNode();
        }
        return objectMapper.readTree(payload);
    }

    public static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.longValue();
        String text = value.asText();
        return text == null || text.isBlank() ? null : Long.valueOf(text);
    }

    public static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.decimalValue();
        String text = value.asText();
        return text == null || text.isBlank() ? null : new BigDecimal(text);
    }

    public static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.intValue();
        String text = value.asText();
        return text == null || text.isBlank() ? null : Integer.valueOf(text);
    }

    public static boolean boolOrFalse(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return false;
        if (value.isBoolean()) return value.booleanValue();
        return Boolean.parseBoolean(value.asText());
    }

    public static OffsetDateTime offsetDateTimeOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : OffsetDateTime.parse(text);
    }

    public static LocalDate localDateOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : LocalDate.parse(text);
    }
}

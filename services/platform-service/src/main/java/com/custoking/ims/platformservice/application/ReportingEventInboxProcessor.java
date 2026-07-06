package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.BillingInvoiceReadRepository;
import com.custoking.ims.platformservice.persistence.DimensionProjectionRepository;
import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;

@Component
@ConditionalOnProperty(prefix = "reporting.event-projection", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReportingEventInboxProcessor {

    private static final String SOURCE_TYPE = "EVENT_INBOX";
    private static final String BILLING_INVOICE_UPSERTED = "billing.invoice-upserted.v1";
    private static final String SCHOOL_UPSERTED = "school.upserted.v1";
    private static final String SCHOOL_SECTION_UPSERTED = "school-section.upserted.v1";
    private static final String ACADEMIC_YEAR_UPSERTED = "academic-year.upserted.v1";

    private final ReportingEventInboxRepository inbox;
    private final ReportingCommandRepository commands;
    private final BillingInvoiceReadRepository billingInvoiceRead;
    private final DimensionProjectionRepository dims;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public ReportingEventInboxProcessor(
            ReportingEventInboxRepository inbox,
            ReportingCommandRepository commands,
            BillingInvoiceReadRepository billingInvoiceRead,
            DimensionProjectionRepository dims,
            ObjectMapper objectMapper,
            @Value("${reporting.event-projection.batch-size:50}") int batchSize) {
        this.inbox = inbox;
        this.commands = commands;
        this.billingInvoiceRead = billingInvoiceRead;
        this.dims = dims;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${reporting.event-projection.fixed-delay-ms:10000}")
    public void runScheduled() {
        processBatch();
    }

    public int processBatch() {
        int processed = 0;
        for (var event : inbox.findReceivedForProjection(batchSize)) {
            try {
                if (!commands.feedSourceExists(SOURCE_TYPE, event.eventId())) {
                    commands.recordProjectedFeed(toFeedCommand(event));
                }
                switch (event.eventType() == null ? "" : event.eventType()) {
                    case BILLING_INVOICE_UPSERTED -> projectBillingInvoice(event);
                    case SCHOOL_UPSERTED -> projectSchool(event);
                    case SCHOOL_SECTION_UPSERTED -> projectSection(event);
                    case ACADEMIC_YEAR_UPSERTED -> projectAcademicYear(event);
                    default -> { /* not a dimension-relevant event; feed already recorded above */ }
                }
                inbox.markProcessed(event.eventId());
                processed++;
            } catch (RuntimeException ex) {
                inbox.markFailed(event.eventId(), ex.getMessage());
            }
        }
        return processed;
    }

    private void projectBillingInvoice(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = readPayload(event.payload());
        String id = textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        String orderRef = textOrNull(payload, "orderRef");
        String school = textOrNull(payload, "school");
        Long schoolId = longOrNull(payload, "schoolId");
        String description = textOrNull(payload, "description");
        Integer qty = intOrNull(payload, "qty");
        Long rate = longOrNull(payload, "rate");
        Long amount = longOrNull(payload, "amount");
        Long gstAmount = longOrNull(payload, "gstAmount");
        String status = textOrNull(payload, "status");
        BigDecimal total = decimalOrNull(payload, "total");
        String issuedAt = textOrNull(payload, "issuedAt");
        String dueAt = textOrNull(payload, "dueAt");
        String notes = textOrNull(payload, "notes");
        OffsetDateTime createdAt = offsetDateTimeOrNull(payload, "createdAt");
        OffsetDateTime occurredAt = event.occurredAt() != null ? event.occurredAt() : event.receivedAt();
        billingInvoiceRead.upsert(id, orderRef, school, schoolId, description, qty, rate, amount, gstAmount,
                total, status, issuedAt, dueAt, notes, createdAt, occurredAt);
    }

    private void projectSchool(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = readPayload(event.payload());
        Long id = longOrNull(payload, "id");
        if (id == null) {
            return;
        }
        String name = textOrNull(payload, "name");
        String shortCode = textOrNull(payload, "shortCode");
        String city = textOrNull(payload, "city");
        String state = textOrNull(payload, "state");
        boolean active = boolOrFalse(payload, "active");
        dims.upsertSchool(id, name, shortCode, city, state, active);
    }

    private void projectSection(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = readPayload(event.payload());
        String id = textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        String name = textOrNull(payload, "name");
        Long schoolId = longOrNull(payload, "schoolId");
        String classId = textOrNull(payload, "classId");
        String className = textOrNull(payload, "className");
        boolean active = boolOrFalse(payload, "active");
        String teacherName = textOrNull(payload, "teacherName");
        dims.upsertSection(id, name, schoolId, classId, className, active, teacherName);
    }

    private void projectAcademicYear(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = readPayload(event.payload());
        String id = textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        String label = textOrNull(payload, "label");
        boolean active = boolOrFalse(payload, "active");
        dims.upsertAcademicYear(id, label, active);
    }

    private JsonNode readPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return objectMapper.nullNode();
        }
        return objectMapper.readTree(payload);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.longValue();
        String text = value.asText();
        return text == null || text.isBlank() ? null : Long.valueOf(text);
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.decimalValue();
        String text = value.asText();
        return text == null || text.isBlank() ? null : new BigDecimal(text);
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.intValue();
        String text = value.asText();
        return text == null || text.isBlank() ? null : Integer.valueOf(text);
    }

    private boolean boolOrFalse(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return false;
        if (value.isBoolean()) return value.booleanValue();
        return Boolean.parseBoolean(value.asText());
    }

    private OffsetDateTime offsetDateTimeOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : OffsetDateTime.parse(text);
    }

    private ReportingCommandRepository.ProjectedFeedCommand toFeedCommand(
            ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        String eventType = safe(event.eventType(), "event.received.v1");
        String aggregateType = safe(event.aggregateType(), "Entity");
        String aggregateId = safe(event.aggregateId(), "unknown");
        OffsetDateTime createdAt = event.occurredAt() != null ? event.occurredAt() : event.receivedAt();
        return new ReportingCommandRepository.ProjectedFeedCommand(
                event.schoolId(),
                moduleFor(eventType),
                eventType,
                titleFor(eventType),
                "Received " + eventType + " for " + aggregateType + " " + aggregateId,
                "info",
                aggregateType,
                aggregateId,
                event.actorUserId(),
                createdAt,
                SOURCE_TYPE,
                event.eventId()
        );
    }

    private String moduleFor(String eventType) {
        String domain = eventType == null ? "" : eventType.split("\\.", 2)[0].toLowerCase(Locale.ROOT);
        return switch (domain) {
            case "fees", "billing", "payments" -> "finance";
            case "attendance" -> "attendance";
            case "supply" -> "supply";
            case "firefighting" -> "firefighting";
            case "workflow" -> "workflow";
            case "students", "student" -> "students";
            case "schools", "school" -> "schools";
            case "identity", "auth" -> "identity";
            default -> "system";
        };
    }

    private String titleFor(String eventType) {
        String core = eventType == null ? "Event Received" : eventType;
        int versionStart = core.lastIndexOf(".v");
        if (versionStart > 0 && versionStart < core.length() - 2 && Character.isDigit(core.charAt(versionStart + 2))) {
            core = core.substring(0, versionStart);
        }
        int firstDot = core.indexOf('.');
        if (firstDot >= 0 && firstDot < core.length() - 1) {
            core = core.substring(firstDot + 1);
        }
        String[] tokens = core.replace('-', '.').replace('_', '.').split("\\.");
        StringBuilder title = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank()) continue;
            if (!title.isEmpty()) title.append(' ');
            title.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                title.append(token.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return title.isEmpty() ? "Event Received" : title.toString();
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

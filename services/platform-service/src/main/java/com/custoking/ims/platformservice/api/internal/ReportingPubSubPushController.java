package com.custoking.ims.platformservice.api.internal;

import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository.ReportingEventInboxRecord;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/pubsub")
public class ReportingPubSubPushController {

    private static final String ENVELOPE_VERSION = "ims.event-envelope.v1";

    private final ReportingEventInboxRepository inbox;
    private final ObjectMapper objectMapper;
    private final String pushToken;

    public ReportingPubSubPushController(
            ReportingEventInboxRepository inbox,
            ObjectMapper objectMapper,
            @Value("${reporting.pubsub.push-token:${reporting.read-token:}}") String pushToken) {
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.pushToken = pushToken == null ? "" : pushToken.trim();
    }

    @PostMapping("/reporting-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receiveReportingEvent(
            @RequestHeader(value = "X-Reporting-Pubsub-Token", required = false) String token,
            @RequestParam(value = "token", required = false) String tokenParam,
            @RequestBody JsonNode inboundEnvelope) {
        requireToken(token != null ? token : tokenParam, "reporting:ingest");

        JsonNode message = inboundEnvelope.path("message");
        JsonNode eventEnvelope = message.isMissingNode() ? inboundEnvelope : decodeData(message);
        if (!ENVELOPE_VERSION.equals(firstText(text(eventEnvelope, "schemaVersion"), text(eventEnvelope, "schema")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported reporting event envelope");
        }

        String eventId = firstText(text(eventEnvelope, "eventId"), attribute(message, "eventId"), message.path("messageId").asText(null));
        String eventType = firstText(text(eventEnvelope, "eventType"), attribute(message, "eventType"));
        if (!StringUtils.hasText(eventId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing event id");
        }
        if (!StringUtils.hasText(eventType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing event type");
        }
        if (inbox.exists(eventId)) {
            return;
        }

        JsonNode payload = eventEnvelope.path("payload");
        inbox.record(new ReportingEventInboxRecord(
                eventId,
                firstText(text(eventEnvelope, "eventKey"), attribute(message, "eventKey")),
                eventType,
                text(eventEnvelope, "eventVersion"),
                firstText(text(eventEnvelope, "aggregateType"), attribute(message, "aggregateType")),
                firstText(text(eventEnvelope, "aggregateId"), attribute(message, "aggregateId")),
                longValue(eventEnvelope, "schoolId"),
                firstLong(eventEnvelope, "actorUserId", "actorId"),
                offsetDateTime(eventEnvelope, "occurredAt"),
                OffsetDateTime.now(),
                toJson(eventEnvelope),
                payload.isMissingNode() || payload.isNull() ? "{}" : toJson(payload)
        ));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope");
        }
        if (!StringUtils.hasText(pushToken) || !pushToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid reporting pubsub token");
        }
    }

    private JsonNode decodeData(JsonNode message) {
        String encoded = message.path("data").asText("");
        if (!StringUtils.hasText(encoded)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Pub/Sub message data");
        }
        try {
            String json = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pub/Sub message data is not valid JSON", ex);
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event payload cannot be serialized", ex);
        }
    }

    private static Optional<OffsetDateTime> offsetDateTime(JsonNode node, String field) {
        String value = text(node, field);
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        return Optional.of(OffsetDateTime.parse(value));
    }

    private static Long firstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            Long value = longValue(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.longValue();
        }
        String text = value.asText();
        return StringUtils.hasText(text) ? Long.valueOf(text) : null;
    }

    private static String attribute(JsonNode message, String name) {
        JsonNode value = message.path("attributes").path(name);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}

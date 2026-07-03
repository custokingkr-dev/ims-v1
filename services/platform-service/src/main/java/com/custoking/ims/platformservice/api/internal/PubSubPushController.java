package com.custoking.ims.platformservice.api.internal;

import com.custoking.ims.platformservice.application.NotificationInboxProcessor;
import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import com.custoking.ims.platformservice.persistence.NotificationInboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@RestController
@RequestMapping("/api/v1/pubsub")
public class PubSubPushController {

    private final NotificationInboxRepository inboxRepository;
    private final NotificationInboxProcessor inboxProcessor;
    private final ObjectMapper objectMapper;
    private final String pushToken;

    public PubSubPushController(NotificationInboxRepository inboxRepository,
                                NotificationInboxProcessor inboxProcessor,
                                ObjectMapper objectMapper,
                                @Value("${notification.pubsub.push-token:}") String pushToken) {
        this.inboxRepository = inboxRepository;
        this.inboxProcessor = inboxProcessor;
        this.objectMapper = objectMapper;
        this.pushToken = pushToken;
    }

    @PostMapping("/notifications")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receiveNotificationRequest(
            @RequestHeader(value = "X-Pubsub-Token", required = false) String token,
            @RequestParam(value = "token", required = false) String tokenParam,
            @RequestBody JsonNode envelope) {
        requireValidToken(token != null ? token : tokenParam, "notification:ingest");

        JsonNode message = envelope.path("message");
        if (message.isMissingNode()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Pub/Sub message");
        }
        String eventId = attribute(message, "eventId");
        if (eventId == null || eventId.isBlank()) {
            eventId = message.path("messageId").asText(null);
        }
        if (eventId == null || eventId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing event id");
        }
        JsonNode decodedPayload = decodePayload(message);
        JsonNode deliveryPayload = canonicalPayload(decodedPayload);

        NotificationInboxEvent existing = inboxRepository.findById(eventId).orElse(null);
        if (existing != null) {
            if (NotificationInboxEvent.STATUS_PROCESSED.equals(existing.getStatus())) {
                return;
            }
            inboxProcessor.process(existing);
            return;
        }

        NotificationInboxEvent event = new NotificationInboxEvent();
        event.setEventId(eventId);
        event.setEventType(firstText(attribute(message, "eventType"), text(decodedPayload, "eventType")));
        event.setEventKey(firstText(attribute(message, "eventKey"), text(decodedPayload, "eventKey")));
        event.setAggregateType(firstText(attribute(message, "aggregateType"), text(decodedPayload, "aggregateType")));
        event.setAggregateId(firstText(attribute(message, "aggregateId"), text(decodedPayload, "aggregateId")));
        event.setPayload(toJson(deliveryPayload));
        inboxRepository.save(event);

        inboxProcessor.process(event);
    }

    private void requireValidToken(String token, String requiredScope) {
        if (requiredScope == null || requiredScope.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (pushToken == null || pushToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Pub/Sub push token configuration");
        }
        if (!pushToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Pub/Sub push token");
        }
    }

    private JsonNode decodePayload(JsonNode message) {
        String encoded = message.path("data").asText("");
        if (encoded.isBlank()) {
            return objectMapper.createObjectNode();
        }
        byte[] decoded = Base64.getDecoder().decode(encoded);
        String payload = new String(decoded, StandardCharsets.UTF_8);
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pub/Sub message data is not valid JSON");
        }
    }

    private JsonNode canonicalPayload(JsonNode decodedPayload) {
        JsonNode schemaVersion = decodedPayload.get("schemaVersion");
        JsonNode payload = decodedPayload.get("payload");
        if (schemaVersion != null
                && "ims.event-envelope.v1".equals(schemaVersion.asText())
                && payload != null
                && !payload.isNull()) {
            return payload;
        }
        return decodedPayload;
    }

    private String toJson(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pub/Sub payload cannot be serialized", ex);
        }
    }

    private static String attribute(JsonNode message, String name) {
        JsonNode value = message.path("attributes").path(name);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}


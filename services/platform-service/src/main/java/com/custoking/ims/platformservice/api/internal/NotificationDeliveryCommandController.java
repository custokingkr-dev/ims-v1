package com.custoking.ims.platformservice.api.internal;

import com.custoking.ims.platformservice.application.NotificationDeliveryCommandService;
import com.custoking.ims.platformservice.application.NotificationDeliveryCommandService.DeliverNowCommand;
import com.custoking.ims.platformservice.application.NotificationDeliveryCommandService.DeliveryAnswer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service-to-service delivery command used by school-core's absentee-notification drainer.
 *
 * <p>Auth: Cloud Run IAM verifies the caller's OIDC bearer at the ingress (the caller's runtime
 * service account needs {@code roles/run.invoker} on this service), and the shared
 * {@code X-Notification-Service-Token} ({@code notification.status.token}) gates the route inside
 * the app — the same double gate as {@code /api/v1/notifications/logs}. The gateway never routes
 * {@code /api/v1/internal/**}.
 *
 * <p>Every delivery outcome is a 200 with a {@code status} field (DELIVERED, SUPPRESSED, FAILED,
 * DEAD_LETTER) so the caller can tell a delivery result from a transport failure.
 */
@RestController
@RequestMapping("/api/v1/internal/notifications")
public class NotificationDeliveryCommandController {

    private final NotificationDeliveryCommandService service;
    private final ObjectMapper objectMapper;
    private final String statusToken;

    @Autowired
    public NotificationDeliveryCommandController(NotificationDeliveryCommandService service,
                                                 ObjectMapper objectMapper,
                                                 @Value("${notification.status.token:}") String statusToken) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.statusToken = statusToken == null ? "" : statusToken.trim();
    }

    @PostMapping("/deliveries")
    public Map<String, Object> deliver(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @RequestBody JsonNode envelope) {
        requireToken(token, "notification:deliver");
        String eventId = text(envelope, "eventId");
        if (!StringUtils.hasText(eventId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventId is required");
        }
        JsonNode payload = envelope.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload object is required");
        }
        DeliveryAnswer answer = service.deliverNow(new DeliverNowCommand(
                eventId,
                text(envelope, "eventType"),
                text(envelope, "eventKey"),
                text(envelope, "aggregateType"),
                text(envelope, "aggregateId"),
                payload));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", answer.eventId());
        body.put("status", answer.status());
        body.put("dryRun", answer.dryRun());
        body.put("provider", answer.provider());
        body.put("attempts", answer.attempts());
        body.put("error", answer.error());
        body.put("providerMessageId", answer.providerMessageId());
        return body;
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope");
        }
        if (!StringUtils.hasText(statusToken) || !statusToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid notification service token");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

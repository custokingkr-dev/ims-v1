package com.custoking.ims.schoolcoreservice.absentee;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live delivery path: {@code POST {platform.base-url}/api/v1/internal/notifications/deliveries}.
 *
 * <p>The body is a {@code notification.requested.v1} event carrying the
 * {@code attendance.absentee-notification-requested.v1} producer contract and the fresh v2 policy
 * evidence, exactly as platform-service's {@code NotificationPolicyGuard} verifies it. platform-service
 * runs its inbox processor (row lock, guard, provider, audit) synchronously and answers with the
 * terminal-or-retryable outcome; this class only translates that answer.
 *
 * <p>Why HTTP and not the outbox: the outbox publishes to the reporting topic only, delivery is a
 * command that needs a reply (provider id / failure reason back onto the row), and V9 requires
 * policy re-evaluation immediately before <em>every</em> provider attempt — a Pub/Sub retry would
 * carry evidence that has already aged past the two-minute window.
 */
public class PlatformAbsenteeDeliveryGateway implements AbsenteeDeliveryGateway {

    static final String PATH = "/api/v1/internal/notifications/deliveries";
    static final String SOURCE_EVENT_TYPE = "attendance.absentee-notification-requested.v1";
    static final String NOTIFICATION_TYPE = "ABSENTEE_ALERT";
    static final String TEMPLATE = "absentee-alert.v1";

    private final RestClient client;
    private final String baseUrl;
    private final String serviceToken;
    private final CloudRunIdentityTokenSupplier identityTokens;
    private final String whatsappTemplateName;
    private final ObjectMapper objectMapper;

    public PlatformAbsenteeDeliveryGateway(RestClient client,
                                           String baseUrl,
                                           String serviceToken,
                                           CloudRunIdentityTokenSupplier identityTokens,
                                           String whatsappTemplateName,
                                           ObjectMapper objectMapper) {
        this.client = client;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.identityTokens = identityTokens;
        this.whatsappTemplateName = whatsappTemplateName == null ? "" : whatsappTemplateName.trim();
        this.objectMapper = objectMapper;
    }

    public boolean configured() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(serviceToken);
    }

    @Override
    public DeliveryOutcome deliver(AbsenteeDeliveryRequest request) {
        if (!configured()) {
            return DeliveryOutcome.transientFailure("platform delivery is not configured (platform.base-url / notification.status.token)");
        }
        try {
            String body = client.post()
                    .uri(baseUrl + PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::applyHeaders)
                    .body(objectMapper.writeValueAsString(envelope(request)))
                    .retrieve()
                    .body(String.class);
            return interpret(body);
        } catch (HttpStatusCodeException ex) {
            return DeliveryOutcome.transientFailure("platform answered HTTP " + ex.getStatusCode().value());
        } catch (ResourceAccessException ex) {
            return DeliveryOutcome.transientFailure("platform unreachable: " + rootMessage(ex));
        } catch (RestClientException ex) {
            return DeliveryOutcome.transientFailure("platform call failed: " + rootMessage(ex));
        }
    }

    private void applyHeaders(HttpHeaders headers) {
        headers.set("X-Notification-Service-Token", serviceToken);
        String identityToken = identityTokens.tokenFor(baseUrl);
        if (StringUtils.hasText(identityToken)) {
            headers.setBearerAuth(identityToken);
        }
    }

    Map<String, Object> envelope(AbsenteeDeliveryRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("absenteeRequestId", request.eventId());
        payload.put("absenteeNotificationId", request.notificationId());
        payload.put("sourceEventType", SOURCE_EVENT_TYPE);
        payload.put("sourceEventId", request.eventId());
        payload.put("notificationType", NOTIFICATION_TYPE);
        payload.put("template", TEMPLATE);
        if (!whatsappTemplateName.isBlank()) {
            // The MSG91 WhatsApp template name is a product decision, supplied by configuration.
            payload.put("templateName", whatsappTemplateName);
        }
        payload.put("channel", request.channel());
        payload.put("destination", request.destination());
        payload.put("schoolId", request.schoolId());
        payload.put("studentId", request.studentId());
        payload.put("recipientType", "GUARDIAN");
        payload.put("recipientId", request.guardianId());
        payload.put("subject", "Absence notification");
        payload.put("policyEvidence", request.policyEvidence());
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("attendanceDate", String.valueOf(request.attendanceDate()));
        variables.put("message", request.message());
        payload.put("variables", variables);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", request.eventId());
        envelope.put("eventType", "notification.requested.v1");
        envelope.put("eventKey", "AbsenteeNotification:" + request.notificationId());
        envelope.put("aggregateType", "AbsenteeNotification");
        envelope.put("aggregateId", request.notificationId());
        envelope.put("payload", payload);
        return envelope;
    }

    private DeliveryOutcome interpret(String body) {
        JsonNode answer;
        try {
            answer = body == null ? null : objectMapper.readTree(body);
        } catch (RuntimeException ex) {
            return DeliveryOutcome.transientFailure("platform answer is not JSON");
        }
        if (answer == null || answer.isNull()) {
            return DeliveryOutcome.transientFailure("platform answered with an empty body");
        }
        String status = text(answer, "status");
        String provider = text(answer, "provider");
        String error = text(answer, "error");
        boolean dryRun = answer.path("dryRun").asBoolean(true);
        return switch (status == null ? "" : status) {
            case "DELIVERED" -> dryRun
                    ? DeliveryOutcome.dryRun(provider == null ? "platform-dry-run" : provider)
                    : DeliveryOutcome.delivered(provider == null ? "platform" : provider, text(answer, "providerMessageId"));
            case "SUPPRESSED" -> DeliveryOutcome.suppressed(error == null ? "SUPPRESSED" : error);
            case "FAILED" -> DeliveryOutcome.transientFailure(error == null ? "platform delivery failed" : error);
            case "DEAD_LETTER" -> DeliveryOutcome.permanentFailure(error == null ? "platform dead-lettered the event" : error);
            default -> DeliveryOutcome.transientFailure("unknown platform status: " + status);
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static String trimTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}

package com.custoking.ims.notificationservice.api;

import com.custoking.ims.notificationservice.persistence.NotificationDeliveryAttempt;
import com.custoking.ims.notificationservice.persistence.NotificationDeliveryAttemptRepository;
import com.custoking.ims.notificationservice.persistence.NotificationInboxEvent;
import com.custoking.ims.notificationservice.persistence.NotificationInboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationStatusController {

    private final NotificationInboxRepository inboxRepository;
    private final NotificationDeliveryAttemptRepository attemptRepository;
    private final String statusToken;

    public NotificationStatusController(NotificationInboxRepository inboxRepository,
                                        NotificationDeliveryAttemptRepository attemptRepository,
                                        @Value("${notification.status.token:}") String statusToken) {
        this.inboxRepository = inboxRepository;
        this.attemptRepository = attemptRepository;
        this.statusToken = statusToken;
    }

    @GetMapping("/{eventId}")
    public NotificationStatusResponse getStatus(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token) {
        requireValidToken(token, "notification:status:read");
        NotificationInboxEvent event = inboxRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification event not found"));
        List<DeliveryAttemptResponse> attempts = attemptRepository.findByEventIdOrderByAttemptedAtDesc(eventId)
                .stream()
                .map(DeliveryAttemptResponse::from)
                .toList();
        return new NotificationStatusResponse(
                event.getEventId(),
                event.getEventType(),
                event.getEventKey(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getStatus(),
                event.getReceivedAt(),
                event.getProcessedAt(),
                event.getLastError(),
                attempts);
    }

    private void requireValidToken(String token, String requiredScope) {
        if (requiredScope == null || requiredScope.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (statusToken == null || statusToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing notification status token configuration");
        }
        if (!statusToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid notification status token");
        }
    }

    public record NotificationStatusResponse(
            String eventId,
            String eventType,
            String eventKey,
            String aggregateType,
            String aggregateId,
            String status,
            OffsetDateTime receivedAt,
            OffsetDateTime processedAt,
            String lastError,
            List<DeliveryAttemptResponse> attempts
    ) {
    }

    public record DeliveryAttemptResponse(
            Long id,
            String provider,
            String channel,
            String status,
            OffsetDateTime attemptedAt,
            String error
    ) {
        static DeliveryAttemptResponse from(NotificationDeliveryAttempt attempt) {
            return new DeliveryAttemptResponse(
                    attempt.getId(),
                    attempt.getProvider(),
                    attempt.getChannel(),
                    attempt.getStatus(),
                    attempt.getAttemptedAt(),
                    attempt.getError());
        }
    }
}


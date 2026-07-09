package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.observability.TraceContextBridge;
import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import com.custoking.ims.platformservice.persistence.NotificationInboxRepository;
import com.custoking.ims.platformservice.persistence.NotificationDeliveryAttempt;
import com.custoking.ims.platformservice.persistence.NotificationDeliveryAttemptRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class NotificationInboxProcessor {

    private final NotificationInboxRepository inboxRepository;
    private final NotificationDeliveryAttemptRepository attemptRepository;
    private final NotificationDeliveryService deliveryService;
    private final ObjectMapper objectMapper;
    private final TraceContextBridge traceContextBridge;
    private final String provider;

    public NotificationInboxProcessor(NotificationInboxRepository inboxRepository,
                                      NotificationDeliveryAttemptRepository attemptRepository,
                                      NotificationDeliveryService deliveryService,
                                      ObjectMapper objectMapper,
                                      String provider) {
        this(inboxRepository, attemptRepository, deliveryService, objectMapper,
                TraceContextBridge.noop(), provider);
    }

    @Autowired
    public NotificationInboxProcessor(NotificationInboxRepository inboxRepository,
                                      NotificationDeliveryAttemptRepository attemptRepository,
                                      NotificationDeliveryService deliveryService,
                                      ObjectMapper objectMapper,
                                      TraceContextBridge traceContextBridge,
                                      @Value("${notification.delivery.provider:logging}") String provider) {
        this.inboxRepository = inboxRepository;
        this.attemptRepository = attemptRepository;
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
        this.traceContextBridge = traceContextBridge;
        this.provider = provider == null || provider.isBlank() ? "logging" : provider;
    }

    @Transactional
    public void process(NotificationInboxEvent event) {
        traceContextBridge.runInSpan(
                "notification.process " + safe(event.getEventType(), "event"),
                event.getTraceParent(),
                event.getTraceState(),
                () -> processOne(event));
    }

    private void processOne(NotificationInboxEvent event) {
        try {
            deliveryService.deliver(event);
            event.setStatus(NotificationInboxEvent.STATUS_PROCESSED);
            event.setProcessedAt(OffsetDateTime.now());
            event.setLastError(null);
            inboxRepository.save(event);
            recordAttempt(event, NotificationDeliveryAttempt.STATUS_DELIVERED, null);
        } catch (RuntimeException ex) {
            event.setStatus(NotificationInboxEvent.STATUS_FAILED);
            event.setLastError(ex.getMessage());
            inboxRepository.save(event);
            recordAttempt(event, NotificationDeliveryAttempt.STATUS_FAILED, ex.getMessage());
            throw ex;
        }
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void recordAttempt(NotificationInboxEvent event, String status, String error) {
        NotificationDeliveryAttempt attempt = new NotificationDeliveryAttempt();
        attempt.setEventId(event.getEventId());
        attempt.setEventType(event.getEventType());
        attempt.setChannel(channel(event));
        attempt.setProvider(provider);
        attempt.setStatus(status);
        attempt.setAttemptedAt(OffsetDateTime.now());
        attempt.setError(error);
        attemptRepository.save(attempt);
    }

    private String channel(NotificationInboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            JsonNode channel = payload.get("channel");
            return channel == null || channel.isNull() ? null : channel.asText();
        } catch (Exception ignored) {
            return null;
        }
    }
}

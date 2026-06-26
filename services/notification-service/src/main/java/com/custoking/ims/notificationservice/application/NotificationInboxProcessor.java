package com.custoking.ims.notificationservice.application;

import com.custoking.ims.notificationservice.persistence.NotificationInboxEvent;
import com.custoking.ims.notificationservice.persistence.NotificationInboxRepository;
import com.custoking.ims.notificationservice.persistence.NotificationDeliveryAttempt;
import com.custoking.ims.notificationservice.persistence.NotificationDeliveryAttemptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final String provider;

    public NotificationInboxProcessor(NotificationInboxRepository inboxRepository,
                                      NotificationDeliveryAttemptRepository attemptRepository,
                                      NotificationDeliveryService deliveryService,
                                      ObjectMapper objectMapper,
                                      @Value("${notification.delivery.provider:logging}") String provider) {
        this.inboxRepository = inboxRepository;
        this.attemptRepository = attemptRepository;
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
        this.provider = provider == null || provider.isBlank() ? "logging" : provider;
    }

    @Transactional
    public void process(NotificationInboxEvent event) {
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

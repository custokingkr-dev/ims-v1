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
import java.time.Duration;

@Service
public class NotificationInboxProcessor {

    private final NotificationInboxRepository inboxRepository;
    private final NotificationDeliveryAttemptRepository attemptRepository;
    private final NotificationDeliveryService deliveryService;
    private final ObjectMapper objectMapper;
    private final TraceContextBridge traceContextBridge;
    private final String provider;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public NotificationInboxProcessor(NotificationInboxRepository inboxRepository,
                                      NotificationDeliveryAttemptRepository attemptRepository,
                                      NotificationDeliveryService deliveryService,
                                      ObjectMapper objectMapper,
                                      String provider) {
        this(inboxRepository, attemptRepository, deliveryService, objectMapper,
                TraceContextBridge.noop(), provider, 8, Duration.ofSeconds(30), Duration.ofHours(1));
    }

    @Autowired
    public NotificationInboxProcessor(NotificationInboxRepository inboxRepository,
                                      NotificationDeliveryAttemptRepository attemptRepository,
                                      NotificationDeliveryService deliveryService,
                                      ObjectMapper objectMapper,
                                      TraceContextBridge traceContextBridge,
                                      @Value("${notification.delivery.provider:logging}") String provider,
                                      @Value("${notification.inbox.retry.max-attempts:8}") int maxAttempts,
                                      @Value("${notification.inbox.retry.initial-backoff:30s}") Duration initialBackoff,
                                      @Value("${notification.inbox.retry.max-backoff:1h}") Duration maxBackoff) {
        this.inboxRepository = inboxRepository;
        this.attemptRepository = attemptRepository;
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
        this.traceContextBridge = traceContextBridge;
        this.provider = provider == null || provider.isBlank() ? "logging" : provider;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()
                ? Duration.ofSeconds(30) : initialBackoff;
        this.maxBackoff = maxBackoff == null || maxBackoff.compareTo(this.initialBackoff) < 0
                ? this.initialBackoff : maxBackoff;
    }

    @Transactional(noRollbackFor = NotificationDeliveryFailedException.class)
    public void process(NotificationInboxEvent event) {
        NotificationInboxEvent locked = inboxRepository.findByIdForUpdate(event.getEventId()).orElse(null);
        if (locked == null || terminal(locked)) {
            return;
        }
        if (NotificationInboxEvent.STATUS_FAILED.equals(locked.getStatus())
                && locked.getNextAttemptAt() != null
                && locked.getNextAttemptAt().isAfter(OffsetDateTime.now())) {
            return;
        }
        traceContextBridge.runInSpan(
                "notification.process " + safe(locked.getEventType(), "event"),
                locked.getTraceParent(),
                locked.getTraceState(),
                () -> processOne(locked));
    }

    private boolean terminal(NotificationInboxEvent event) {
        return NotificationInboxEvent.STATUS_PROCESSED.equals(event.getStatus())
                || NotificationInboxEvent.STATUS_DEAD_LETTER.equals(event.getStatus())
                || NotificationInboxEvent.STATUS_SUPPRESSED.equals(event.getStatus());
    }

    private void processOne(NotificationInboxEvent event) {
        try {
            deliveryService.deliver(event);
        } catch (NotificationSuppressedException ex) {
            OffsetDateTime attemptedAt = OffsetDateTime.now();
            event.setStatus(NotificationInboxEvent.STATUS_SUPPRESSED);
            event.setProcessedAt(attemptedAt);
            event.setLastError(ex.reasonCode());
            event.setAttemptCount(event.getAttemptCount() + 1);
            event.setLastAttemptAt(attemptedAt);
            event.setNextAttemptAt(null);
            event.setDeadLetteredAt(null);
            inboxRepository.save(event);
            recordAttempt(event, NotificationDeliveryAttempt.STATUS_SUPPRESSED, ex.reasonCode());
            return;
        } catch (RuntimeException ex) {
            OffsetDateTime attemptedAt = OffsetDateTime.now();
            int attempts = event.getAttemptCount() + 1;
            event.setAttemptCount(attempts);
            event.setLastAttemptAt(attemptedAt);
            event.setLastError(ex.getMessage());
            if (attempts >= maxAttempts) {
                event.setStatus(NotificationInboxEvent.STATUS_DEAD_LETTER);
                event.setDeadLetteredAt(attemptedAt);
                event.setNextAttemptAt(null);
            } else {
                event.setStatus(NotificationInboxEvent.STATUS_FAILED);
                event.setNextAttemptAt(attemptedAt.plus(backoffFor(attempts)));
            }
            inboxRepository.save(event);
            recordAttempt(event, NotificationDeliveryAttempt.STATUS_FAILED, ex.getMessage());
            throw new NotificationDeliveryFailedException(event.getEventId(), ex);
        }
        event.setStatus(NotificationInboxEvent.STATUS_PROCESSED);
        event.setProcessedAt(OffsetDateTime.now());
        event.setLastError(null);
        event.setAttemptCount(event.getAttemptCount() + 1);
        event.setLastAttemptAt(OffsetDateTime.now());
        event.setNextAttemptAt(null);
        event.setDeadLetteredAt(null);
        inboxRepository.save(event);
        recordAttempt(event, NotificationDeliveryAttempt.STATUS_DELIVERED, null);
    }

    private Duration backoffFor(int attempts) {
        long multiplier = 1L << Math.min(20, Math.max(0, attempts - 1));
        long seconds;
        try {
            seconds = Math.multiplyExact(initialBackoff.toSeconds(), multiplier);
        } catch (ArithmeticException ignored) {
            seconds = maxBackoff.toSeconds();
        }
        return Duration.ofSeconds(Math.min(maxBackoff.toSeconds(), Math.max(1, seconds)));
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

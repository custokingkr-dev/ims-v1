package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import com.custoking.ims.platformservice.persistence.NotificationInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class NotificationAsyncHealthReporter {

    private static final Logger log = LoggerFactory.getLogger(NotificationAsyncHealthReporter.class);
    private final NotificationInboxRepository inbox;

    public NotificationAsyncHealthReporter(NotificationInboxRepository inbox) {
        this.inbox = inbox;
    }

    @Scheduled(
            initialDelayString = "${notification.inbox.health-log.initial-delay-ms:30000}",
            fixedDelayString = "${notification.inbox.health-log.fixed-delay-ms:60000}")
    public void report() {
        try {
            long received = inbox.countByStatus(NotificationInboxEvent.STATUS_RECEIVED);
            long failed = inbox.countByStatus(NotificationInboxEvent.STATUS_FAILED);
            long deadLetter = inbox.countByStatus(NotificationInboxEvent.STATUS_DEAD_LETTER);
            long oldestFailedAgeSeconds = inbox.findOldestReceivedAtByStatus(NotificationInboxEvent.STATUS_FAILED)
                    .map(timestamp -> Math.max(0L, Duration.between(timestamp, OffsetDateTime.now()).toSeconds()))
                    .orElse(0L);
            Map<String, Object> notificationInbox = Map.of(
                    "backlogCount", received + failed,
                    "receivedCount", received,
                    "failedCount", failed,
                    "deadLetterCount", deadLetter,
                    "oldestFailedAgeSeconds", oldestFailedAgeSeconds);
            log.info("notification.async.health {}", kv("health", Map.of("notificationInbox", notificationInbox)));
        } catch (RuntimeException ex) {
            log.warn("notification.async.health.failed error={}", ex.getMessage());
        }
    }
}

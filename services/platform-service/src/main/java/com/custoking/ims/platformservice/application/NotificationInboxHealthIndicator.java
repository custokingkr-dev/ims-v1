package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import com.custoking.ims.platformservice.persistence.NotificationInboxRepository;
import com.custoking.ims.platformservice.persistence.NotificationDeliveryAttempt;
import com.custoking.ims.platformservice.persistence.NotificationDeliveryAttemptRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

@Component("notificationInbox")
public class NotificationInboxHealthIndicator implements HealthIndicator {

    private final NotificationInboxRepository inboxRepository;
    private final NotificationDeliveryAttemptRepository attemptRepository;

    public NotificationInboxHealthIndicator(NotificationInboxRepository inboxRepository,
                                           NotificationDeliveryAttemptRepository attemptRepository) {
        this.inboxRepository = inboxRepository;
        this.attemptRepository = attemptRepository;
    }

    @Override
    public Health health() {
        try {
            long received = inboxRepository.countByStatus(NotificationInboxEvent.STATUS_RECEIVED);
            long processed = inboxRepository.countByStatus(NotificationInboxEvent.STATUS_PROCESSED);
            long failed = inboxRepository.countByStatus(NotificationInboxEvent.STATUS_FAILED);
            long deliveredAttempts = attemptRepository.countByStatus(NotificationDeliveryAttempt.STATUS_DELIVERED);
            long failedAttempts = attemptRepository.countByStatus(NotificationDeliveryAttempt.STATUS_FAILED);
            long oldestFailedAgeSeconds = inboxRepository
                    .findOldestReceivedAtByStatus(NotificationInboxEvent.STATUS_FAILED)
                    .map(NotificationInboxHealthIndicator::ageSeconds)
                    .orElse(0L);

            return Health.up()
                    .withDetail("received", received)
                    .withDetail("processed", processed)
                    .withDetail("failed", failed)
                    .withDetail("deliveredAttempts", deliveredAttempts)
                    .withDetail("failedAttempts", failedAttempts)
                    .withDetail("oldestFailedAgeSeconds", oldestFailedAgeSeconds)
                    .build();
        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }

    private static long ageSeconds(OffsetDateTime timestamp) {
        return Math.max(0L, Duration.between(timestamp, OffsetDateTime.now()).toSeconds());
    }
}

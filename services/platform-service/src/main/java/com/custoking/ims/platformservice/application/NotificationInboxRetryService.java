package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import com.custoking.ims.platformservice.persistence.NotificationInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NotificationInboxRetryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationInboxRetryService.class);

    private final NotificationInboxRepository inboxRepository;
    private final NotificationInboxProcessor inboxProcessor;
    private final boolean enabled;
    private final int batchSize;

    public NotificationInboxRetryService(NotificationInboxRepository inboxRepository,
                                         NotificationInboxProcessor inboxProcessor,
                                         @Value("${notification.inbox.retry.enabled:true}") boolean enabled,
                                         @Value("${notification.inbox.retry.batch-size:25}") int batchSize) {
        this.inboxRepository = inboxRepository;
        this.inboxProcessor = inboxProcessor;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${notification.inbox.retry.fixed-delay-ms:30000}")
    public void retryFailedEvents() {
        if (!enabled) {
            return;
        }

        List<NotificationInboxEvent> failedEvents = inboxRepository.findByStatusOrderByReceivedAtAsc(
                NotificationInboxEvent.STATUS_FAILED,
                PageRequest.of(0, batchSize));
        for (NotificationInboxEvent event : failedEvents) {
            retry(event);
        }
    }

    private void retry(NotificationInboxEvent event) {
        try {
            inboxProcessor.process(event);
            log.info("notification.inbox.retry.processed eventId={}", event.getEventId());
        } catch (RuntimeException ex) {
            log.warn("notification.inbox.retry.failed eventId={} error={}", event.getEventId(), ex.getMessage());
        }
    }
}

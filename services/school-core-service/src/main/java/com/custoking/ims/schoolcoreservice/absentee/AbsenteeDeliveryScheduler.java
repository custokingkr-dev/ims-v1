package com.custoking.ims.schoolcoreservice.absentee;

import com.custoking.ims.schoolcoreservice.persistence.AbsenteeNotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Scheduler entry points for the absentee delivery worker. Like {@code OutboxRelay}, this only
 * runs on an instance that is alive: with {@code minScale: 0} and no traffic nothing drains, so the
 * service needs {@code min-instances=1} for the queue to move outside request bursts.
 */
@Component
public class AbsenteeDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AbsenteeDeliveryScheduler.class);

    private final AbsenteeDeliveryWorker worker;
    private final AbsenteeNotificationDeliveryRepository repository;

    public AbsenteeDeliveryScheduler(AbsenteeDeliveryWorker worker, AbsenteeNotificationDeliveryRepository repository) {
        this.worker = worker;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${attendance.absentee-delivery.fixed-delay-ms:15000}",
            initialDelayString = "${attendance.absentee-delivery.initial-delay-ms:20000}")
    public void drain() {
        worker.runScheduled();
    }

    /** Queue depth snapshot: {@code jsonPayload.health.absenteeDelivery.deadLetterCount > 0} is alert-worthy. */
    @Scheduled(fixedDelayString = "${attendance.absentee-delivery.health-fixed-delay-ms:60000}",
            initialDelayString = "${attendance.absentee-delivery.health-initial-delay-ms:40000}")
    public void reportHealth() {
        try {
            Map<String, Object> state = repository.health();
            log.info("absentee.delivery.health {}", kv("health", Map.of("absenteeDelivery", state)));
        } catch (RuntimeException ex) {
            log.warn("absentee.delivery.health.failed error={}", ex.getMessage());
        }
    }
}

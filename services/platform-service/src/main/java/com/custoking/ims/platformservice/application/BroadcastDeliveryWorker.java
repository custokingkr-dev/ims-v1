package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository.QueuedRecipient;
import com.custoking.ims.platformservice.application.BroadcastRecipientPolicy.Recipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable at-least-once queue with an approved recipient binding and fresh policy on every attempt. */
@Service
public class BroadcastDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(BroadcastDeliveryWorker.class);
    private final BroadcastDispatchRepository repository;
    private final BroadcastRecipientPolicy policy;
    private final NotificationDeliveryCommandService delivery;
    private final BroadcastDispatchService workflows;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public BroadcastDeliveryWorker(BroadcastDispatchRepository repository, BroadcastRecipientPolicy policy,
            NotificationDeliveryCommandService delivery, BroadcastDispatchService workflows, ObjectMapper mapper,
            PlatformTransactionManager transactions) {
        this.repository = repository; this.policy = policy; this.delivery = delivery; this.workflows = workflows; this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${notification.broadcast.fixed-delay-ms:30000}")
    public void scheduled() {
        if (!workflows.queueEnabled()) return;
        try { drainBatch(); } catch (RuntimeException error) { log.warn("broadcast.worker.failed type={}", error.getClass().getSimpleName()); }
    }

    public int drainBatch() {
        if (!workflows.queueEnabled()) return 0;
        int processed = 0;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (processed < 25 && System.nanoTime() < deadline && Boolean.TRUE.equals(transaction.execute(status -> processNext()))) processed++;
        return processed;
    }

    private boolean processNext() {
        var claimed = repository.claim(OffsetDateTime.now());
        if (claimed.isEmpty()) return false;
        attempt(claimed.get()); return true;
    }

    void attempt(QueuedRecipient row) {
        String status;
        String reason = null;
        String provider = null;
        try {
            // A configuration change can never turn a dry-run queue into a live send.
            if (!"DRY_RUN".equals(row.mode()) || !delivery.dryRun()) throw new IllegalStateException("Live broadcast delivery is blocked");
            List<Recipient> decisions = policy.resolve(row.schoolId(), row.broadcastId(), List.of(row.channel()), List.of(row.studentId()));
            if (decisions.size() != 1) throw new IllegalStateException("Recipient policy response was incomplete");
            Recipient decision = decisions.getFirst();
            if (!decision.allowed()) {
                status = "SUPPRESSED"; reason = decision.reason();
            } else if (!row.eventId().equals(decision.eventId()) || !row.guardianId().equals(decision.guardianId())
                    || !row.destinationSha256().equals(decision.destinationSha256())) {
                status = "SUPPRESSED"; reason = "POLICY_BINDING_CHANGED";
            } else {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("sourceEventType", "school.broadcast-requested.v1"); payload.put("sourceEventId", row.eventId());
                payload.put("broadcastRequestId", row.eventId()); payload.put("broadcastId", row.broadcastId());
                payload.put("schoolId", row.schoolId()); payload.put("studentId", row.studentId());
                payload.put("notificationType", "SCHOOL_NOTICE"); payload.put("template", "school-notice.v1");
                payload.put("recipientType", "GUARDIAN"); payload.put("recipientId", decision.guardianId());
                payload.put("channel", row.channel()); payload.put("destination", decision.destination());
                payload.put("policyEvidence", decision.policyEvidence()); payload.put("subject", row.title());
                payload.put("message", row.message()); payload.put("variables", Map.of("title", row.title(), "message", row.message()));
                var answer = delivery.deliverNow(new NotificationDeliveryCommandService.DeliverNowCommand(
                        row.eventId(), "notification.requested.v1", row.eventId(), "SCHOOL_BROADCAST", row.broadcastId().toString(), mapper.valueToTree(payload)));
                provider = answer.provider();
                status = switch (answer.status()) {
                    case "DELIVERED" -> answer.dryRun() ? "DRY_RUN" : "DEAD_LETTER";
                    case "SUPPRESSED" -> "SUPPRESSED";
                    case "DEAD_LETTER" -> "DEAD_LETTER";
                    default -> "FAILED";
                };
                reason = answer.error() == null ? null
                        : "SUPPRESSED".equals(status) && answer.error().matches("[A-Z_]{3,100}")
                        ? answer.error() : "DELIVERY_ATTEMPT_FAILED";
                if (!answer.dryRun()) reason = "UNEXPECTED_LIVE_PROVIDER_OUTCOME";
            }
        } catch (RuntimeException error) {
            status = "FAILED";
            // Provider/transport exception text can contain contacts or credentials. Keep public outcomes safe.
            reason = "POLICY_OR_DELIVERY_UNAVAILABLE";
        }
        if ("FAILED".equals(status) && row.attempts() + 1 >= 8) status = "DEAD_LETTER";
        OffsetDateTime next = "FAILED".equals(status) ? OffsetDateTime.now().plusSeconds(Math.min(3600, 30L << Math.min(row.attempts(), 7))) : null;
        repository.outcome(row, status, reason, provider, next);
    }
}

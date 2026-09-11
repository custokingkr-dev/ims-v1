package com.custoking.ims.schoolcoreservice.absentee;

import com.custoking.ims.schoolcoreservice.absentee.AbsenteeDeliveryStateMachine.Transition;
import com.custoking.ims.schoolcoreservice.persistence.AbsenteeNotificationDeliveryRepository;
import com.custoking.ims.schoolcoreservice.persistence.AbsenteeNotificationDeliveryRepository.ClaimedNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Drains {@code attendance.absentee_notifications}: claims one row per transaction, re-evaluates the
 * guardian-communications policy, hands the row to the {@link AbsenteeDeliveryGateway}, and records
 * the resulting {@link AbsenteeDeliveryStateMachine.Transition}.
 *
 * <p><b>At-least-once.</b> Like {@code OutboxRelay}, the gateway is called before the row is marked.
 * A crash between the two re-delivers the row on the next tick; in live mode the platform inbox
 * dedupes on {@code eventId} and answers from its terminal state instead of re-sending.
 *
 * <p><b>Transactions are programmatic</b> ({@link TransactionTemplate}) rather than {@code @Transactional}
 * so the claim lock and the transaction-local RLS GUCs demonstrably cover the gateway call and the
 * mark, and so the behaviour is identical whether the worker is driven by the scheduler or by a test.
 * Spring's proxy would silently not apply to a self-invocation.
 *
 * <p>Wiring ({@code @Scheduled}, property binding) lives in {@link AbsenteeDeliveryConfiguration}.
 */
public class AbsenteeDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(AbsenteeDeliveryWorker.class);

    static final String EVENT_SENT = "absentee.delivery.sent";
    static final String EVENT_DRY_RUN = "absentee.delivery.dry_run";
    static final String EVENT_SUPPRESSED = "absentee.delivery.suppressed";
    static final String EVENT_FAILED = "absentee.delivery.failed";
    static final String EVENT_DEAD_LETTER = "absentee.delivery.dead_letter";

    private final AbsenteeNotificationDeliveryRepository repository;
    private final AbsenteeDispatchPolicy policy;
    private final AbsenteeDeliveryGateway gateway;
    private final TransactionTemplate transaction;
    private final AbsenteeDeliveryStateMachine machine;
    private final int batchSize;
    private final Clock clock;

    public AbsenteeDeliveryWorker(AbsenteeNotificationDeliveryRepository repository,
                                  AbsenteeDispatchPolicy policy,
                                  AbsenteeDeliveryGateway gateway,
                                  PlatformTransactionManager transactionManager,
                                  AbsenteeDeliveryStateMachine machine,
                                  int batchSize,
                                  Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.gateway = gateway;
        this.transaction = new TransactionTemplate(transactionManager);
        this.machine = machine;
        this.batchSize = Math.max(1, batchSize);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Scheduler entry point: never lets one bad tick take the service down. */
    public void runScheduled() {
        try {
            int processed = drainBatch();
            if (processed > 0) {
                log.info("absentee.delivery.tick processed={}", processed);
            }
        } catch (RuntimeException ex) {
            log.warn("absentee.delivery.tick_failed error={}", ex.getMessage());
        }
    }

    /**
     * Processes up to {@code batchSize} rows, one transaction each, stopping early when the queue is
     * empty. Returns how many rows were claimed and marked.
     */
    public int drainBatch() {
        int processed = 0;
        while (processed < batchSize) {
            Boolean claimed = transaction.execute(status -> processNext());
            if (!Boolean.TRUE.equals(claimed)) {
                break;
            }
            processed++;
        }
        return processed;
    }

    private boolean processNext() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<ClaimedNotification> claimed = repository.claimNextScoped(now);
        if (claimed.isEmpty()) {
            return false;
        }
        ClaimedNotification row = claimed.get();
        String eventId = AbsenteeDeliveryRequest.eventIdFor(row.id());
        DispatchDecision decision = null;
        DeliveryOutcome outcome;
        try {
            decision = policy.evaluate(row.schoolId(), row.studentId(), row.channel(), eventId);
            if (!decision.allowed()) {
                outcome = DeliveryOutcome.suppressed(decision.reason());
            } else if (!bindingUnchanged(row, decision)) {
                // The queue-time authorization named a specific guardian and destination. If the
                // fresh decision binds to different ones the queued message is not the one that was
                // authorized: fail closed rather than silently re-target.
                outcome = DeliveryOutcome.suppressed("POLICY_BINDING_CHANGED");
            } else {
                outcome = gateway.deliver(request(row, decision, eventId));
            }
        } catch (RuntimeException ex) {
            outcome = DeliveryOutcome.transientFailure(safeMessage(ex));
        }
        Transition transition = machine.apply(row.attempts(), outcome, now);
        int updated = repository.markOutcome(row, transition, outcome, decision);
        if (updated != 1) {
            log.error("absentee.delivery.failed {}", kv("absentee", fields(EVENT_FAILED, row, transition, outcome,
                    "SCOPED_UPDATE_AFFECTED_" + updated + "_ROWS")));
            throw new IllegalStateException("Scoped status update for " + row.id() + " affected " + updated + " rows");
        }
        logOutcome(row, transition, outcome);
        return true;
    }

    private static boolean bindingUnchanged(ClaimedNotification row, DispatchDecision decision) {
        return row.guardianId() != null && row.guardianId().equals(decision.guardianId())
                && row.destinationSha256() != null
                && row.destinationSha256().equalsIgnoreCase(decision.destinationSha256());
    }

    private static AbsenteeDeliveryRequest request(ClaimedNotification row, DispatchDecision decision, String eventId) {
        return new AbsenteeDeliveryRequest(
                eventId,
                row.id(),
                row.schoolId(),
                row.studentId(),
                decision.channel(),
                decision.destination(),
                decision.guardianId(),
                row.attendanceDate(),
                row.message(),
                decision.evidence());
    }

    private void logOutcome(ClaimedNotification row, Transition transition, DeliveryOutcome outcome) {
        switch (outcome.kind()) {
            case DELIVERED -> log.info("{} {}", EVENT_SENT, kv("absentee", fields(EVENT_SENT, row, transition, outcome, null)));
            case DRY_RUN -> log.info("{} {}", EVENT_DRY_RUN, kv("absentee", fields(EVENT_DRY_RUN, row, transition, outcome, null)));
            case SUPPRESSED -> log.info("{} {}", EVENT_SUPPRESSED, kv("absentee", fields(EVENT_SUPPRESSED, row, transition, outcome, outcome.error())));
            case TRANSIENT_FAILURE, PERMANENT_FAILURE -> {
                String event = transition.terminal() ? EVENT_DEAD_LETTER : EVENT_FAILED;
                log.warn("{} {}", event, kv("absentee", fields(event, row, transition, outcome, outcome.error())));
            }
        }
    }

    /**
     * Counter-friendly, PII-free shape: no destination, no message text, no student identity (the
     * notification id is the join key back to the row). A log-based metric on
     * {@code jsonPayload.absentee.event="absentee.delivery.failed"} (or {@code dead_letter}) is what
     * an alert should be built on.
     */
    private static Map<String, Object> fields(String event, ClaimedNotification row, Transition transition,
                                              DeliveryOutcome outcome, String error) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", event);
        fields.put("notificationId", row.id());
        fields.put("eventId", AbsenteeDeliveryRequest.eventIdFor(row.id()));
        fields.put("schoolId", row.schoolId());
        fields.put("channel", row.channel());
        fields.put("attendanceDate", String.valueOf(row.attendanceDate()));
        fields.put("status", transition.status());
        fields.put("attempts", transition.attempts());
        fields.put("terminal", transition.terminal());
        if (outcome.provider() != null) fields.put("provider", outcome.provider());
        if (outcome.providerMessageId() != null) fields.put("providerMessageId", outcome.providerMessageId());
        if (transition.nextAttemptAt() != null) fields.put("nextAttemptAt", transition.nextAttemptAt().toString());
        if (error != null) fields.put("error", error);
        return fields;
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}

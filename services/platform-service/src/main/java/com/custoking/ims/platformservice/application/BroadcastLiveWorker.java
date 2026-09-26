package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository.QueuedRecipient;
import com.custoking.ims.platformservice.persistence.BroadcastLiveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.util.List;

/** One durable submission reservation, then at most one provider call outside a transaction. */
@Service
public class BroadcastLiveWorker {
    private static final Logger log = LoggerFactory.getLogger(BroadcastLiveWorker.class);
    private final BroadcastDispatchRepository queue;
    private final BroadcastLiveRepository ledger;
    private final BroadcastRecipientPolicy policy;
    private final BroadcastLiveProvider provider;
    private final BroadcastDispatchService workflows;
    private final LiveBroadcastConfiguration configuration;
    private final TransactionTemplate transaction;
    public BroadcastLiveWorker(BroadcastDispatchRepository queue, BroadcastLiveRepository ledger, BroadcastRecipientPolicy policy,
            BroadcastLiveProvider provider, BroadcastDispatchService workflows, LiveBroadcastConfiguration configuration, PlatformTransactionManager manager) {
        this.queue=queue; this.ledger=ledger; this.policy=policy; this.provider=provider; this.workflows=workflows; this.configuration=configuration;
        transaction = new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelayString="${notification.broadcast.fixed-delay-ms:30000}")
    public void scheduled() {
        try { drainBatch(); } catch (RuntimeException failure) { log.warn("broadcast.live.worker.failed type={}", failure.getClass().getSimpleName()); }
    }
    public int drainBatch() {
        if (!workflows.liveQueueEnabled()) return 0;
        int handled=0; long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while (handled<3 && System.nanoTime()<deadline) {
            Plan plan = transaction.execute(tx -> prepareNext());
            if (plan == null) break;
            handled++;
            if (plan.prepared() == null) continue;
            // Returning from execute proves the reservation committed. A crash before/after the
            // network call leaves SUBMITTING/UNKNOWN and is never eligible for automatic retry.
            BroadcastLiveProvider.Result result;
            try { result = provider.submit(plan.prepared()); }
            catch (RuntimeException error) { result = new BroadcastLiveProvider.Result("UNKNOWN", null, "PROVIDER_RESULT_UNCONFIRMED"); }
            final var outcome=result;
            transaction.executeWithoutResult(tx -> ledger.finish(plan.row(), plan.prepared(), outcome));
        }
        return handled;
    }
    private Plan prepareNext() {
        var claimed=queue.claim(OffsetDateTime.now(),"LIVE");
        if (claimed.isEmpty()) return null;
        QueuedRecipient row=claimed.get();
        if (!configuration.schoolAllowed(row.schoolId()) || !configuration.channel().equals(row.channel())
                || !configuration.destinationAllowed(row.destinationSha256())) {
            ledger.preparationOutcome(row,"SUPPRESSED","LIVE_RECIPIENT_NOT_ADMITTED"); return new Plan(row,null);
        }
        BroadcastLiveProvider.Prepared prepared;
        try {
            var decisions=policy.resolve(row.schoolId(),row.broadcastId(),List.of(row.channel()),List.of(row.studentId()));
            if (decisions.size()!=1) throw new IllegalStateException("Incomplete recipient policy");
            var decision=decisions.getFirst();
            if (!decision.allowed()) {
                ledger.preparationOutcome(row,"SUPPRESSED",safeReason(decision.reason())); return new Plan(row,null);
            }
            if (decision.schoolId()!=row.schoolId() || decision.studentId()!=row.studentId() || !row.eventId().equals(decision.eventId())
                    || !row.channel().equals(decision.channel()) || !row.guardianId().equals(decision.guardianId())
                    || !row.destinationSha256().equals(decision.destinationSha256())) {
                ledger.preparationOutcome(row,"SUPPRESSED","POLICY_BINDING_CHANGED"); return new Plan(row,null);
            }
            prepared=provider.prepare(row,decision);
        } catch (RuntimeException failure) {
            ledger.preparationOutcome(row,"FAILED","LIVE_PREPARATION_UNAVAILABLE"); return new Plan(row,null);
        }
        return new Plan(row,ledger.reserve(row,prepared) ? prepared : null);
    }
    private static String safeReason(String reason) { return reason!=null && reason.matches("[A-Z_]{3,100}") ? reason : "CURRENT_POLICY_DENIED"; }
    private record Plan(QueuedRecipient row, BroadcastLiveProvider.Prepared prepared) {}
}

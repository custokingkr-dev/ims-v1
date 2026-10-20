package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.*;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

/** Provider I/O is outside transactions and is preceded by a separately committed reservation. */
@Service
public class GenericNotificationSubmissionWorker {
    private final NotificationInboxRepository inbox;
    private final NotificationDeliveryAttemptRepository attempts;
    private final GenericNotificationSubmissionRepository submissions;
    private final CurrentNotificationRecipientPolicy policy;
    private final NotificationDeliveryService delivery;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction, outside;
    public GenericNotificationSubmissionWorker(NotificationInboxRepository inbox, NotificationDeliveryAttemptRepository attempts,
            GenericNotificationSubmissionRepository submissions, CurrentNotificationRecipientPolicy policy,
            NotificationDeliveryService delivery,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.inbox=inbox; this.attempts=attempts; this.submissions=submissions; this.policy=policy;
        this.delivery=delivery; this.mapper=mapper;
        transaction=new TransactionTemplate(manager); transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
        outside=new TransactionTemplate(manager); outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }
    public void process(String eventId) { outside.executeWithoutResult(ignored -> run(eventId)); }
    private void run(String id) {
        NotificationInboxEvent snapshot=transaction.execute(tx -> inbox.findByIdForUpdate(id).filter(GenericNotificationSubmissionWorker::eligible).orElse(null));
        if(snapshot==null) return;
        try { policy.requireCurrent(snapshot); }
        catch(NotificationSuppressedException denied) {
            transaction.executeWithoutResult(tx -> inbox.findByIdForUpdate(id).filter(GenericNotificationSubmissionWorker::eligible).ifPresent(row -> {
                // Never suppress a concurrently refreshed or newly reserved request using stale evidence.
                if(!row.getPayload().equals(snapshot.getPayload())) return;
                row.setStatus("SUPPRESSED");row.setLastError(denied.reasonCode());row.setNextAttemptAt(null);
                row.setAttemptCount(row.getAttemptCount()+1);row.setLastAttemptAt(OffsetDateTime.now());row.setProcessedAt(OffsetDateTime.now());
                inbox.save(row);attempt(row,"SUPPRESSED",denied.reasonCode());
            })); return;
        }
        long school=mapper.readTree(snapshot.getPayload()).get("schoolId").asLong();
        NotificationDeliveryRequest request=NotificationDeliveryService.request(snapshot,mapper);
        String fingerprint=NotificationSubmissionResult.requestSha256(request);
        Boolean reserved=transaction.execute(tx -> {
            var row=inbox.findByIdForUpdate(id).orElse(null);
            if(row==null || !eligible(row) || !snapshot.getPayload().equals(row.getPayload())) return false;
            if(!submissions.reserve(id,school,fingerprint)) return false;
            row.setStatus("SUBMITTING");row.setNextAttemptAt(null);row.setLastAttemptAt(OffsetDateTime.now());
            row.setAttemptCount(row.getAttemptCount()+1);inbox.save(row);
            attempt(row,"SUBMITTING",null);return true;
        });
        if(!Boolean.TRUE.equals(reserved)) return;
        NotificationSubmissionResult result;
        try {
            // Recheck after reservation, before I/O. Revoked consent never reaches the provider.
            policy.requireCurrent(snapshot);
            result=delivery.submit(snapshot);
            if(result==null || !result.binds(request))
                result=NotificationSubmissionResult.of(request,NotificationSubmissionResult.Status.UNKNOWN,null,"PROVIDER_RECEIPT_BINDING_INVALID");
        } catch(RuntimeException uncertain) {
            result=NotificationSubmissionResult.of(request,NotificationSubmissionResult.Status.UNKNOWN,null,"PROVIDER_RESULT_UNCONFIRMED");
        }
        NotificationSubmissionResult receipt=result;
        String outcome=receipt.status().name();
        transaction.executeWithoutResult(tx -> {
            submissions.finish(id,school,fingerprint,outcome,receipt.correlationId(),receipt.providerRequestId(),receipt.reason());
            var row=inbox.findByIdForUpdate(id).orElse(null);
            if(row==null || !"SUBMITTING".equals(row.getStatus())) return; // erasure suppression wins
            row.setStatus(outcome);
            row.setLastError(receipt.reason());
            row.setNextAttemptAt(null);row.setProcessedAt(OffsetDateTime.now());inbox.save(row);
            attempt(row,outcome,row.getLastError());
        });
    }
    private void attempt(NotificationInboxEvent row,String status,String reason) {
        var attempt=new NotificationDeliveryAttempt();attempt.setEventId(row.getEventId());attempt.setEventType(row.getEventType());
        attempt.setProvider("msg91");attempt.setStatus(status);attempt.setError(reason);attempt.setAttemptedAt(OffsetDateTime.now());
        try { var channel=mapper.readTree(row.getPayload()).get("channel");attempt.setChannel(channel==null?null:channel.asString()); }
        catch(RuntimeException malformed) { attempt.setChannel(null); }
        attempts.save(attempt);
    }
    private static boolean eligible(NotificationInboxEvent row) {
        return List.of("RECEIVED","FAILED").contains(row.getStatus())
            && (row.getNextAttemptAt()==null || !row.getNextAttemptAt().isAfter(OffsetDateTime.now()));
    }
}

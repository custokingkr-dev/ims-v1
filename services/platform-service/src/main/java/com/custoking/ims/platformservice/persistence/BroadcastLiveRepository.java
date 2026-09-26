package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.application.BroadcastLiveProvider.Prepared;
import com.custoking.ims.platformservice.application.BroadcastLiveProvider.Result;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository.QueuedRecipient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class BroadcastLiveRepository {
    private final JdbcClient jdbc;
    public BroadcastLiveRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public void scope(long school) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Live evidence needs a transaction");
        jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single();
        jdbc.sql("SELECT set_config('app.current_school_id', :school, true)").param("school", Long.toString(school)).query(String.class).single();
    }
    public boolean reserve(QueuedRecipient row, Prepared prepared) {
        scope(row.schoolId());
        if (!row.channel().equals(prepared.channel()) || !row.destinationSha256().equals(prepared.destinationSha256())
                || !prepared.correlationId().matches("ims[0-9a-f]{48}") || !prepared.requestSha256().matches("[0-9a-f]{64}")
                || !prepared.senderSha256().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid submission binding");
        int added = jdbc.sql("""
            INSERT INTO notification.broadcast_live_submissions
              (event_id, school_id, broadcast_id, channel, correlation_id, destination_sha256, sender_sha256, request_sha256)
            VALUES (:event, :school, :broadcast, :channel, :correlation, :destination, :sender, :request)
            ON CONFLICT DO NOTHING
            """).param("event", row.eventId()).param("school", row.schoolId()).param("broadcast", row.broadcastId())
                .param("channel", prepared.channel()).param("correlation", prepared.correlationId())
                .param("destination", prepared.destinationSha256()).param("sender", prepared.senderSha256()).param("request", prepared.requestSha256()).update();
        if (added == 0) {
            Submission saved = byEvent(row.eventId());
            if (!saved.requestHash().equals(prepared.requestSha256())) throw new IllegalStateException("Submission fingerprint mismatch");
            return false; // A replay cannot downgrade an outcome or consume another attempt.
        }
        progress(row, "SUBMITTING", null, null, null, true);
        return true;
    }
    public void preparationOutcome(QueuedRecipient row, String status, String reason) {
        scope(row.schoolId());
        if ("FAILED".equals(status) && row.attempts() >= 7) status = "DEAD_LETTER";
        progress(row, status, reason, null, "FAILED".equals(status) ? OffsetDateTime.now().plusSeconds(Math.min(3600, 30L << Math.min(row.attempts(), 7))) : null, true);
    }
    public void finish(QueuedRecipient row, Prepared prepared, Result result) {
        scope(row.schoolId());
        Submission saved = byEvent(row.eventId());
        if (!saved.requestHash().equals(prepared.requestSha256())) throw new IllegalStateException("Submission fingerprint mismatch");
        boolean conflict = saved.providerId() != null && result.providerMessageId() != null && !saved.providerId().equals(result.providerMessageId());
        String delivery = conflict ? "REPORT_CONFLICT" : saved.delivery();
        String providerId = saved.providerId() == null ? result.providerMessageId() : saved.providerId();
        boolean definitive = List.of("ACCEPTED", "REJECTED").contains(saved.submission());
        boolean contrary = definitive && List.of("ACCEPTED", "REJECTED").contains(result.status()) && !saved.submission().equals(result.status());
        String submit = definitive ? saved.submission() : result.status();
        if (saved.delivery() != null) submit = "ACCEPTED";
        if (!List.of("ACCEPTED", "REJECTED", "UNKNOWN").contains(submit)) submit = "UNKNOWN";
        if (contrary || saved.delivery() != null && "REJECTED".equals(result.status())) delivery = "REPORT_CONFLICT";
        String reason = "REPORT_CONFLICT".equals(delivery) ? "CONFLICTING_PROVIDER_RESULTS"
                : definitive && "UNKNOWN".equals(result.status()) ? null : result.reason();
        jdbc.sql("""
            UPDATE notification.broadcast_live_submissions SET submission_status=:status, provider_message_id=:provider,
              delivery_status=:delivery, reason=:reason, updated_at=now() WHERE event_id=:event AND school_id=:school
            """).param("status", submit).param("provider", providerId).param("delivery", delivery)
                .param("reason", conflict ? "PROVIDER_ID_CONFLICT" : reason).param("event", row.eventId()).param("school", row.schoolId()).update();
        progress(row, delivery == null ? submit : delivery, conflict ? "PROVIDER_ID_CONFLICT" : reason, providerId, null, false);
    }
    public boolean report(String correlation, String providerId, String destinationHash, String senderHash,
            String status, OffsetDateTime providerAt, String reportHash) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Report needs a transaction");
        // Authentication is checked before this lookup. Discover only this correlation's school,
        // then disable bypass before binding or modifying any evidence.
        Long school;
        jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
        try { school = jdbc.sql("SELECT school_id FROM notification.broadcast_live_submissions WHERE correlation_id=:id")
                .param("id", correlation).query(Long.class).optional().orElse(null); }
        finally { jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single(); }
        if (school == null) return false;
        scope(school);
        Submission saved = jdbc.sql(select() + " WHERE correlation_id=:id FOR UPDATE").param("id", correlation).query(this::map).single();
        if (!saved.destinationHash().equals(destinationHash) || !saved.senderHash().equals(senderHash)
                || providerAt.isBefore(saved.submittedAt().minusMinutes(5)) || providerAt.isAfter(OffsetDateTime.now().plusMinutes(5))) return false;
        boolean idConflict = saved.providerId() != null && !saved.providerId().equals(providerId);
        int added = jdbc.sql("""
            INSERT INTO notification.broadcast_live_reports (report_sha256,event_id,school_id,provider_message_id,status,provider_at)
            VALUES (:hash,:event,:school,:provider,:status,:at) ON CONFLICT (report_sha256) DO NOTHING
            """).param("hash", reportHash).param("event", saved.eventId()).param("school", school).param("provider", providerId)
                .param("status", status).param("at", providerAt).update();
        if (added == 0) return true;
        String delivery = mergeDelivery(saved.delivery(), status);
        if (idConflict || "REJECTED".equals(saved.submission())) delivery = "REPORT_CONFLICT";
        String boundId = saved.providerId() == null ? providerId : saved.providerId();
        jdbc.sql("""
            UPDATE notification.broadcast_live_submissions SET provider_message_id=:provider, delivery_status=:delivery,
              submission_status='ACCEPTED', reason=:reason, updated_at=now() WHERE event_id=:event AND school_id=:school
            """).param("provider", boundId).param("delivery", delivery).param("reason", "REPORT_CONFLICT".equals(delivery) ? "CONFLICTING_PROVIDER_REPORTS" : null)
                .param("event", saved.eventId()).param("school", school).update();
        jdbc.sql("""
            UPDATE notification.notification_broadcast_recipients SET status=:status, provider_message_id=:provider,
              reason=:reason, next_attempt_at=NULL, updated_at=now() WHERE event_id=:event AND school_id=:school AND dry_run=FALSE
            """).param("status", delivery).param("provider", boundId).param("reason", "REPORT_CONFLICT".equals(delivery) ? "CONFLICTING_PROVIDER_REPORTS" : null)
                .param("event", saved.eventId()).param("school", school).update();
        aggregate(saved.broadcastId(), school);
        return true;
    }
    static String mergeDelivery(String previous, String next) {
        if (previous == null || "ACCEPTED".equals(previous)) return next;
        if ("REPORT_CONFLICT".equals(previous) || "ACCEPTED".equals(next) || previous.equals(next)) return previous;
        return "REPORT_CONFLICT";
    }
    private Submission byEvent(String event) { return jdbc.sql(select() + " WHERE event_id=:event FOR UPDATE").param("event", event).query(this::map).single(); }
    private String select() { return "SELECT event_id,school_id,broadcast_id,request_sha256,destination_sha256,sender_sha256,provider_message_id,submission_status,delivery_status,submitted_at FROM notification.broadcast_live_submissions"; }
    private Submission map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Submission(rs.getString("event_id"), rs.getLong("school_id"), rs.getObject("broadcast_id", UUID.class), rs.getString("request_sha256"),
                rs.getString("destination_sha256"), rs.getString("sender_sha256"), rs.getString("provider_message_id"), rs.getString("submission_status"), rs.getString("delivery_status"), rs.getObject("submitted_at", OffsetDateTime.class));
    }
    private void progress(QueuedRecipient row, String status, String reason, String providerId, OffsetDateTime next, boolean attempt) {
        int changed = jdbc.sql("""
            UPDATE notification.notification_broadcast_recipients SET status=:status, reason=:reason, provider='msg91', dry_run=FALSE,
              provider_message_id=coalesce(:provider,provider_message_id), next_attempt_at=:next,
              attempts=attempts+:increment, last_attempt_at=now(), updated_at=now() WHERE id=:id AND school_id=:school
            """).param("status", status).param("reason", reason).param("provider", providerId).param("next", next)
                .param("increment", attempt ? 1 : 0).param("id", row.id()).param("school", row.schoolId()).update();
        if (changed != 1) throw new IllegalStateException("Live outcome scope mismatch");
        aggregate(row.broadcastId(), row.schoolId());
    }
    public void aggregate(UUID broadcast, long school) {
        jdbc.sql("SELECT id FROM notification.notification_broadcasts WHERE id=:id AND school_id=:school FOR UPDATE")
                .param("id", broadcast).param("school", school).query(UUID.class).single();
        jdbc.sql("""
            UPDATE notification.notification_broadcasts SET status=CASE
              WHEN EXISTS (SELECT 1 FROM notification.notification_broadcast_recipients WHERE broadcast_id=:id AND status IN ('SUBMITTING','UNKNOWN','REPORT_CONFLICT')) THEN 'NEEDS_RECONCILIATION'
              WHEN EXISTS (SELECT 1 FROM notification.notification_broadcast_recipients WHERE broadcast_id=:id AND status IN ('QUEUED','FAILED')) THEN 'QUEUED'
              WHEN EXISTS (SELECT 1 FROM notification.notification_broadcast_recipients WHERE broadcast_id=:id AND status='ACCEPTED') THEN 'AWAITING_DELIVERY'
              WHEN EXISTS (SELECT 1 FROM notification.notification_broadcast_recipients WHERE broadcast_id=:id AND status IN ('REJECTED','DELIVERY_FAILED','DEAD_LETTER')) THEN 'COMPLETED_WITH_FAILURES'
              ELSE 'COMPLETED' END, updated_at=now() WHERE id=:id AND school_id=:school AND dispatch_mode='LIVE'
            """).param("id", broadcast).param("school", school).update();
    }
    private record Submission(String eventId, long schoolId, UUID broadcastId, String requestHash, String destinationHash,
            String senderHash, String providerId, String submission, String delivery, OffsetDateTime submittedAt) {}
}

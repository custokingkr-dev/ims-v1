package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.application.BroadcastRecipientPolicy.Recipient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class BroadcastDispatchRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public BroadcastDispatchRepository(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public Broadcast find(UUID id, boolean lock) {
        return jdbc.sql("SELECT id, school_id, title, message, audience_type, channels, communication_category, status, dispatch_mode, approval_mode, approval_fingerprint FROM notification.notification_broadcasts WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query((rs, row) -> new Broadcast(rs.getObject("id", UUID.class), (Long) rs.getObject("school_id"),
                        rs.getString("title"), rs.getString("message"), rs.getString("audience_type"),
                        Arrays.stream(rs.getString("channels").split(",")).map(value -> value.trim().toUpperCase(Locale.ROOT)).distinct().toList(),
                        rs.getString("communication_category"), rs.getString("status"), rs.getString("dispatch_mode"), rs.getString("approval_mode"), rs.getString("approval_fingerprint")))
                .optional().orElseThrow(() -> new IllegalArgumentException("Broadcast not found"));
    }

    public void approve(Broadcast broadcast, List<Recipient> recipients, Long actorId) {
        approve(broadcast, recipients, actorId, "DRY_RUN", null);
    }

    public void approve(Broadcast broadcast, List<Recipient> recipients, Long actorId, String mode, String fingerprint) {
        Set<String> destinations = new HashSet<>();
        for (Recipient recipient : recipients) {
            boolean duplicate = recipient.allowed() && !destinations.add(recipient.channel() + ":" + recipient.destinationSha256());
            String status = !recipient.allowed() ? "SUPPRESSED" : duplicate ? "DUPLICATE" : "APPROVED";
            jdbc.sql("""
                    INSERT INTO notification.notification_broadcast_recipients
                      (id, broadcast_id, school_id, student_id, channel, event_id, guardian_id, destination_sha256, approval_evidence, status, reason)
                    VALUES (:id, :broadcastId, :schoolId, :studentId, :channel, :eventId, :guardianId, :destinationHash, :evidence, :status, :reason)
                    ON CONFLICT (broadcast_id, student_id, channel) DO NOTHING
                    """)
                    .param("id", UUID.nameUUIDFromBytes(recipient.eventId().getBytes(StandardCharsets.UTF_8)))
                    .param("broadcastId", broadcast.id()).param("schoolId", broadcast.schoolId()).param("studentId", recipient.studentId())
                    .param("channel", recipient.channel()).param("eventId", recipient.eventId()).param("guardianId", recipient.guardianId())
                    .param("destinationHash", recipient.destinationSha256()).param("evidence", recipient.policyEvidence() == null ? null : mapper.writeValueAsString(recipient.policyEvidence()))
                    .param("status", status).param("reason", duplicate ? "DUPLICATE_DESTINATION" : recipient.allowed() ? null : recipient.reason()).update();
        }
        jdbc.sql("UPDATE notification.notification_broadcasts SET status = 'APPROVED', approved_by = :actor, approval_mode = :mode, approval_fingerprint = :fingerprint, approved_at = now(), updated_at = now() WHERE id = :id AND school_id = :schoolId")
                .param("id", broadcast.id()).param("schoolId", broadcast.schoolId()).param("actor", actorId).param("mode", mode).param("fingerprint", fingerprint).update();
    }

    public void queue(Broadcast broadcast, String mode, Long actor) {
        jdbc.sql("UPDATE notification.notification_broadcast_recipients SET status = 'QUEUED', updated_at = now() WHERE broadcast_id = :id AND school_id = :schoolId AND status = 'APPROVED'")
                .param("id", broadcast.id()).param("schoolId", broadcast.schoolId()).update();
        jdbc.sql("UPDATE notification.notification_broadcasts SET status = 'QUEUED', dispatch_mode = :mode, queued_at = now(), queued_by = :actor, updated_at = now() WHERE id = :id AND school_id = :schoolId")
                .param("id", broadcast.id()).param("schoolId", broadcast.schoolId()).param("mode", mode).param("actor", actor).update();
    }

    public Map<String, Object> outcomes(Broadcast broadcast) {
        List<Map<String, Object>> recipients = jdbc.sql("""
                SELECT student_id, channel, status, reason, attempts, next_attempt_at, provider, provider_message_id, dry_run
                FROM notification.notification_broadcast_recipients
                WHERE broadcast_id = :id AND school_id = :schoolId ORDER BY student_id, channel
                """).param("id", broadcast.id()).param("schoolId", broadcast.schoolId())
                .query((rs, row) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("studentId", rs.getLong("student_id")); result.put("channel", rs.getString("channel"));
                    result.put("status", rs.getString("status")); result.put("reason", rs.getString("reason"));
                    result.put("attempts", rs.getInt("attempts")); result.put("nextAttemptAt", rs.getObject("next_attempt_at", OffsetDateTime.class));
                    result.put("provider", rs.getString("provider")); result.put("providerMessageId", rs.getString("provider_message_id")); result.put("dryRun", rs.getBoolean("dry_run")); return result;
                }).list();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> recipient : recipients) counts.merge(String.valueOf(recipient.get("status")), 1L, Long::sum);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("broadcastId", broadcast.id()); result.put("status", broadcast.status()); result.put("mode", broadcast.mode());
        result.put("approvalMode", broadcast.approvalMode());
        result.put("total", recipients.size()); result.put("counts", counts); result.put("recipients", recipients);
        result.put("delivered", "LIVE".equals(broadcast.mode()) ? Math.toIntExact(counts.getOrDefault("DELIVERED", 0L)) : 0);
        return result;
    }

    public int retry(Broadcast broadcast) {
        return jdbc.sql("UPDATE notification.notification_broadcast_recipients SET next_attempt_at = now(), updated_at = now() WHERE broadcast_id = :id AND school_id = :schoolId AND status = 'FAILED' AND attempts < 8")
                .param("id", broadcast.id()).param("schoolId", broadcast.schoolId()).update();
    }

    public Optional<QueuedRecipient> claim(OffsetDateTime now) {
        return claim(now, "DRY_RUN");
    }

    public Optional<QueuedRecipient> claim(OffsetDateTime now, String mode) {
        if (!List.of("DRY_RUN", "LIVE").contains(mode)) throw new IllegalArgumentException("Unsupported dispatch mode");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Claim needs a transaction");
        jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
        Optional<QueuedRecipient> claimed;
        try {
            claimed = jdbc.sql("""
                    SELECT r.id, r.broadcast_id, r.school_id, r.student_id, r.channel, r.event_id, r.guardian_id,
                           r.destination_sha256, r.attempts, b.title, b.message, b.dispatch_mode
                    FROM notification.notification_broadcast_recipients r
                    JOIN notification.notification_broadcasts b ON b.id = r.broadcast_id AND b.school_id = r.school_id
                    WHERE r.status IN ('QUEUED', 'FAILED') AND b.dispatch_mode = :mode
                      AND (b.scheduled_at IS NULL OR b.scheduled_at <= :now)
                      AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= :now)
                    ORDER BY r.created_at, r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                    """).param("now", now).param("mode", mode).query((rs, row) -> new QueuedRecipient(rs.getObject("id", UUID.class), rs.getObject("broadcast_id", UUID.class),
                            rs.getLong("school_id"), rs.getLong("student_id"), rs.getString("channel"), rs.getString("event_id"), rs.getString("guardian_id"),
                            rs.getString("destination_sha256"), rs.getInt("attempts"), rs.getString("title"), rs.getString("message"), rs.getString("dispatch_mode"))).optional();
        } finally {
            jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single();
        }
        claimed.ifPresent(row -> jdbc.sql("SELECT set_config('app.current_school_id', :schoolId, true)").param("schoolId", String.valueOf(row.schoolId())).query(String.class).single());
        // Serialize status aggregation within a broadcast so simultaneous last outcomes cannot leave it QUEUED.
        claimed.ifPresent(row -> jdbc.sql("SELECT id FROM notification.notification_broadcasts WHERE id = :id AND school_id = :schoolId FOR UPDATE")
                .param("id", row.broadcastId()).param("schoolId", row.schoolId()).query(UUID.class).single());
        return claimed;
    }

    public void outcome(QueuedRecipient row, String status, String reason, String provider, OffsetDateTime nextAttemptAt) {
        int updated = jdbc.sql("""
                UPDATE notification.notification_broadcast_recipients SET status = :status, reason = :reason,
                  attempts = attempts + 1, provider = :provider, dry_run = TRUE, next_attempt_at = :next,
                  last_attempt_at = now(), updated_at = now()
                WHERE id = :id AND school_id = :schoolId
                """).param("id", row.id()).param("schoolId", row.schoolId()).param("status", status).param("reason", reason)
                .param("provider", provider).param("next", nextAttemptAt).update();
        if (updated != 1) throw new IllegalStateException("Recipient outcome was not recorded in its school scope");
        jdbc.sql("""
                UPDATE notification.notification_broadcasts SET status = CASE
                  WHEN EXISTS (SELECT 1 FROM notification.notification_broadcast_recipients WHERE broadcast_id = :broadcastId AND status IN ('QUEUED', 'FAILED')) THEN 'QUEUED'
                  WHEN EXISTS (SELECT 1 FROM notification.notification_broadcast_recipients WHERE broadcast_id = :broadcastId AND status = 'DEAD_LETTER') THEN 'COMPLETED_WITH_FAILURES'
                  ELSE 'DRY_RUN_COMPLETE' END, updated_at = now()
                WHERE id = :broadcastId AND school_id = :schoolId
                """).param("broadcastId", row.broadcastId()).param("schoolId", row.schoolId()).update();
    }

    public record Broadcast(UUID id, Long schoolId, String title, String message, String audienceType, List<String> channels,
            String communicationCategory, String status, String mode, String approvalMode, String approvalFingerprint) {
        public Broadcast(UUID id, Long schoolId, String title, String message, String audienceType, List<String> channels,
                String communicationCategory, String status, String mode) {
            this(id, schoolId, title, message, audienceType, channels, communicationCategory, status, mode, "DRY_RUN", null);
        }
    }
    public record QueuedRecipient(UUID id, UUID broadcastId, long schoolId, long studentId, String channel, String eventId,
            String guardianId, String destinationSha256, int attempts, String title, String message, String mode) {}
}

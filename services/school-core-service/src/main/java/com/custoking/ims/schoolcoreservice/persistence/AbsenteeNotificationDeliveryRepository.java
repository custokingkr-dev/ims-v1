package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.absentee.AbsenteeDeliveryStateMachine.Transition;
import com.custoking.ims.schoolcoreservice.absentee.AbsenteeNotificationStatus;
import com.custoking.ims.schoolcoreservice.absentee.DeliveryOutcome;
import com.custoking.ims.schoolcoreservice.absentee.DispatchDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Claim-and-mark access to {@code attendance.absentee_notifications} for the delivery worker.
 *
 * <p><b>Tenant safety.</b> The worker runs on a scheduler thread with no {@code TenantContext}, so
 * {@code TenantAwareDataSource} sets an empty {@code app.current_school_id} and the RLS policy hides
 * every row. Claiming needs to see all tenants; writing must not. {@link #claimNextScoped} therefore
 * enables the bypass <em>transaction-locally</em>, selects one row {@code FOR UPDATE SKIP LOCKED},
 * and immediately turns the bypass off again while scoping {@code app.current_school_id} to the
 * claimed row's school — all before returning. Every statement that follows in the same
 * transaction, {@link #markOutcome} included, runs under RLS for that one school. A previous review
 * closed a Critical where exactly this bypass leaked into writes; keeping the sequence inside a
 * single method is what makes it reviewable, and {@code AbsenteeDeliveryTenantScopeIntegrationTest}
 * proves it as {@code app_rt}.
 */
@Repository
public class AbsenteeNotificationDeliveryRepository {

    private final JdbcClient jdbc;
    private final String table;

    public AbsenteeNotificationDeliveryRepository(JdbcClient jdbc,
                                                  @Value("${attendance.db.schema:attendance}") String schema) {
        this.jdbc = jdbc;
        this.table = qualifiedTable(schema);
    }

    /**
     * Claims the oldest claimable row (QUEUED, or FAILED with its backoff elapsed) and scopes the
     * current transaction to that row's school. Must be called inside an active transaction: the
     * row lock and the transaction-local GUCs both end with it.
     */
    public Optional<ClaimedNotification> claimNextScoped(OffsetDateTime now) {
        requireTransaction();
        jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
        Optional<ClaimedNotification> claimed;
        try {
            claimed = jdbc.sql("""
                            SELECT id, school_id, student_id, class_id, section_id, academic_year_id,
                                   attendance_date, parent_contact, channel, message, attempts,
                                   guardian_id, destination_sha256, consent_event_id, consent_notice_version
                            FROM %s
                            WHERE status IN ('QUEUED', 'FAILED')
                              AND (next_attempt_at IS NULL OR next_attempt_at <= :now::timestamptz)
                            ORDER BY created_at, id
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                            """.formatted(table))
                    .param("now", now)
                    .query((rs, rowNum) -> new ClaimedNotification(
                            rs.getString("id"),
                            rs.getLong("school_id"),
                            rs.getLong("student_id"),
                            rs.getString("class_id"),
                            rs.getString("section_id"),
                            rs.getString("academic_year_id"),
                            rs.getObject("attendance_date", LocalDate.class),
                            rs.getString("parent_contact"),
                            rs.getString("channel"),
                            rs.getString("message"),
                            rs.getInt("attempts"),
                            rs.getString("guardian_id"),
                            rs.getString("destination_sha256"),
                            rs.getString("consent_event_id"),
                            rs.getString("consent_notice_version")))
                    .optional();
        } finally {
            // Drop the bypass no matter what; if nothing was claimed the scope stays empty (sees nothing).
            jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single();
        }
        claimed.ifPresent(row -> jdbc.sql("SELECT set_config('app.current_school_id', :schoolId, true)")
                .param("schoolId", String.valueOf(row.schoolId()))
                .query(String.class)
                .single());
        return claimed;
    }

    /**
     * Persists the outcome of one attempt. The predicate carries both the id and the school so that,
     * even if RLS were somehow disabled, the statement could never cross tenants. Returns the number
     * of rows updated — the worker treats anything other than 1 as a defect.
     */
    public int markOutcome(ClaimedNotification row, Transition transition, DeliveryOutcome outcome,
                           DispatchDecision freshDecision) {
        boolean refreshEvidence = freshDecision != null && freshDecision.allowed();
        return jdbc.sql("""
                        UPDATE %s
                        SET status = :status,
                            attempts = :attempts,
                            last_attempt_at = now(),
                            next_attempt_at = :nextAttemptAt::timestamptz,
                            last_error = left(:error::text, 1000),
                            provider = :provider::text,
                            provider_message_id = :providerMessageId::text,
                            delivered_at = COALESCE(:deliveredAt::timestamptz, delivered_at),
                            dead_lettered_at = :deadLetteredAt::timestamptz,
                            consent_event_id = COALESCE(:consentEventId::text, consent_event_id),
                            consent_notice_version = COALESCE(:consentNoticeVersion::text, consent_notice_version),
                            policy_evaluated_at = COALESCE(:policyEvaluatedAt::timestamptz, policy_evaluated_at),
                            policy_expires_at = COALESCE(:policyExpiresAt::timestamptz, policy_expires_at),
                            updated_at = now()
                        WHERE id = :id AND school_id = :schoolId
                        """.formatted(table))
                .param("id", row.id())
                .param("schoolId", row.schoolId())
                .param("status", transition.status())
                .param("attempts", transition.attempts())
                .param("nextAttemptAt", transition.nextAttemptAt())
                .param("error", outcome.error())
                .param("provider", outcome.provider())
                .param("providerMessageId", outcome.providerMessageId())
                .param("deliveredAt", transition.deliveredAt())
                .param("deadLetteredAt", transition.deadLetteredAt())
                .param("consentEventId", refreshEvidence ? freshDecision.consentEventId() : null)
                .param("consentNoticeVersion", refreshEvidence ? freshDecision.consentNoticeVersion() : null)
                .param("policyEvaluatedAt", refreshEvidence ? freshDecision.evaluatedAt() : null)
                .param("policyExpiresAt", refreshEvidence ? freshDecision.expiresAt() : null)
                .update();
    }

    /**
     * Cross-tenant queue depth for the health log line. Read-only; the bypass is confined to the
     * single statement via a MATERIALIZED CTE (see {@code PlatformBusinessHealthReporter} for why).
     */
    public Map<String, Object> health() {
        return jdbc.sql("""
                        WITH bypass AS MATERIALIZED (SELECT set_config('app.bypass_rls', 'on', true))
                        SELECT (SELECT count(*) FROM %1$s WHERE status = '%2$s') AS queued,
                               (SELECT count(*) FROM %1$s WHERE status = '%3$s') AS failed,
                               (SELECT count(*) FROM %1$s WHERE status = '%4$s') AS dead_letter,
                               COALESCE((SELECT EXTRACT(EPOCH FROM (now() - created_at))::bigint FROM %1$s
                                         WHERE status IN ('%2$s', '%3$s') ORDER BY created_at LIMIT 1), 0) AS oldest_age
                        FROM bypass
                        """.formatted(table, AbsenteeNotificationStatus.QUEUED, AbsenteeNotificationStatus.FAILED,
                        AbsenteeNotificationStatus.DEAD_LETTER))
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "queuedCount", rs.getLong("queued"),
                        "failedCount", rs.getLong("failed"),
                        "deadLetterCount", rs.getLong("dead_letter"),
                        "oldestPendingAgeSeconds", rs.getLong("oldest_age")))
                .single();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // Without a transaction the FOR UPDATE lock and the transaction-local GUCs would both be
            // released before the caller could act on them -- silently, with every row visible.
            throw new IllegalStateException("claimNextScoped requires an active transaction");
        }
    }

    private static String qualifiedTable(String schema) {
        String normalized = schema == null || schema.isBlank() ? "attendance" : schema;
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid database identifier: " + normalized);
        }
        return normalized + ".absentee_notifications";
    }

    public record ClaimedNotification(
            String id,
            long schoolId,
            long studentId,
            String classId,
            String sectionId,
            String academicYearId,
            LocalDate attendanceDate,
            String parentContact,
            String channel,
            String message,
            int attempts,
            String guardianId,
            String destinationSha256,
            String consentEventId,
            String consentNoticeVersion) {
    }
}

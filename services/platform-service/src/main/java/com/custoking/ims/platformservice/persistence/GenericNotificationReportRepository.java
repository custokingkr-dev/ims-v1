package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.application.GenericNotificationReport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.OffsetDateTime;

/** Minimal append-only evidence; the original submission and inbox outcome are never promoted. */
@Repository
public class GenericNotificationReportRepository {
    private final JdbcClient jdbc;
    public GenericNotificationReportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public boolean reconcile(GenericNotificationReport report) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Report requires a transaction");
        jdbc.sql("SELECT set_config('app.bypass_rls','off',true)").query(String.class).single();
        jdbc.sql("SELECT set_config('app.current_school_id',:school,true)")
                .param("school", Long.toString(report.schoolId())).query(String.class).single();
        jdbc.sql("SELECT set_config('statement_timeout','5000',true)").query(String.class).single();
        jdbc.sql("SELECT set_config('lock_timeout','2000',true)").query(String.class).single();
        OffsetDateTime submitted = jdbc.sql("""
            SELECT submitted_at FROM notification.generic_submissions
            WHERE event_id=:event AND school_id=:school AND request_sha256=:request
              AND correlation_id=:correlation AND provider_request_id=:provider AND status='ACCEPTED'
            """).param("event", report.eventId()).param("school", report.schoolId())
                .param("request", report.requestSha256()).param("correlation", report.correlationId())
                .param("provider", report.providerRequestId()).query(OffsetDateTime.class).optional().orElse(null);
        if (submitted == null || report.occurredAt().isBefore(submitted.minusMinutes(5))
                || report.occurredAt().isAfter(OffsetDateTime.now().plusMinutes(5))) return false;
        // Lock the inbox before delivery state, matching the suppression trigger's lock order.
        // Erasure commits before us (reject) or after us (atomically suppresses our result).
        String inbox = jdbc.sql("SELECT status FROM notification.notification_inbox_events WHERE event_id=:event FOR UPDATE")
                .param("event", report.eventId()).query(String.class).optional().orElse(null);
        if (!"ACCEPTED".equals(inbox)) return false;
        jdbc.sql("""
            INSERT INTO notification.generic_delivery_results
              (event_id,school_id,request_sha256,correlation_id,provider_request_id,delivery_status)
            VALUES(:event,:school,:request,:correlation,:provider,'ACCEPTED') ON CONFLICT(event_id) DO NOTHING
            """).param("event", report.eventId()).param("school", report.schoolId())
                .param("request", report.requestSha256()).param("correlation", report.correlationId())
                .param("provider", report.providerRequestId()).update();
        String previous = jdbc.sql("SELECT delivery_status FROM notification.generic_delivery_results WHERE event_id=:event AND school_id=:school FOR UPDATE")
                .param("event", report.eventId()).param("school", report.schoolId()).query(String.class).single();
        if ("SUPPRESSED".equals(previous)) return false;
        int added = jdbc.sql("""
            INSERT INTO notification.generic_delivery_reports
              (report_sha256,event_id,school_id,evidence_sha256,status,provider_at)
            VALUES(:hash,:event,:school,:evidence,:status,:at) ON CONFLICT(report_sha256) DO NOTHING
            """).param("hash", report.reportSha256()).param("event", report.eventId()).param("school", report.schoolId())
                .param("evidence", report.evidenceSha256()).param("status", report.status().name()).param("at", report.occurredAt()).update();
        if (added == 0) return true;
        boolean alteredEvidence = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM notification.generic_delivery_reports
              WHERE event_id=:event AND school_id=:school AND evidence_sha256=:evidence AND report_sha256<>:hash)
            """).param("event", report.eventId()).param("school", report.schoolId())
                .param("evidence", report.evidenceSha256()).param("hash", report.reportSha256()).query(Boolean.class).single();
        String next = alteredEvidence ? "REPORT_CONFLICT" : merge(previous, report.status().name());
        jdbc.sql("""
            UPDATE notification.generic_delivery_results SET delivery_status=:status,updated_at=now()
            WHERE event_id=:event AND school_id=:school
            """).param("status", next).param("event", report.eventId()).param("school", report.schoolId()).update();
        return true;
    }
    static String merge(String previous, String next) {
        if ("SUPPRESSED".equals(previous) || "REPORT_CONFLICT".equals(previous)) return previous;
        if ("ACCEPTED".equals(previous)) return next;
        if (previous.equals(next) || "ACCEPTED".equals(next)) return previous;
        return "REPORT_CONFLICT";
    }
}

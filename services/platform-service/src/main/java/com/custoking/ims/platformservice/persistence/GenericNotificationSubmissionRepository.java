package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Irreversible per-event provider attempt reservation. Never deleted by retry or inbox reset. */
@Repository
public class GenericNotificationSubmissionRepository {
    private final JdbcClient jdbc;
    public GenericNotificationSubmissionRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    private void scope(long school) {
        if(school<=0 || !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Submission requires a school-scoped transaction");
        jdbc.sql("SELECT set_config('app.bypass_rls','off',true)").query(String.class).single();
        jdbc.sql("SELECT set_config('app.current_school_id',:school,true)").param("school",Long.toString(school)).query(String.class).single();
    }
    public boolean reserve(String event,long school,String requestHash) {
        scope(school);
        if(event==null || event.isBlank() || event.length()>120 || !requestHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid submission binding");
        int added=jdbc.sql("INSERT INTO notification.generic_submissions(event_id,school_id,request_sha256) VALUES(:event,:school,:hash) ON CONFLICT(event_id) DO NOTHING")
            .param("event",event).param("school",school).param("hash",requestHash).update();
        if(added==0) {
            String saved=jdbc.sql("SELECT request_sha256 FROM notification.generic_submissions WHERE event_id=:event AND school_id=:school")
                .param("event",event).param("school",school).query(String.class).optional().orElse(null);
            if(!requestHash.equals(saved)) throw new IllegalStateException("Submission binding mismatch");
        }
        return added==1;
    }
    public void finish(String event,long school,String requestHash,String status) {
        finish(event,school,requestHash,status,com.custoking.ims.platformservice.application.NotificationSubmissionResult.correlationId(event),null,"PROVIDER_RESULT_UNCONFIRMED");
    }
    public void finish(String event,long school,String requestHash,String status,String correlation,String providerId,String reason) {
        scope(school);
        new com.custoking.ims.platformservice.application.NotificationSubmissionResult(
            com.custoking.ims.platformservice.application.NotificationSubmissionResult.Status.valueOf(status),event,correlation,requestHash,providerId,reason);
        if(!correlation.equals(com.custoking.ims.platformservice.application.NotificationSubmissionResult.correlationId(event)))
            throw new IllegalArgumentException("Invalid submission correlation");
        jdbc.sql("UPDATE notification.generic_submissions SET status=:status,correlation_id=:correlation,provider_request_id=:provider,reason=:reason,updated_at=now() WHERE event_id=:event AND school_id=:school AND request_sha256=:hash AND status='SUBMITTING'")
            .param("status",status).param("correlation",correlation).param("provider",providerId).param("reason",reason)
            .param("event",event).param("school",school).param("hash",requestHash).update();
    }
}

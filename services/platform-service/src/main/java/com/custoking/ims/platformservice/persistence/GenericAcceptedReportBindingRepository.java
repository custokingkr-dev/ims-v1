package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.OffsetDateTime;
import java.util.Optional;

/** Dormant server-side lookup. No component or public callback admission. */
public final class GenericAcceptedReportBindingRepository {
    private final JdbcClient jdbc;
    public GenericAcceptedReportBindingRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public record Binding(String requestSha256,String correlationId,String providerRequestId,
            OffsetDateTime submittedAt,String payload) {
        @Override public String toString(){return "AcceptedReportBinding[REDACTED]";}
    }
    public Optional<Binding> find(long school,String event) {
        if(school<=0 || event==null || !event.matches("[A-Za-z0-9._:-]{1,120}"))
            throw new IllegalArgumentException("Invalid report lookup scope");
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Report binding requires transaction");
        jdbc.sql("SELECT set_config('app.bypass_rls','off',true)").query(String.class).single();
        jdbc.sql("SELECT set_config('app.current_school_id',:school,true)").param("school",Long.toString(school)).query(String.class).single();
        jdbc.sql("SELECT set_config('statement_timeout','5000',true)").query(String.class).single();
        jdbc.sql("SELECT set_config('lock_timeout','2000',true)").query(String.class).single();
        return jdbc.sql("""
            SELECT s.request_sha256,s.correlation_id,s.provider_request_id,s.submitted_at,i.payload
            FROM notification.generic_submissions s JOIN notification.notification_inbox_events i ON i.event_id=s.event_id
            WHERE s.event_id=:event AND s.school_id=:school AND s.status='ACCEPTED'
              AND s.provider_request_id IS NOT NULL AND i.status='ACCEPTED'
              AND octet_length(i.payload)<=65536
            FOR UPDATE OF i
            """).param("event",event).param("school",school).query((rs,n)->new Binding(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getObject(4,OffsetDateTime.class),rs.getString(5))).optional();
    }
}

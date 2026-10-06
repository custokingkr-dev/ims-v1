package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.security.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.MDC;
import java.util.UUID;

/** Only trusted command paths call this inside their mutation transaction. */
public final class TrustedCommandAudit {
    private TrustedCommandAudit() { }
    public static void record(JdbcClient jdbc,String action,String entityId,Long actor,Long school) {
        String request=MDC.get("requestId");
        if(request==null || !request.matches("[A-Za-z0-9._:-]{1,64}")) request=UUID.randomUUID().toString();
        Long authenticated=TenantContext.get().userId();
        jdbc.sql("""
            INSERT INTO reporting.trusted_command_audit(id,action,entity_id,actor_user_id,school_id,request_id,authority,outcome)
            VALUES (:id,:action,:entity,:actor,:school,:request,'SERVER','SUCCESS')
            """).param("id",UUID.randomUUID()).param("action",action).param("entity",entityId)
                .param("actor",authenticated==null?actor:authenticated).param("school",school).param("request",request).update();
    }
}

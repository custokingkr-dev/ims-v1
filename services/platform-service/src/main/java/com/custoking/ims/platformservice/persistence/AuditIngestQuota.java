package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class AuditIngestQuota {
    private final JdbcClient jdbc;
    public AuditIngestQuota(JdbcClient jdbc) { this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean allow(long userId,Long schoolId) {
        return consume("audit:user:"+userId,60) && consume("audit:school:"+schoolId,2000);
    }
    private boolean consume(String key,int limit) {
        return jdbc.sql("""
            INSERT INTO audit.ingest_quotas(quota_key,used,expires_at)
            VALUES (:key,1,clock_timestamp()+interval '1 minute')
            ON CONFLICT(quota_key) DO UPDATE SET
              used=CASE WHEN ingest_quotas.expires_at<=clock_timestamp() THEN 1 ELSE ingest_quotas.used+1 END,
              expires_at=CASE WHEN ingest_quotas.expires_at<=clock_timestamp() THEN clock_timestamp()+interval '1 minute' ELSE ingest_quotas.expires_at END
            WHERE ingest_quotas.expires_at<=clock_timestamp() OR ingest_quotas.used<:limit RETURNING used
            """).param("key",key).param("limit",limit).query(Integer.class).optional().isPresent();
    }
}

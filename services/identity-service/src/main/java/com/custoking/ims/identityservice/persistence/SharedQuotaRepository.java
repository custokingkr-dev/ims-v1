package com.custoking.ims.identityservice.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.atomic.AtomicInteger;

@Repository
public class SharedQuotaRepository {
    private final JdbcTemplate jdbc;
    private final AtomicInteger cleanupCounter = new AtomicInteger();

    public SharedQuotaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Commit attempts even when the enclosing login/reset operation fails. Atomic UPSERT serializes
    // contenders across replicas; PostgreSQL time avoids application clock skew.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(String key, int limit, int seconds) {
        if (limit < 1 || seconds < 1) throw new IllegalArgumentException("Quota limits must be positive");
        var rows = jdbc.queryForList("""
                INSERT INTO identity.request_quotas (quota_key, used, expires_at)
                VALUES (?, 1, clock_timestamp() + (? * interval '1 second'))
                ON CONFLICT (quota_key) DO UPDATE SET
                    used = CASE WHEN request_quotas.expires_at <= clock_timestamp() THEN 1 ELSE request_quotas.used + 1 END,
                    expires_at = CASE WHEN request_quotas.expires_at <= clock_timestamp()
                        THEN clock_timestamp() + (? * interval '1 second') ELSE request_quotas.expires_at END
                WHERE request_quotas.expires_at <= clock_timestamp() OR request_quotas.used < ?
                RETURNING used
                """, key, seconds, seconds, limit);
        if ((cleanupCounter.incrementAndGet() & 127) == 0) {
            jdbc.update("""
                    DELETE FROM identity.request_quotas WHERE quota_key IN
                    (SELECT quota_key FROM identity.request_quotas WHERE expires_at < now() - interval '1 day'
                     ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)
                    """);
            jdbc.update("""
                    DELETE FROM identity.password_reset_tokens WHERE token_hash IN
                    (SELECT token_hash FROM identity.password_reset_tokens WHERE expires_at < now() - interval '1 day'
                     ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)
                    """);
            jdbc.update("""
                    DELETE FROM identity.password_reset_deliveries WHERE id IN
                    (SELECT id FROM identity.password_reset_deliveries WHERE created_at < now() - interval '7 days'
                     ORDER BY created_at LIMIT 1000 FOR UPDATE SKIP LOCKED)
                    """);
        }
        return !rows.isEmpty();
    }
}

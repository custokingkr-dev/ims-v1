package com.custoking.ims.identityservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Records refresh-token reuse (a theft signal) into the identity audit log. */
@Repository
public class AuthAuditRepository {

    private final JdbcClient jdbc;

    public AuthAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void recordRefreshTokenReuse(Long userId, String email, String familyId) {
        // correlation_id (VARCHAR 100) stores the family_id so the audit row is
        // self-describing and does not misuse the permission_codes text field.
        jdbc.sql("""
                INSERT INTO identity.rbac_audit_log
                    (event_type, actor_user_id, actor_email, correlation_id, created_at)
                VALUES
                    ('REFRESH_TOKEN_REUSE_DETECTED', :userId, :email, :familyId, now())
                """)
            .param("userId", userId)
            .param("email", email)
            .param("familyId", familyId)
            .update();
    }
}

package com.custoking.ims.identityservice.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@Repository
public class PasswordResetRepository {
    private final JdbcTemplate jdbc;
    public PasswordResetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void enqueue(String email) {
        jdbc.update("""
                INSERT INTO identity.password_reset_deliveries (id, user_id, credential_version)
                SELECT ?, id, credential_version FROM identity.app_users WHERE lower(email) = lower(?) AND deleted_at IS NULL
                """, java.util.UUID.randomUUID(), email.trim());
    }

    @Transactional
    public Optional<Delivery> claim() {
        jdbc.update("""
                UPDATE identity.password_reset_deliveries SET status = 'FAILED'
                WHERE status = 'PENDING' AND (created_at < now() - interval '30 minutes' OR attempts >= 3)
                  AND (lease_until IS NULL OR lease_until < now())
                """);
        return jdbc.query("""
                UPDATE identity.password_reset_deliveries d SET attempts = attempts + 1, lease_until = now() + interval '2 minutes'
                WHERE d.id = (SELECT id FROM identity.password_reset_deliveries
                  WHERE status = 'PENDING' AND next_attempt_at <= now() AND (lease_until IS NULL OR lease_until < now())
                  ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING d.id, d.user_id, d.credential_version, d.attempts
                """, (rs, row) -> new Delivery(rs.getObject(1, java.util.UUID.class), rs.getLong(2), rs.getLong(3), rs.getInt(4)))
                .stream().findFirst();
    }

    @Transactional
    public Optional<String> issueForDelivery(Delivery delivery, String token) {
        // The monotonically increasing claim attempt fences workers that resume after
        // another replica has reclaimed their expired lease. Validate the intent again
        // here: a suspended worker must not mint a fresh token from an expired request.
        return jdbc.query("""
                WITH eligible AS (
                    SELECT u.email, u.id, u.credential_version
                    FROM identity.password_reset_deliveries d JOIN identity.app_users u ON u.id = d.user_id
                    WHERE d.id = ? AND d.attempts = ? AND d.user_id = ? AND d.credential_version = ?
                      AND d.status = 'PENDING' AND d.lease_until > clock_timestamp()
                      AND d.created_at > clock_timestamp() - interval '30 minutes'
                      AND u.credential_version = d.credential_version AND u.deleted_at IS NULL
                    FOR UPDATE OF d
                )
                INSERT INTO identity.password_reset_tokens (token_hash, user_id, credential_version, expires_at)
                SELECT ?, id, credential_version, clock_timestamp() + interval '30 minutes' FROM eligible
                RETURNING (SELECT email FROM eligible)
                """, (rs, row) -> rs.getString(1), delivery.id(), delivery.attempt(), delivery.userId(), delivery.version(), digest(token))
                .stream().findFirst();
    }

    public void finishDelivery(Delivery delivery, boolean success) {
        jdbc.update("""
                UPDATE identity.password_reset_deliveries SET status = CASE WHEN ? THEN 'SENT' WHEN attempts >= 3 THEN 'FAILED' ELSE 'PENDING' END,
                  lease_until = NULL, next_attempt_at = now() + interval '1 minute' * attempts
                WHERE id = ? AND attempts = ? AND status = 'PENDING' AND lease_until > clock_timestamp()
                  AND created_at > clock_timestamp() - interval '30 minutes'
                """, success, delivery.id(), delivery.attempt());
    }
    public record Delivery(java.util.UUID id, long userId, long version, int attempt) {}

    @Transactional
    public Optional<String> issue(String email, String token) {
        var users = jdbc.query("""
                SELECT id, email, credential_version FROM identity.app_users
                WHERE lower(email) = lower(?) AND deleted_at IS NULL
                """, (rs, row) -> new ResetUser(rs.getLong("id"), rs.getString("email"), rs.getLong("credential_version")), email.trim());
        if (users.size() != 1) return Optional.empty();
        var user = users.getFirst();
        jdbc.update("""
                INSERT INTO identity.password_reset_tokens (token_hash, user_id, credential_version, expires_at)
                VALUES (?, ?, ?, now() + interval '30 minutes')
                """, digest(token), user.id(), user.version());
        return Optional.of(user.email());
    }

    @Transactional
    public boolean confirm(String token, String password, PasswordEncoder encoder) {
        var users = jdbc.query("""
                SELECT u.id FROM identity.password_reset_tokens t JOIN identity.app_users u ON u.id = t.user_id
                WHERE t.token_hash = ? AND t.consumed_at IS NULL AND t.expires_at > clock_timestamp()
                  AND u.deleted_at IS NULL AND u.credential_version = t.credential_version
                FOR UPDATE OF u, t
                """, (rs, row) -> rs.getLong(1), digest(token));
        if (users.isEmpty()) return false;
        long userId = users.getFirst();
        // A version fence also invalidates a login/refresh that began before this transaction
        // and happens to issue its old-version session after this transaction commits.
        jdbc.update("UPDATE identity.app_users SET password_hash = ?, credential_version = credential_version + 1 WHERE id = ?",
                encoder.encode(password), userId);
        jdbc.update("UPDATE identity.password_reset_tokens SET consumed_at = now() WHERE token_hash = ?", digest(token));
        jdbc.update("""
                INSERT INTO identity.rbac_audit_log (event_type, target_user_id, created_at)
                VALUES ('PASSWORD_RESET_COMPLETED', ?, now())
                """, userId);
        return true;
    }

    private static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private record ResetUser(long id, String email, long version) {}
}

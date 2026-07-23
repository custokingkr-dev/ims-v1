package com.custoking.ims.identityservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

@Repository
public class UserDirectoryReadRepository {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    public UserDirectoryReadRepository(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public List<UserDirectoryRow> users(String role, Long branchId, Long zoneId, Boolean active, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, full_name, email, role, branch_id, branch_name, zone_id, zone_name,
                       created_at, deleted_at, deleted_by,
                       (deleted_at IS NULL) AS active
                FROM identity.app_users
                WHERE 1 = 1
                """);
        if (role != null && !role.isBlank()) sql.append(" AND UPPER(role) = UPPER(:role)");
        if (branchId != null) sql.append(" AND branch_id = :branchId");
        if (zoneId != null) sql.append(" AND zone_id = :zoneId");
        if (active != null) {
            sql.append(active ? " AND deleted_at IS NULL" : " AND deleted_at IS NOT NULL");
        }
        sql.append(" ORDER BY full_name, email LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (role != null && !role.isBlank()) spec = spec.param("role", role);
        if (branchId != null) spec = spec.param("branchId", branchId);
        if (zoneId != null) spec = spec.param("zoneId", zoneId);
        return spec.query(UserDirectoryRow.class).list();
    }

    public UserDirectoryRow user(Long id) {
        return jdbc.sql("""
                        SELECT id, full_name, email, role, branch_id, branch_name, zone_id, zone_name,
                               created_at, deleted_at, deleted_by,
                               (deleted_at IS NULL) AS active
                        FROM identity.app_users
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(UserDirectoryRow.class)
                .optional()
                .orElse(null);
    }

    @Transactional
    public UserDirectoryRow updateProfile(Long userId, String fullName, String email, Long actorId, String actorEmail) {
        UserDirectoryRow before = user(userId);
        if (before == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        String normalizedName = blankToNull(fullName);
        String normalizedEmail = blankToNull(email);
        if (normalizedName == null && normalizedEmail == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fullName or email is required");
        }
        if (normalizedEmail != null) {
            normalizedEmail = normalizedEmail.toLowerCase(Locale.ROOT);
        }
        try {
            jdbc.sql("""
                            UPDATE identity.app_users
                            SET full_name = COALESCE(:fullName, full_name),
                                email = COALESCE(:email, email)
                            WHERE id = :userId
                            """)
                    .param("fullName", normalizedName)
                    .param("email", normalizedEmail)
                    .param("userId", userId)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email is already used by an active account", ex);
        }
        UserDirectoryRow after = user(userId);
        if (normalizedEmail != null && !normalizedEmail.equalsIgnoreCase(before.email())) {
            deleteSessions(userId);
        }
        logAudit("USER_UPDATED", actorId, actorEmail, userId,
                profileAuditValue(before.fullName(), before.email()),
                profileAuditValue(after.fullName(), after.email()));
        return after;
    }

    @Transactional
    public void resetPassword(Long userId, String password, Long actorId, String actorEmail) {
        if (password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password is required");
        }
        requireUser(userId);
        jdbc.sql("""
                        UPDATE identity.app_users
                        SET password_hash = :passwordHash
                        WHERE id = :userId
                        """)
                .param("passwordHash", passwordEncoder.encode(password))
                .param("userId", userId)
                .update();
        deleteSessions(userId);
        logAudit("PASSWORD_RESET", actorId, actorEmail, userId, null, null);
    }

    @Transactional
    public void disableUser(Long userId, Long actorId, String actorEmail) {
        requireUser(userId);
        int updated = jdbc.sql("""
                        UPDATE identity.app_users
                        SET deleted_at = COALESCE(deleted_at, now()),
                            deleted_by = COALESCE(deleted_by, :actorEmail)
                        WHERE id = :userId
                        """)
                .param("actorEmail", actorEmail == null || actorEmail.isBlank() ? "system" : actorEmail)
                .param("userId", userId)
                .update();
        if (updated > 0) {
            deleteSessions(userId);
            logAudit("USER_DISABLED", actorId, actorEmail, userId, null, null);
        }
    }

    @Transactional
    public void enableUser(Long userId, Long actorId, String actorEmail) {
        requireUser(userId);
        int updated = jdbc.sql("""
                        UPDATE identity.app_users
                        SET deleted_at = NULL,
                            deleted_by = NULL
                        WHERE id = :userId
                        """)
                .param("userId", userId)
                .update();
        if (updated > 0) {
            logAudit("USER_ENABLED", actorId, actorEmail, userId, null, null);
        }
    }

    private void requireUser(Long userId) {
        boolean exists = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM identity.app_users WHERE id = :userId)
                        """)
                .param("userId", userId)
                .query(Boolean.class)
                .single());
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
    }

    private void deleteSessions(Long userId) {
        jdbc.sql("DELETE FROM identity.auth_sessions WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String profileAuditValue(String fullName, String email) {
        return "{\"fullName\":\"" + auditEscape(fullName) + "\",\"email\":\"" + auditEscape(email) + "\"}";
    }

    private String auditEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void logAudit(String eventType, Long actorId, String actorEmail, Long targetUserId,
                          String oldValue, String newValue) {
        jdbc.sql("""
                        INSERT INTO identity.rbac_audit_log
                            (event_type, actor_user_id, actor_email, target_user_id, old_value, new_value, created_at)
                        VALUES
                            (:eventType, :actorId, :actorEmail, :targetUserId, :oldValue, :newValue, now())
                        """)
                .param("eventType", eventType)
                .param("actorId", actorId)
                .param("actorEmail", actorEmail)
                .param("targetUserId", targetUserId)
                .param("oldValue", oldValue)
                .param("newValue", newValue)
                .update();
    }

    public record UserDirectoryRow(
            Long id,
            String fullName,
            String email,
            String role,
            Long branchId,
            String branchName,
            Long zoneId,
            String zoneName,
            OffsetDateTime createdAt,
            OffsetDateTime deletedAt,
            String deletedBy,
            Boolean active) {}
}

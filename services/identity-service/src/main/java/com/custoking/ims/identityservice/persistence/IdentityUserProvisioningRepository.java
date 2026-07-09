package com.custoking.ims.identityservice.persistence;

import com.custoking.ims.identityservice.infrastructure.TenantSchoolClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class IdentityUserProvisioningRepository {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TenantSchoolClient tenantSchool;

    public IdentityUserProvisioningRepository(JdbcClient jdbc, PasswordEncoder passwordEncoder, TenantSchoolClient tenantSchool) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.tenantSchool = tenantSchool;
    }

    @Transactional
    public Map<String, Object> provisionSchoolUser(Long schoolId, String role, Map<String, Object> body) {
        String normalizedRole = normalizeSchoolRole(role);
        String fullName = requiredString(body, "fullName");
        String email = requiredString(body, "email").toLowerCase(Locale.ROOT);
        String temporaryPassword = requiredString(body, "temporaryPassword");
        Long assignedBy = optionalLong(body.get("assignedBy"));
        String branchName = tenantSchool.school(schoolId).name();

        ExistingUser existing = activeUserByEmail(email);
        Long userId;
        if (existing != null) {
            if (!"OPERATIONS".equals(normalizedRole) || !"OPERATIONS".equalsIgnoreCase(existing.role())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "email is already used by an active account");
            }
            userId = existing.id();
        } else {
            userId = createSchoolUser(fullName, email, temporaryPassword, normalizedRole, schoolId, branchName);
        }
        assignScopedRole(userId, normalizedRole, schoolId, null, assignedBy);

        return Map.of(
                "userId", userId,
                "fullName", fullName,
                "email", email,
                "branchId", schoolId,
                "branchName", branchName
        );
    }

    @Transactional
    public Map<String, Object> provisionZoneAdmin(Long zoneId, Map<String, Object> body) {
        String fullName = requiredString(body, "fullName");
        String email = requiredString(body, "email").toLowerCase(Locale.ROOT);
        String temporaryPassword = requiredString(body, "temporaryPassword");
        Long assignedBy = optionalLong(body.get("assignedBy"));
        String zoneName = tenantSchool.zone(zoneId).name();

        retireZoneAdmins(zoneId, assignedBy);
        Long userId = createZoneUser(fullName, email, temporaryPassword, zoneId, zoneName);
        assignScopedRole(userId, "ZONE_ADMIN", null, zoneId, assignedBy);
        tenantSchool.assignZoneAdmin(zoneId, userId, assignedBy);

        return Map.of(
                "userId", userId,
                "fullName", fullName,
                "email", email,
                "zoneId", zoneId,
                "zoneName", zoneName
        );
    }

    private void retireZoneAdmins(Long zoneId, Long assignedBy) {
        List<Long> userIds = jdbc.sql("""
                        SELECT id
                        FROM identity.app_users
                        WHERE zone_id = :zoneId
                          AND UPPER(role) = 'ZONE_ADMIN'
                          AND deleted_at IS NULL
                        """)
                .param("zoneId", zoneId)
                .query(Long.class)
                .list();
        if (!userIds.isEmpty()) {
            tenantSchool.retireZoneAdmins(zoneId, userIds);
        }
        retireUsers(userIds, assignedBy);
    }

    private void retireUsers(List<Long> userIds, Long assignedBy) {
        for (Long userId : userIds) {
            jdbc.sql("DELETE FROM identity.auth_sessions WHERE user_id = :userId")
                    .param("userId", userId)
                    .update();
            jdbc.sql("""
                            UPDATE identity.user_role_assignments
                            SET active = false, revoked_at = now(), revoked_by = :assignedBy
                            WHERE user_id = :userId
                              AND active = true
                            """)
                    .param("userId", userId)
                    .param("assignedBy", assignedBy)
                    .update();
            jdbc.sql("""
                            UPDATE identity.app_users
                            SET deleted_at = now(),
                                deleted_by = 'identity-service',
                                email = CONCAT('deleted+', id, '+', email)
                            WHERE id = :userId
                              AND deleted_at IS NULL
                            """)
                    .param("userId", userId)
                    .update();
        }
    }

    private Long createSchoolUser(String fullName, String email, String temporaryPassword,
                                  String role, Long schoolId, String schoolName) {
        try {
            return jdbc.sql("""
                            INSERT INTO identity.app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
                            VALUES (:fullName, :email, :passwordHash, :role, :schoolId, :schoolName, now())
                            RETURNING id
                            """)
                    .param("fullName", fullName)
                    .param("email", email)
                    .param("passwordHash", passwordEncoder.encode(temporaryPassword))
                    .param("role", role)
                    .param("schoolId", schoolId)
                    .param("schoolName", schoolName)
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email is already used by an active account", ex);
        }
    }

    private Long createZoneUser(String fullName, String email, String temporaryPassword,
                                Long zoneId, String zoneName) {
        return jdbc.sql("""
                        INSERT INTO identity.app_users (full_name, email, password_hash, role, zone_id, zone_name, created_at)
                        VALUES (:fullName, :email, :passwordHash, 'ZONE_ADMIN', :zoneId, :zoneName, now())
                        RETURNING id
                        """)
                .param("fullName", fullName)
                .param("email", email)
                .param("passwordHash", passwordEncoder.encode(temporaryPassword))
                .param("zoneId", zoneId)
                .param("zoneName", zoneName)
                .query(Long.class)
                .single();
    }

    private void assignScopedRole(Long userId, String roleName, Long schoolId, Long zoneId, Long assignedBy) {
        Long roleId = jdbc.sql("SELECT id FROM identity.roles WHERE UPPER(name) = :roleName")
                .param("roleName", roleName)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "role not found"));
        if (activeAssignmentExists(userId, roleId, schoolId, zoneId)) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO identity.user_role_assignments
                            (user_id, role_id, school_id, zone_id, assigned_at, assigned_by, active, valid_from)
                        VALUES
                            (:userId, :roleId, :schoolId, :zoneId, now(), :assignedBy, true, now())
                        """)
                .param("userId", userId)
                .param("roleId", roleId)
                .param("schoolId", schoolId)
                .param("zoneId", zoneId)
                .param("assignedBy", assignedBy)
                .update();
    }

    private boolean activeAssignmentExists(Long userId, Long roleId, Long schoolId, Long zoneId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM identity.user_role_assignments
                            WHERE user_id = :userId
                              AND role_id = :roleId
                              AND active = true
                              AND revoked_at IS NULL
                              AND school_id IS NOT DISTINCT FROM :schoolId
                              AND zone_id IS NOT DISTINCT FROM :zoneId
                              AND (valid_from IS NULL OR valid_from <= now())
                              AND (valid_until IS NULL OR valid_until > now())
                        )
                        """)
                .param("userId", userId)
                .param("roleId", roleId)
                .param("schoolId", schoolId)
                .param("zoneId", zoneId)
                .query(Boolean.class)
                .single());
    }

    private ExistingUser activeUserByEmail(String email) {
        return jdbc.sql("""
                        SELECT id, role
                        FROM identity.app_users
                        WHERE lower(email) = :email
                          AND deleted_at IS NULL
                        ORDER BY id
                        LIMIT 1
                        """)
                .param("email", email.toLowerCase(Locale.ROOT))
                .query(ExistingUser.class)
                .optional()
                .orElse(null);
    }

    private String normalizeSchoolRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("ADMIN") && !normalized.equals("OPERATIONS")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported school role");
        }
        return normalized;
    }

    private String requiredString(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.toString().trim();
    }

    private Long optionalLong(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private record ExistingUser(Long id, String role) {}
}

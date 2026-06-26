package com.custoking.ims.identityservice.persistence;

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

    public IdentityUserProvisioningRepository(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Map<String, Object> provisionSchoolUser(Long schoolId, String role, Map<String, Object> body) {
        String normalizedRole = normalizeSchoolRole(role);
        String fullName = requiredString(body, "fullName");
        String email = requiredString(body, "email").toLowerCase(Locale.ROOT);
        String temporaryPassword = requiredString(body, "temporaryPassword");
        Long assignedBy = optionalLong(body.get("assignedBy"));
        String branchName = jdbc.sql("SELECT name FROM tenant_school.schools WHERE id = :schoolId")
                .param("schoolId", schoolId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "school not found"));

        retireSchoolUsers(schoolId, normalizedRole, assignedBy);
        Long userId = createSchoolUser(fullName, email, temporaryPassword, normalizedRole, schoolId, branchName);
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
        String zoneName = jdbc.sql("SELECT name FROM tenant_school.zones WHERE id = :zoneId")
                .param("zoneId", zoneId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "zone not found"));

        retireZoneAdmins(zoneId, assignedBy);
        Long userId = createZoneUser(fullName, email, temporaryPassword, zoneId, zoneName);
        assignScopedRole(userId, "ZONE_ADMIN", null, zoneId, assignedBy);
        jdbc.sql("""
                        INSERT INTO tenant_school.zone_admin_assignments (zone_id, user_id, assigned_at, assigned_by, active)
                        VALUES (:zoneId, :userId, now(), :assignedBy, true)
                        """)
                .param("zoneId", zoneId)
                .param("userId", userId)
                .param("assignedBy", assignedBy)
                .update();

        return Map.of(
                "userId", userId,
                "fullName", fullName,
                "email", email,
                "zoneId", zoneId,
                "zoneName", zoneName
        );
    }

    private void retireSchoolUsers(Long schoolId, String role, Long assignedBy) {
        List<Long> userIds = jdbc.sql("""
                        SELECT id
                        FROM identity.app_users
                        WHERE branch_id = :schoolId
                          AND UPPER(role) = :role
                          AND deleted_at IS NULL
                        """)
                .param("schoolId", schoolId)
                .param("role", role)
                .query(Long.class)
                .list();
        retireUsers(userIds, assignedBy);
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
            jdbc.sql("""
                            UPDATE tenant_school.zone_admin_assignments
                            SET active = false
                            WHERE zone_id = :zoneId
                              AND user_id IN (:userIds)
                            """)
                    .param("zoneId", zoneId)
                    .param("userIds", userIds)
                    .update();
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
}

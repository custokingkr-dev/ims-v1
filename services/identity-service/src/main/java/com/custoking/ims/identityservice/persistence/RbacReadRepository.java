package com.custoking.ims.identityservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class RbacReadRepository {

    private final JdbcClient jdbc;

    public RbacReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<RoleRow> roles() {
        return jdbc.sql("""
                SELECT id, name, description, created_at
                FROM identity.roles
                ORDER BY name
                """).query(RoleRow.class).list();
    }

    public List<PermissionRow> permissions() {
        return jdbc.sql("""
                SELECT id, code, description, created_at
                FROM identity.permissions
                ORDER BY code
                """).query(PermissionRow.class).list();
    }

    public List<RolePermissionRow> rolePermissions(Long roleId) {
        StringBuilder sql = new StringBuilder("""
                SELECT rp.role_id, r.name AS role_name, rp.permission_id, p.code AS permission_code
                FROM identity.role_permissions rp
                JOIN identity.roles r ON r.id = rp.role_id
                JOIN identity.permissions p ON p.id = rp.permission_id
                WHERE 1=1
                """);
        if (roleId != null) sql.append(" AND rp.role_id = :roleId");
        sql.append(" ORDER BY r.name, p.code");
        var spec = jdbc.sql(sql.toString());
        if (roleId != null) spec = spec.param("roleId", roleId);
        return spec.query(RolePermissionRow.class).list();
    }

    public List<UserRoleAssignmentRow> userAssignments(Long userId, Boolean active, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT ura.id, ura.user_id, ura.role_id, r.name AS role_name, ura.assigned_at,
                       ura.assigned_by, ura.revoked_by, ura.revoked_at, ura.school_id, ura.zone_id,
                       ura.active, ura.valid_from, ura.valid_until
                FROM identity.user_role_assignments ura
                JOIN identity.roles r ON r.id = ura.role_id
                WHERE 1=1
                """);
        if (userId != null) sql.append(" AND ura.user_id = :userId");
        if (active != null) sql.append(" AND ura.active = :active");
        sql.append(" ORDER BY ura.assigned_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (userId != null) spec = spec.param("userId", userId);
        if (active != null) spec = spec.param("active", active);
        return spec.query(UserRoleAssignmentRow.class).list();
    }

    public List<String> effectivePermissions(Long userId, Long schoolId, Long zoneId) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT p.code
                FROM identity.user_role_assignments ura
                JOIN identity.role_permissions rp ON rp.role_id = ura.role_id
                JOIN identity.permissions p ON p.id = rp.permission_id
                WHERE ura.user_id = :userId
                  AND ura.active = true
                  AND ura.revoked_at IS NULL
                  AND (ura.valid_from IS NULL OR ura.valid_from <= now())
                  AND (ura.valid_until IS NULL OR ura.valid_until >= now())
                """);
        if (schoolId != null || zoneId != null) {
            sql.append("""
                  AND (
                        (ura.school_id IS NULL AND ura.zone_id IS NULL)
                """);
            if (schoolId != null) sql.append(" OR ura.school_id = :schoolId");
            if (zoneId != null) sql.append(" OR ura.zone_id = :zoneId");
            sql.append(")");
        }
        sql.append(" ORDER BY p.code");
        var spec = jdbc.sql(sql.toString()).param("userId", userId);
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (zoneId != null) spec = spec.param("zoneId", zoneId);
        return spec.query(String.class).list();
    }

    public List<Long> operatorSchoolIds(Long userId) {
        return jdbc.sql("""
                SELECT DISTINCT ura.school_id
                FROM identity.user_role_assignments ura
                JOIN identity.roles r ON r.id = ura.role_id
                WHERE ura.user_id = :userId
                  AND ura.active = true
                  AND ura.revoked_at IS NULL
                  AND ura.school_id IS NOT NULL
                  AND (ura.valid_from IS NULL OR ura.valid_from <= now())
                  AND (ura.valid_until IS NULL OR ura.valid_until >= now())
                  AND UPPER(r.name) = 'OPERATIONS'
                ORDER BY ura.school_id
                """)
                .param("userId", userId)
                .query(Long.class)
                .list();
    }

    public List<RbacAuditRow> audit(Long actorUserId, Long targetUserId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_type, actor_user_id, actor_email, target_user_id, role_id,
                       role_name, permission_codes, school_id, zone_id, old_value, new_value,
                       ip_address, user_agent, correlation_id, created_at
                FROM identity.rbac_audit_log
                WHERE 1=1
                """);
        if (actorUserId != null) sql.append(" AND actor_user_id = :actorUserId");
        if (targetUserId != null) sql.append(" AND target_user_id = :targetUserId");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (actorUserId != null) spec = spec.param("actorUserId", actorUserId);
        if (targetUserId != null) spec = spec.param("targetUserId", targetUserId);
        return spec.query(RbacAuditRow.class).list();
    }

    public record RoleRow(Long id, String name, String description, OffsetDateTime createdAt) {}

    public record PermissionRow(Long id, String code, String description, OffsetDateTime createdAt) {}

    public record RolePermissionRow(Long roleId, String roleName, Long permissionId, String permissionCode) {}

    public record UserRoleAssignmentRow(Long id, Long userId, Long roleId, String roleName,
                                        OffsetDateTime assignedAt, Long assignedBy, Long revokedBy,
                                        OffsetDateTime revokedAt, Long schoolId, Long zoneId,
                                        Boolean active, OffsetDateTime validFrom,
                                        OffsetDateTime validUntil) {}

    public record RbacAuditRow(Long id, String eventType, Long actorUserId, String actorEmail,
                               Long targetUserId, Long roleId, String roleName, String permissionCodes,
                               Long schoolId, Long zoneId, String oldValue, String newValue,
                               String ipAddress, String userAgent, String correlationId,
                               OffsetDateTime createdAt) {}
}

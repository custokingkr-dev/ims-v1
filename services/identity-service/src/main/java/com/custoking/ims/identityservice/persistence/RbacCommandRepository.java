package com.custoking.ims.identityservice.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class RbacCommandRepository {

    private final JdbcClient jdbc;

    public RbacCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> createRole(Map<String, Object> body) {
        String name = requiredString(body, "name").toUpperCase(Locale.ROOT);
        String description = optionalString(body.get("description"));
        Long actorId = optionalLong(body.get("actorId"));
        if (roleNameExists(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "role already exists");
        }
        Long roleId = jdbc.sql("""
                        INSERT INTO identity.roles (name, description, created_at)
                        VALUES (:name, :description, now())
                        RETURNING id
                        """)
                .param("name", name)
                .param("description", description)
                .query(Long.class)
                .single();
        replaceRolePermissions(roleId, permissionCodes(body));
        logAudit("ROLE_CREATED", actorId, null, roleId, name, null, null);
        return roleView(roleId);
    }

    @Transactional
    public Map<String, Object> updateRole(Long roleId, Map<String, Object> body) {
        RoleSnapshot role = roleSnapshot(roleId);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "role not found");
        }
        Long actorId = optionalLong(body == null ? null : body.get("actorId"));
        if (body != null && body.containsKey("description")) {
            jdbc.sql("UPDATE identity.roles SET description = :description WHERE id = :roleId")
                    .param("roleId", roleId)
                    .param("description", optionalString(body.get("description")))
                    .update();
        }
        if (body != null && body.containsKey("permissions")) {
            replaceRolePermissions(roleId, permissionCodes(body));
        }
        logAudit("ROLE_UPDATED", actorId, null, roleId, role.name(), null, null);
        return roleView(roleId);
    }

    @Transactional
    public Map<String, Object> assignPlatformRole(Long userId, Map<String, Object> body) {
        return assignScopedRole(userId, requiredString(body, "role"), null, null, optionalLong(body.get("assignedBy")));
    }

    @Transactional
    public Map<String, Object> assignSchoolRole(Long userId, Map<String, Object> body) {
        return assignScopedRole(
                userId,
                requiredString(body, "role"),
                requiredLong(body, "schoolId"),
                null,
                optionalLong(body.get("assignedBy")));
    }

    @Transactional
    public Map<String, Object> assignZoneRole(Long userId, Map<String, Object> body) {
        return assignScopedRole(
                userId,
                requiredString(body, "role"),
                null,
                requiredLong(body, "zoneId"),
                optionalLong(body.get("assignedBy")));
    }

    @Transactional
    public void revokeAssignment(Long userId, Long assignmentId, Map<String, Object> body) {
        Long actorId = optionalLong(body == null ? null : body.get("revokedBy"));
        Map<String, Object> assignment = assignmentView(assignmentId);
        if (assignment == null || !userId.equals(((Number) assignment.get("userId")).longValue())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "assignment not found");
        }
        jdbc.sql("""
                        UPDATE identity.user_role_assignments
                        SET active = false, revoked_at = now(), revoked_by = :actorId
                        WHERE id = :assignmentId
                        """)
                .param("assignmentId", assignmentId)
                .param("actorId", actorId)
                .update();
        logAudit("ROLE_REVOKED", actorId, userId, ((Number) assignment.get("roleId")).longValue(),
                assignment.get("role").toString(), (Long) assignment.get("schoolId"),
                (Long) assignment.get("zoneId"));
    }

    private Map<String, Object> assignScopedRole(Long userId, String roleCode, Long schoolId, Long zoneId,
                                                 Long assignedBy) {
        if (!userExists(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        String roleName = roleCode.trim().toUpperCase(Locale.ROOT);
        Long roleId = jdbc.sql("SELECT id FROM identity.roles WHERE UPPER(name) = :roleName")
                .param("roleName", roleName)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown role"));
        if (activeAssignmentExists(userId, roleId, schoolId, zoneId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "active assignment already exists for this user/role/scope combination");
        }
        try {
            Long assignmentId = jdbc.sql("""
                            INSERT INTO identity.user_role_assignments
                                (user_id, role_id, school_id, zone_id, assigned_at, assigned_by, active, valid_from)
                            VALUES
                                (:userId, :roleId, :schoolId, :zoneId, now(), :assignedBy, true, now())
                            RETURNING id
                            """)
                    .param("userId", userId)
                    .param("roleId", roleId)
                    .param("schoolId", schoolId)
                    .param("zoneId", zoneId)
                    .param("assignedBy", assignedBy)
                    .query(Long.class)
                    .single();
            logAudit("ROLE_ASSIGNED", assignedBy, userId, roleId, roleName, schoolId, zoneId);
            return assignmentView(assignmentId);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "assignment already exists for this user/role/scope combination");
        }
    }

    @Transactional
    public Map<String, Object> syncOperatorSchools(Long userId, List<Long> schoolIds, Long actorId) {
        if (!userExists(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        Long roleId = jdbc.sql("SELECT id FROM identity.roles WHERE UPPER(name) = :roleName")
                .param("roleName", "OPERATIONS")
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown role"));

        java.util.LinkedHashSet<Long> target = new java.util.LinkedHashSet<>();
        if (schoolIds != null) {
            for (Long schoolId : schoolIds) {
                if (schoolId != null) target.add(schoolId);
            }
        }

        List<Long> currentSchoolIds = jdbc.sql("""
                        SELECT school_id
                        FROM identity.user_role_assignments
                        WHERE user_id = :userId
                          AND role_id = :roleId
                          AND active = true
                          AND revoked_at IS NULL
                          AND school_id IS NOT NULL
                        """)
                .param("userId", userId)
                .param("roleId", roleId)
                .query(Long.class)
                .list();
        java.util.LinkedHashSet<Long> current = new java.util.LinkedHashSet<>(currentSchoolIds);

        for (Long schoolId : target) {
            if (!current.contains(schoolId)) {
                assignScopedRole(userId, "OPERATIONS", schoolId, null, actorId);
            }
        }
        for (Long schoolId : current) {
            if (!target.contains(schoolId)) {
                jdbc.sql("""
                                UPDATE identity.user_role_assignments
                                SET active = false, revoked_at = now(), revoked_by = :actorId
                                WHERE user_id = :userId AND role_id = :roleId AND school_id = :schoolId
                                  AND active = true AND revoked_at IS NULL
                                """)
                        .param("userId", userId)
                        .param("roleId", roleId)
                        .param("schoolId", schoolId)
                        .param("actorId", actorId)
                        .update();
                logAudit("ROLE_REVOKED", actorId, userId, roleId, "OPERATIONS", schoolId, null);
            }
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", userId);
        view.put("schoolIds", new java.util.ArrayList<>(target));
        return view;
    }

    private boolean userExists(Long userId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM identity.app_users WHERE id = :userId AND deleted_at IS NULL)")
                .param("userId", userId)
                .query(Boolean.class)
                .single());
    }

    private boolean roleNameExists(String roleName) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM identity.roles WHERE UPPER(name) = :roleName)")
                .param("roleName", roleName)
                .query(Boolean.class)
                .single());
    }

    private RoleSnapshot roleSnapshot(Long roleId) {
        return jdbc.sql("SELECT id, name FROM identity.roles WHERE id = :roleId")
                .param("roleId", roleId)
                .query(RoleSnapshot.class)
                .optional()
                .orElse(null);
    }

    private void replaceRolePermissions(Long roleId, List<String> permissionCodes) {
        jdbc.sql("DELETE FROM identity.role_permissions WHERE role_id = :roleId")
                .param("roleId", roleId)
                .update();
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return;
        }
        List<Long> permissionIds = jdbc.sql("""
                        SELECT id
                        FROM identity.permissions
                        WHERE code IN (:permissionCodes)
                        """)
                .param("permissionCodes", permissionCodes)
                .query(Long.class)
                .list();
        if (permissionIds.size() != permissionCodes.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown permission codes");
        }
        for (Long permissionId : permissionIds) {
            jdbc.sql("""
                            INSERT INTO identity.role_permissions (role_id, permission_id)
                            VALUES (:roleId, :permissionId)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("roleId", roleId)
                    .param("permissionId", permissionId)
                    .update();
        }
    }

    private Map<String, Object> roleView(Long roleId) {
        RoleDetail detail = jdbc.sql("""
                        SELECT id, name, COALESCE(description, '') AS description
                        FROM identity.roles
                        WHERE id = :roleId
                        """)
                .param("roleId", roleId)
                .query(RoleDetail.class)
                .single();
        List<String> permissions = jdbc.sql("""
                        SELECT p.code
                        FROM identity.role_permissions rp
                        JOIN identity.permissions p ON p.id = rp.permission_id
                        WHERE rp.role_id = :roleId
                        ORDER BY p.code
                        """)
                .param("roleId", roleId)
                .query(String.class)
                .list();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", detail.id());
        view.put("name", detail.name());
        view.put("description", detail.description());
        view.put("permissions", permissions);
        return view;
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
                              AND ((:schoolId IS NULL AND school_id IS NULL) OR school_id = :schoolId)
                              AND ((:zoneId IS NULL AND zone_id IS NULL) OR zone_id = :zoneId)
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

    private Map<String, Object> assignmentView(Long assignmentId) {
        return jdbc.sql("""
                        SELECT ura.id, ura.user_id, ura.role_id, r.name AS role, ura.active,
                               (ura.active = true AND ura.revoked_at IS NULL
                                AND (ura.valid_from IS NULL OR ura.valid_from <= now())
                                AND (ura.valid_until IS NULL OR ura.valid_until > now())) AS effective,
                               ura.school_id, ura.zone_id, ura.valid_from, ura.valid_until,
                               ura.assigned_at, ura.assigned_by, ura.revoked_at, ura.revoked_by
                        FROM identity.user_role_assignments ura
                        JOIN identity.roles r ON r.id = ura.role_id
                        WHERE ura.id = :assignmentId
                        """)
                .param("assignmentId", assignmentId)
                .query(this::mapAssignment)
                .optional()
                .orElse(null);
    }

    private Map<String, Object> mapAssignment(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", rs.getLong("id"));
        view.put("userId", rs.getLong("user_id"));
        view.put("roleId", rs.getLong("role_id"));
        view.put("role", rs.getString("role"));
        view.put("active", rs.getBoolean("active"));
        view.put("effective", rs.getBoolean("effective"));
        view.put("schoolId", nullableLong(rs, "school_id"));
        view.put("zoneId", nullableLong(rs, "zone_id"));
        view.put("validFrom", nullableOffsetDateTime(rs, "valid_from"));
        view.put("validUntil", nullableOffsetDateTime(rs, "valid_until"));
        view.put("assignedAt", nullableOffsetDateTime(rs, "assigned_at"));
        view.put("assignedBy", nullableLong(rs, "assigned_by"));
        view.put("revokedAt", nullableOffsetDateTime(rs, "revoked_at"));
        view.put("revokedBy", nullableLong(rs, "revoked_by"));
        return view;
    }

    private void logAudit(String eventType, Long actorId, Long targetUserId, Long roleId, String roleName,
                          Long schoolId, Long zoneId) {
        jdbc.sql("""
                        INSERT INTO identity.rbac_audit_log
                            (event_type, actor_user_id, target_user_id, role_id, role_name, school_id, zone_id, created_at)
                        VALUES
                            (:eventType, :actorId, :targetUserId, :roleId, :roleName, :schoolId, :zoneId, now())
                        """)
                .param("eventType", eventType)
                .param("actorId", actorId)
                .param("targetUserId", targetUserId)
                .param("roleId", roleId)
                .param("roleName", roleName)
                .param("schoolId", schoolId)
                .param("zoneId", zoneId)
                .update();
    }

    private String requiredString(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing required field: " + field);
        }
        return value.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private List<String> permissionCodes(Map<String, Object> body) {
        Object value = body == null ? null : body.get("permissions");
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "permissions must be a list");
    }

    private String optionalString(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private Long requiredLong(Map<String, Object> body, String field) {
        Long value = optionalLong(body == null ? null : body.get(field));
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing required field: " + field);
        }
        return value;
    }

    private Long optionalLong(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(value.toString());
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private OffsetDateTime nullableOffsetDateTime(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return rs.wasNull() ? null : value;
    }

    private record RoleSnapshot(Long id, String name) {}

    private record RoleDetail(Long id, String name, String description) {}
}

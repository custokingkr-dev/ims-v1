package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.api.dto.AssignPlatformRoleRequest;
import com.custoking.ims.identityservice.api.dto.AssignSchoolRoleRequest;
import com.custoking.ims.identityservice.api.dto.AssignZoneRoleRequest;
import com.custoking.ims.identityservice.api.dto.CreateRoleRequest;
import com.custoking.ims.identityservice.api.dto.UpdateRoleRequest;
import com.custoking.ims.identityservice.persistence.RbacReadRepository;
import com.custoking.ims.identityservice.persistence.RbacCommandRepository;
import com.custoking.ims.identityservice.security.TenantContext;
import com.custoking.ims.identityservice.security.TenantScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rbac")
public class RbacReadController {

    private final RbacReadRepository rbac;
    private final RbacCommandRepository commands;
    private final String serviceToken;

    public RbacReadController(
            RbacReadRepository rbac,
            RbacCommandRepository commands,
            @Value("${identity.introspection-token:}") String serviceToken) {
        this.rbac = rbac;
        this.commands = commands;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @GetMapping("/roles")
    public Object roles(@RequestHeader(value = "X-Identity-Service-Token", required = false) String token) {
        requireToken(token, "identity:read");
        return rbac.roles();
    }

    @GetMapping("/permissions")
    public Object permissions(@RequestHeader(value = "X-Identity-Service-Token", required = false) String token) {
        requireToken(token, "identity:read");
        return rbac.permissions();
    }

    @GetMapping("/role-permissions")
    public Object rolePermissions(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @RequestParam(required = false) Long roleId) {
        requireToken(token, "identity:read");
        return rbac.rolePermissions(roleId);
    }

    @GetMapping("/user-role-assignments")
    public Object userAssignments(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "identity:read");
        return rbac.userAssignments(userId, active, limit);
    }

    @GetMapping("/users/{userId}/roles")
    public Object userRoles(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long userId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "identity:read");
        return rbac.userAssignments(userId, active, limit);
    }

    @GetMapping("/users/{userId}/permissions")
    public Object userPermissions(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long userId,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long zoneId) {
        requireToken(token, "identity:read");
        schoolId = TenantScope.resolveSchoolId(schoolId);
        zoneId = resolveZoneId(zoneId);
        return rbac.effectivePermissions(userId, schoolId, zoneId);
    }

    @GetMapping("/audit")
    public Object audit(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) Long targetUserId,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "identity:read");
        return rbac.audit(actorUserId, targetUserId, limit);
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public Object createRole(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @Valid @RequestBody CreateRoleRequest req) {
        requireToken(token, "identity:write");
        Map<String, Object> body = new HashMap<>();
        body.put("name", req.name());
        body.put("description", req.description());
        body.put("permissions", req.permissions());
        body.put("actorId", req.actorId());
        return commands.createRole(body);
    }

    @org.springframework.web.bind.annotation.PutMapping("/roles/{roleId}")
    public Object updateRole(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long roleId,
            @Valid @RequestBody UpdateRoleRequest req) {
        requireToken(token, "identity:write");
        Map<String, Object> body = new HashMap<>();
        body.put("description", req.description());
        body.put("permissions", req.permissions());
        body.put("actorId", req.actorId());
        return commands.updateRole(roleId, body);
    }

    @PostMapping("/users/{userId}/roles/platform")
    public Object assignPlatformRole(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long userId,
            @Valid @RequestBody AssignPlatformRoleRequest req) {
        requireToken(token, "identity:write");
        Map<String, Object> body = new HashMap<>();
        body.put("role", req.role());
        body.put("assignedBy", req.assignedBy());
        return commands.assignPlatformRole(userId, body);
    }

    @PostMapping("/users/{userId}/roles/school")
    public Object assignSchoolRole(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long userId,
            @Valid @RequestBody AssignSchoolRoleRequest req) {
        requireToken(token, "identity:write");
        Map<String, Object> body = new HashMap<>();
        body.put("role", req.role());
        body.put("schoolId", req.schoolId());
        body.put("assignedBy", req.assignedBy());
        return commands.assignSchoolRole(userId, body);
    }

    @PostMapping("/users/{userId}/roles/zone")
    public Object assignZoneRole(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long userId,
            @Valid @RequestBody AssignZoneRoleRequest req) {
        requireToken(token, "identity:write");
        Map<String, Object> body = new HashMap<>();
        body.put("role", req.role());
        body.put("zoneId", req.zoneId());
        body.put("assignedBy", req.assignedBy());
        return commands.assignZoneRole(userId, body);
    }

    @DeleteMapping("/users/{userId}/roles/{assignmentId}")
    public void revokeAssignment(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long userId,
            @PathVariable Long assignmentId,
            @RequestBody(required = false) Map<String, Object> body) {
        requireToken(token, "identity:write");
        commands.revokeAssignment(userId, assignmentId, body);
    }

    private Long resolveZoneId(Long requested) {
        TenantContext ctx = TenantContext.get();
        if (ctx.isSuperAdmin()) return requested;
        if (requested == null) return null;
        if (requested.equals(ctx.zoneId())) return requested;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cross-zone access denied");
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(serviceToken) || !serviceToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid identity service token");
        }
    }
}


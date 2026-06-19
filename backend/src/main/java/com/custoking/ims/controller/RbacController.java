package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.entity.PermissionEntity;
import com.custoking.ims.entity.RoleEntity;
import com.custoking.ims.entity.UserRoleAssignmentEntity;
import com.custoking.ims.repo.PermissionRepository;
import com.custoking.ims.repo.RoleRepository;
import com.custoking.ims.security.AppUserDetails;
import com.custoking.ims.service.RbacAuditService;
import com.custoking.ims.service.RbacService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/rbac")
public class RbacController {

    private final RoleRepository roleRepo;
    private final PermissionRepository permissionRepo;
    private final RbacService rbacService;
    private final RbacAuditService auditService;

    public RbacController(RoleRepository roleRepo,
                          PermissionRepository permissionRepo,
                          RbacService rbacService,
                          RbacAuditService auditService) {
        this.roleRepo = roleRepo;
        this.permissionRepo = permissionRepo;
        this.rbacService = rbacService;
        this.auditService = auditService;
    }

    // ── Roles ─────────────────────────────────────────────────────────────────

    @GetMapping("/roles")
    @PreAuthorize(PermissionConstants.ROLE_READ)
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRoles() {
        List<RoleEntity> roles = roleRepo.findAll();
        List<Long> ids = roles.stream().map(RoleEntity::getId).collect(Collectors.toList());
        // Fetch with permissions in one query to avoid N+1.
        List<RoleEntity> hydrated = roleRepo.findByIdInWithPermissions(ids);
        return hydrated.stream().map(this::roleView).collect(Collectors.toList());
    }

    @GetMapping("/roles/{id}")
    @PreAuthorize(PermissionConstants.ROLE_READ)
    @Transactional(readOnly = true)
    public Map<String, Object> getRole(@PathVariable Long id) {
        List<RoleEntity> result = roleRepo.findByIdInWithPermissions(List.of(id));
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found");
        return roleView(result.get(0));
    }

    @PostMapping("/roles")
    @PreAuthorize(PermissionConstants.ROLE_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createRole(@RequestBody Map<String, Object> body, Authentication authentication) {
        String name = requireString(body, "name").toUpperCase();
        String description = (String) body.get("description");
        @SuppressWarnings("unchecked")
        List<String> permCodes = (List<String>) body.getOrDefault("permissions", List.of());

        if (roleRepo.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Role already exists: " + name);
        }
        Set<PermissionEntity> permissions = resolvePermissions(permCodes);
        RoleEntity role = new RoleEntity();
        role.setName(name);
        role.setDescription(description);
        role.setPermissions(permissions);
        RoleEntity saved = roleRepo.save(role);
        auditService.logRoleCreated(resolveActorId(authentication), resolveActorEmail(authentication),
                saved.getId(), saved.getName(), String.join(",", permCodes));
        return roleView(saved);
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize(PermissionConstants.ROLE_UPDATE)
    @Transactional
    public Map<String, Object> updateRole(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication authentication) {
        RoleEntity role = roleRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found"));
        String oldPerms = role.getPermissions().stream()
                .map(PermissionEntity::getCode).sorted().collect(Collectors.joining(","));
        if (body.containsKey("description")) {
            role.setDescription((String) body.get("description"));
        }
        if (body.containsKey("permissions")) {
            @SuppressWarnings("unchecked")
            List<String> permCodes = (List<String>) body.get("permissions");
            role.setPermissions(resolvePermissions(permCodes));
        }
        RoleEntity saved = roleRepo.save(role);
        String newPerms = saved.getPermissions().stream()
                .map(PermissionEntity::getCode).sorted().collect(Collectors.joining(","));
        Long actorId = resolveActorId(authentication);
        String actorEmail = resolveActorEmail(authentication);
        auditService.logRoleUpdated(actorId, actorEmail, saved.getId(), saved.getName(), oldPerms, newPerms);
        // Log individual permission add/remove events for granular auditability.
        Set<String> oldSet = oldPerms.isBlank() ? Set.of()
                : new HashSet<>(Arrays.asList(oldPerms.split(",")));
        Set<String> newSet = newPerms.isBlank() ? Set.of()
                : new HashSet<>(Arrays.asList(newPerms.split(",")));
        newSet.stream().filter(p -> !oldSet.contains(p))
                .forEach(p -> auditService.logPermissionAssigned(actorId, actorEmail, saved.getId(), saved.getName(), p));
        oldSet.stream().filter(p -> !newSet.contains(p))
                .forEach(p -> auditService.logPermissionRemoved(actorId, actorEmail, saved.getId(), saved.getName(), p));
        return roleView(saved);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    @GetMapping("/permissions")
    @PreAuthorize(PermissionConstants.PERMISSION_READ)
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPermissions() {
        return permissionRepo.findAll().stream()
                .sorted((a, b) -> a.getCode().compareTo(b.getCode()))
                .map(p -> Map.<String, Object>of("id", p.getId(), "code", p.getCode(), "description", p.getDescription() != null ? p.getDescription() : ""))
                .collect(Collectors.toList());
    }

    // ── User role assignments ─────────────────────────────────────────────────

    @GetMapping("/users/{userId}/roles")
    @PreAuthorize(PermissionConstants.ROLE_READ)
    public List<Map<String, Object>> getUserRoles(@PathVariable Long userId) {
        return rbacService.getUserRoleAssignments(userId).stream()
                .map(this::assignmentView)
                .collect(Collectors.toList());
    }

    @GetMapping("/users/{userId}/permissions")
    @PreAuthorize(PermissionConstants.ROLE_READ)
    public List<String> getUserPermissions(
            @PathVariable Long userId,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long zoneId) {
        if (schoolId != null || zoneId != null) {
            return rbacService.getEffectivePermissions(userId, schoolId, zoneId);
        }
        return rbacService.getEffectivePermissions(userId);
    }

    @PostMapping("/users/{userId}/roles/platform")
    @PreAuthorize(PermissionConstants.ROLE_UPDATE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignPlatformRole(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        String roleCode = requireString(body, "role");
        UserRoleAssignmentEntity ura = rbacService.assignPlatformRole(userId, roleCode, resolveActorId(authentication));
        return assignmentView(ura);
    }

    @PostMapping("/users/{userId}/roles/school")
    @PreAuthorize(PermissionConstants.ROLE_UPDATE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignSchoolRole(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        String roleCode = requireString(body, "role");
        Long schoolId = requireLong(body, "schoolId");
        UserRoleAssignmentEntity ura = rbacService.assignSchoolRole(userId, roleCode, schoolId, resolveActorId(authentication));
        return assignmentView(ura);
    }

    @PostMapping("/users/{userId}/roles/zone")
    @PreAuthorize(PermissionConstants.ROLE_UPDATE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignZoneRole(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        String roleCode = requireString(body, "role");
        Long zoneId = requireLong(body, "zoneId");
        UserRoleAssignmentEntity ura = rbacService.assignZoneRole(userId, roleCode, zoneId, resolveActorId(authentication));
        return assignmentView(ura);
    }

    @DeleteMapping("/users/{userId}/roles/{assignmentId}")
    @PreAuthorize(PermissionConstants.ROLE_UPDATE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAssignment(
            @PathVariable Long userId,
            @PathVariable Long assignmentId,
            Authentication authentication) {
        rbacService.revokeRoleAssignment(assignmentId, resolveActorId(authentication));
    }

    @PutMapping("/users/{userId}/role")
    @PreAuthorize(PermissionConstants.ROLE_UPDATE)
    @ResponseStatus(HttpStatus.GONE)
    public Map<String, String> replaceRole(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        return Map.of("error", "PUT /users/{userId}/role is removed due to scope-promotion risk. " +
                "Revoke the existing assignment via DELETE /users/{userId}/assignments/{id}, " +
                "then assign the new role using POST /users/{userId}/assignments.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> assignmentView(UserRoleAssignmentEntity ura) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", ura.getId());
        view.put("role", ura.getRole().getName());
        view.put("active", ura.isActive());
        view.put("effective", ura.isEffective());
        view.put("schoolId", ura.getSchoolId());
        view.put("zoneId", ura.getZoneId());
        view.put("validFrom", ura.getValidFrom());
        view.put("validUntil", ura.getValidUntil());
        view.put("assignedAt", ura.getAssignedAt());
        view.put("assignedBy", ura.getAssignedBy());
        if (!ura.isEffective()) {
            view.put("revokedAt", ura.getRevokedAt());
            view.put("revokedBy", ura.getRevokedBy());
        }
        return view;
    }

    private Map<String, Object> roleView(RoleEntity role) {
        List<String> permCodes = role.getPermissions().stream()
                .map(PermissionEntity::getCode).sorted().collect(Collectors.toList());
        return Map.of(
                "id", role.getId(),
                "name", role.getName(),
                "description", role.getDescription() != null ? role.getDescription() : "",
                "permissions", permCodes);
    }

    private Set<PermissionEntity> resolvePermissions(List<String> codes) {
        if (codes == null || codes.isEmpty()) return new HashSet<>();
        List<PermissionEntity> found = permissionRepo.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            Set<String> foundCodes = found.stream().map(PermissionEntity::getCode).collect(Collectors.toSet());
            List<String> missing = codes.stream().filter(c -> !foundCodes.contains(c)).collect(Collectors.toList());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown permission codes: " + missing);
        }
        return new HashSet<>(found);
    }

    private static Long requireLong(Map<String, Object> body, String field) {
        Object val = body.get(field);
        if (val == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: " + field);
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); }
        catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid numeric field: " + field);
        }
    }

    private static String requireString(Map<String, Object> body, String field) {
        Object val = body.get(field);
        if (val == null || val.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: " + field);
        }
        return val.toString().trim();
    }

    private static Long resolveActorId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof AppUserDetails details) {
            return details.getUser().getId();
        }
        return null;
    }

    private static String resolveActorEmail(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof AppUserDetails details) {
            return details.getUser().getEmail();
        }
        return null;
    }
}

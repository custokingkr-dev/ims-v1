package com.custoking.ims.service;

import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.entity.RoleEntity;
import com.custoking.ims.entity.UserRoleAssignmentEntity;
import com.custoking.ims.events.RoleAssignedEvent;
import com.custoking.ims.events.RoleRevokedEvent;
import com.custoking.ims.repo.RoleRepository;
import com.custoking.ims.repo.UserRoleAssignmentRepository;
import com.custoking.ims.security.AppUserDetails;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central RBAC service. Bean name "rbacService" enables SpEL expressions:
 *   @PreAuthorize("@rbacService.hasPermission(authentication, 'perm:code')")
 *   @PreAuthorize("@rbacService.hasAnyPermission(authentication, 'a:b', 'c:d')")
 *
 * Permission checks use in-memory permissions loaded at auth time (fast path).
 * Falls back to a DB query for principals constructed without pre-loaded permissions
 * (e.g., test scenarios using SecurityMockMvcRequestPostProcessors.user()).
 */
@Service("rbacService")
@Transactional(readOnly = true)
public class RbacService {

    private final UserRoleAssignmentRepository uraRepo;
    private final RoleRepository roleRepo;
    private final RbacAuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public RbacService(UserRoleAssignmentRepository uraRepo, RoleRepository roleRepo,
                       RbacAuditService auditService, ApplicationEventPublisher eventPublisher) {
        this.uraRepo = uraRepo;
        this.roleRepo = roleRepo;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    // ── Spring Security SpEL entry points ────────────────────────────────────

    public boolean hasPermission(Authentication authentication, String permissionCode) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        if (!(authentication.getPrincipal() instanceof AppUserDetails details)) return false;
        Set<String> perms = details.getPermissions();
        if (!perms.isEmpty()) {
            return perms.contains(permissionCode);
        }
        return userHasPermission(details.getUser().getId(), permissionCode);
    }

    /** True if the principal holds AT LEAST ONE of the given permission codes. */
    public boolean hasAnyPermission(Authentication authentication, String... permissionCodes) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        if (!(authentication.getPrincipal() instanceof AppUserDetails details)) return false;
        Set<String> perms = details.getPermissions();
        if (!perms.isEmpty()) {
            return Arrays.stream(permissionCodes).anyMatch(perms::contains);
        }
        Set<String> dbPerms = getUserPermissions(details.getUser().getId());
        return Arrays.stream(permissionCodes).anyMatch(dbPerms::contains);
    }

    /** True if the principal holds ALL of the given permission codes. */
    public boolean hasAllPermissions(Authentication authentication, String... permissionCodes) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        if (!(authentication.getPrincipal() instanceof AppUserDetails details)) return false;
        Set<String> perms = details.getPermissions();
        if (!perms.isEmpty()) {
            return Arrays.stream(permissionCodes).allMatch(perms::contains);
        }
        Set<String> dbPerms = getUserPermissions(details.getUser().getId());
        return Arrays.stream(permissionCodes).allMatch(dbPerms::contains);
    }

    // ── Core permission checks ────────────────────────────────────────────────

    public boolean userHasPermission(Long userId, String permissionCode) {
        return getUserPermissions(userId).contains(permissionCode);
    }

    public Set<String> getUserPermissions(Long userId) {
        return uraRepo.findByUser_IdWithPermissions(userId).stream()
                .filter(UserRoleAssignmentEntity::isEffective)
                .flatMap(ura -> ura.getRole().getPermissions().stream())
                .map(p -> p.getCode())
                .collect(Collectors.toSet());
    }

    /** Returns effective permissions as a sorted list — useful for APIs. */
    public List<String> getEffectivePermissions(Long userId) {
        return getUserPermissions(userId).stream().sorted().collect(Collectors.toList());
    }

    /**
     * Returns effective permissions for the currently authenticated user via
     * the SecurityContext. Returns an empty list for anonymous or non-AppUserDetails
     * principals.
     */
    public List<String> getEffectivePermissionsForCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return List.of();
        if (!(auth.getPrincipal() instanceof AppUserDetails details)) return List.of();
        Set<String> perms = details.getPermissions();
        if (!perms.isEmpty()) {
            return perms.stream().sorted().collect(Collectors.toList());
        }
        return getEffectivePermissions(details.getUser().getId());
    }

    public List<String> getUserRoleNames(Long userId) {
        return uraRepo.findByUser_Id(userId).stream()
                .filter(UserRoleAssignmentEntity::isEffective)
                .map(ura -> ura.getRole().getName())
                .collect(Collectors.toList());
    }

    // ── Scoped role assignment ────────────────────────────────────────────────

    /** Assigns a platform-wide role (school_id=null, zone_id=null). */
    @Transactional
    public UserRoleAssignmentEntity assignPlatformRole(Long userId, String roleCode, Long assignedBy) {
        return assignScopedRole(userId, roleCode, null, null, assignedBy);
    }

    /** Assigns a school-scoped role. */
    @Transactional
    public UserRoleAssignmentEntity assignSchoolRole(Long userId, String roleCode, Long schoolId, Long assignedBy) {
        if (schoolId == null) throw new IllegalArgumentException("schoolId required for school-scoped assignment");
        return assignScopedRole(userId, roleCode, schoolId, null, assignedBy);
    }

    /** Assigns a zone-scoped role. */
    @Transactional
    public UserRoleAssignmentEntity assignZoneRole(Long userId, String roleCode, Long zoneId, Long assignedBy) {
        if (zoneId == null) throw new IllegalArgumentException("zoneId required for zone-scoped assignment");
        return assignScopedRole(userId, roleCode, null, zoneId, assignedBy);
    }

    private UserRoleAssignmentEntity assignScopedRole(Long userId, String roleCode,
                                                       Long schoolId, Long zoneId, Long assignedBy) {
        String name = roleCode.toUpperCase();
        if (uraRepo.existsActiveAssignment(userId, name, schoolId, zoneId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Active assignment already exists for this user/role/scope combination");
        }
        RoleEntity role = roleRepo.findByName(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role: " + roleCode));
        AppUserEntity userRef = new AppUserEntity();
        userRef.setId(userId);
        UserRoleAssignmentEntity ura = new UserRoleAssignmentEntity();
        ura.setUser(userRef);
        ura.setRole(role);
        ura.setSchoolId(schoolId);
        ura.setZoneId(zoneId);
        ura.setAssignedBy(assignedBy);
        ura.setActive(true);
        UserRoleAssignmentEntity saved = uraRepo.save(ura);
        auditService.logRoleAssigned(assignedBy, null, userId, role.getId(), role.getName(), schoolId, zoneId);
        eventPublisher.publishEvent(new RoleAssignedEvent(this, saved.getId(), userId,
                role.getName(), schoolId, zoneId, assignedBy));
        return saved;
    }

    /** Soft-revokes an assignment by ID (sets active=false and stamps revocation metadata). */
    @Transactional
    public void revokeRoleAssignment(Long assignmentId, Long revokedBy) {
        UserRoleAssignmentEntity ura = uraRepo.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Assignment not found: " + assignmentId));
        ura.setActive(false);
        ura.setRevokedBy(revokedBy);
        ura.setRevokedAt(OffsetDateTime.now());
        uraRepo.save(ura);
        auditService.logRoleRevoked(revokedBy, null, ura.getUser().getId(),
                ura.getRole().getId(), ura.getRole().getName(),
                ura.getSchoolId(), ura.getZoneId());
        eventPublisher.publishEvent(new RoleRevokedEvent(this, assignmentId, ura.getUser().getId(),
                ura.getRole().getName(), ura.getSchoolId(), ura.getZoneId(), revokedBy));
    }

    /** Alias for {@link #revokeRoleAssignment} — soft-disables without implying permanent revocation. */
    @Transactional
    public void disableRoleAssignment(Long assignmentId, Long disabledBy) {
        revokeRoleAssignment(assignmentId, disabledBy);
    }

    /** Returns all assignments (active and inactive) for a user. */
    public List<UserRoleAssignmentEntity> getUserRoleAssignments(Long userId) {
        return uraRepo.findByUser_Id(userId);
    }

    /**
     * Returns effective permissions for a user in the context of a specific school/zone.
     * Platform-wide assignments (school_id=null, zone_id=null) always contribute.
     */
    public List<String> getEffectivePermissions(Long userId, Long schoolId, Long zoneId) {
        return uraRepo.findByUser_IdWithPermissions(userId).stream()
                .filter(UserRoleAssignmentEntity::isEffective)
                .filter(ura -> {
                    boolean isPlatform = ura.getSchoolId() == null && ura.getZoneId() == null;
                    boolean schoolMatch = schoolId != null && schoolId.equals(ura.getSchoolId());
                    boolean zoneMatch = zoneId != null && zoneId.equals(ura.getZoneId());
                    return isPlatform || schoolMatch || zoneMatch;
                })
                .flatMap(ura -> ura.getRole().getPermissions().stream())
                .map(p -> p.getCode())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    // ── Legacy assignment API (disabled) ─────────────────────────────────────

    /**
     * @deprecated Disabled — always promoted to platform scope, creating a privilege-escalation risk.
     * Use {@link #assignPlatformRole}, {@link #assignSchoolRole}, or {@link #assignZoneRole} explicitly.
     */
    @Deprecated(forRemoval = true)
    @Transactional
    public void assignRole(Long userId, String roleName, Long assignedBy) {
        throw new UnsupportedOperationException(
                "assignRole() is disabled due to accidental platform-scope risk. " +
                "Use assignPlatformRole(), assignSchoolRole(schoolId), or assignZoneRole(zoneId) instead.");
    }

    /** @deprecated Use {@link #revokeRoleAssignment(Long, Long)} with assignment ID. */
    @Deprecated(forRemoval = true)
    @Transactional
    public void revokeRole(Long userId, String roleName) {
        uraRepo.findByUser_Id(userId).stream()
                .filter(UserRoleAssignmentEntity::isActive)
                .filter(ura -> roleName.toUpperCase().equals(ura.getRole().getName()))
                .findFirst()
                .ifPresent(ura -> revokeRoleAssignment(ura.getId(), null));
    }

    /**
     * @deprecated Disabled: always promoted to platform scope, creating a privilege-escalation risk.
     * Use {@link #revokeRoleAssignment} + {@link #assignSchoolRole} / {@link #assignPlatformRole} instead.
     */
    @Deprecated(forRemoval = true)
    @Transactional
    public void replaceRole(Long userId, String newRoleName, Long assignedBy) {
        throw new UnsupportedOperationException(
                "replaceRole() is disabled due to scope-promotion risk. " +
                "Revoke specific assignments with revokeRoleAssignment(), then assign the new role " +
                "using assignSchoolRole() or assignPlatformRole() with the correct scope.");
    }
}

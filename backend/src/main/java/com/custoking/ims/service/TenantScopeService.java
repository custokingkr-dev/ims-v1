package com.custoking.ims.service;

import com.custoking.ims.context.TenantScope;
import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.entity.UserRoleAssignmentEntity;
import com.custoking.ims.repo.UserRoleAssignmentRepository;
import com.custoking.ims.repo.ZoneAdminAssignmentRepository;
import com.custoking.ims.repo.ZoneSchoolMappingRepository;
import com.custoking.ims.security.AppUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Derives tenant scope from permissions and user_role_assignments.
 *
 * Design principle: app_users.role is NOT used for authorization decisions.
 * It is kept on the entity for display/logging only. All access decisions use:
 *   - effective permissions loaded at authentication time (fast in-memory set)
 *   - user_role_assignments rows (school_id / zone_id scope columns)
 *
 * RLS is currently disabled (V117). Isolation is enforced here at the application layer.
 */
@Service
@Transactional(readOnly = true)
public class TenantScopeService {

    /** Permission code granted exclusively to platform-level (SUPERADMIN) roles. */
    private static final String PLATFORM_PERMISSION = "platform:admin";

    private final RbacService rbacService;
    private final UserRoleAssignmentRepository uraRepo;
    private final ZoneAdminAssignmentRepository zoneAdminRepo;
    private final ZoneSchoolMappingRepository zoneMappingRepo;

    public TenantScopeService(RbacService rbacService,
                               UserRoleAssignmentRepository uraRepo,
                               ZoneAdminAssignmentRepository zoneAdminRepo,
                               ZoneSchoolMappingRepository zoneMappingRepo) {
        this.rbacService = rbacService;
        this.uraRepo = uraRepo;
        this.zoneAdminRepo = zoneAdminRepo;
        this.zoneMappingRepo = zoneMappingRepo;
    }

    // ── Scope building ────────────────────────────────────────────────────────

    public TenantScope buildScope(AppUserEntity user) {
        List<String> roleNames = rbacService.getUserRoleNames(user.getId());
        Set<String> permissions = rbacService.getUserPermissions(user.getId());

        // Platform access is determined by permissions, not by app_users.role string.
        boolean isPlatformAdmin = permissions.contains(PLATFORM_PERMISSION);

        List<Long> accessibleSchoolIds = new ArrayList<>();
        Long rbacPrimarySchoolId = null;

        if (!isPlatformAdmin) {
            List<UserRoleAssignmentEntity> effective = uraRepo.findByUser_Id(user.getId()).stream()
                    .filter(UserRoleAssignmentEntity::isEffective)
                    .collect(Collectors.toList());

            for (UserRoleAssignmentEntity ura : effective) {
                if (ura.getZoneId() != null) {
                    zoneMappingRepo.findByZone_Id(ura.getZoneId())
                            .forEach(zsm -> accessibleSchoolIds.add(zsm.getSchool().getId()));
                }
                if (ura.getSchoolId() != null) {
                    if (rbacPrimarySchoolId == null) rbacPrimarySchoolId = ura.getSchoolId();
                    if (!accessibleSchoolIds.contains(ura.getSchoolId())) {
                        accessibleSchoolIds.add(ura.getSchoolId());
                    }
                }
            }
        }

        // Primary school: derived from RBAC school assignments only.
        Long effectiveSchoolId = rbacPrimarySchoolId;

        return new TenantScope(
                user.getId(),
                user.getRole(),   // for display/logging only — not used for authz
                Collections.unmodifiableList(roleNames),
                Collections.unmodifiableSet(permissions),
                effectiveSchoolId,
                user.getBranchName(),
                user.getZoneId(),
                user.getZoneName(),
                Collections.unmodifiableList(accessibleSchoolIds),
                isPlatformAdmin
        );
    }

    public TenantScope getCurrentScope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return buildScope(details.getUser());
    }

    // ── Permission-based access checks (preferred API) ────────────────────────

    /** True if the user holds a platform-level role (SUPERADMIN). */
    public boolean hasPlatformAccess(Long userId) {
        return rbacService.getUserPermissions(userId).contains(PLATFORM_PERMISSION);
    }

    /** True if the user can access the given school (platform-wide, zone, or direct). */
    public boolean canAccessSchool(Long userId, Long schoolId) {
        if (hasPlatformAccess(userId)) return true;
        return accessibleSchoolIds(userId).contains(schoolId);
    }

    /** True if the user has an active zone-scoped assignment that covers this zone. */
    public boolean canAccessZone(Long userId, Long zoneId) {
        if (hasPlatformAccess(userId)) return true;
        return uraRepo.findByUser_Id(userId).stream()
                .filter(UserRoleAssignmentEntity::isEffective)
                .anyMatch(ura -> zoneId.equals(ura.getZoneId()));
    }

    /** All school IDs accessible to the user via direct assignment or zone membership. */
    public Set<Long> accessibleSchoolIds(Long userId) {
        if (hasPlatformAccess(userId)) return Set.of(); // empty = unrestricted for platform

        Set<Long> ids = new HashSet<>();
        for (UserRoleAssignmentEntity ura : uraRepo.findByUser_Id(userId)) {
            if (!ura.isEffective()) continue;
            if (ura.getSchoolId() != null) ids.add(ura.getSchoolId());
            if (ura.getZoneId() != null) {
                zoneMappingRepo.findByZone_Id(ura.getZoneId())
                        .forEach(zsm -> ids.add(zsm.getSchool().getId()));
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /** Zone IDs the user holds active zone-scoped assignments for. */
    public Set<Long> accessibleZoneIds(Long userId) {
        return uraRepo.findByUser_Id(userId).stream()
                .filter(UserRoleAssignmentEntity::isEffective)
                .filter(ura -> ura.getZoneId() != null)
                .map(UserRoleAssignmentEntity::getZoneId)
                .collect(Collectors.toUnmodifiableSet());
    }

    // ── Authentication-aware enforcement methods ──────────────────────────────

    /** Throws 403 if the authenticated user cannot access the given school. */
    public void requireSchoolAccess(Authentication auth, Long schoolId) {
        AppUserDetails details = resolveDetails(auth);
        if (!canAccessSchool(details.getUser().getId(), schoolId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Access denied: school " + schoolId);
        }
    }

    /** Throws 403 if the authenticated user cannot access the given zone. */
    public void requireZoneAccess(Authentication auth, Long zoneId) {
        AppUserDetails details = resolveDetails(auth);
        if (!canAccessZone(details.getUser().getId(), zoneId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Access denied: zone " + zoneId);
        }
    }

    /**
     * Returns a validated effective school ID.
     * - Platform admins: any supplied schoolId is accepted.
     * - School/zone-scoped users: supplied schoolId must be in their accessible set.
     * - If schoolId is null and user is school-scoped: returns their assigned school.
     */
    public Long resolveSchoolScope(Authentication auth, Long requestedSchoolId) {
        AppUserDetails details = resolveDetails(auth);
        Long userId = details.getUser().getId();
        if (hasPlatformAccess(userId)) return requestedSchoolId;

        Set<Long> ids = accessibleSchoolIds(userId);
        if (requestedSchoolId != null) {
            if (!ids.contains(requestedSchoolId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Access denied: school " + requestedSchoolId);
            }
            return requestedSchoolId;
        }
        return ids.isEmpty() ? null : ids.iterator().next();
    }

    /** Returns all accessible school IDs for the authenticated user. */
    public Set<Long> getAccessibleSchoolIds(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails details)) return Set.of();
        return accessibleSchoolIds(details.getUser().getId());
    }

    // ── Legacy API (kept for backward compat; prefer the two-arg variants above) ──

    /** @deprecated Use {@link #canAccessSchool(Long, Long)} */
    @Deprecated(forRemoval = true)
    public boolean canAccessSchool(Long schoolId) {
        TenantScope scope = getCurrentScope();
        if (scope.isSuperadmin()) return true;
        if (scope.schoolId() != null && scope.schoolId().equals(schoolId)) return true;
        return scope.accessibleSchoolIds().contains(schoolId);
    }

    /** @deprecated Use {@link #resolveSchoolScope(Authentication, Long)} */
    @Deprecated(forRemoval = true)
    public Long resolveRequestedSchoolId(Long requestedId) {
        TenantScope scope = getCurrentScope();
        if (scope.isSuperadmin()) return requestedId;
        if (!scope.accessibleSchoolIds().isEmpty()) {
            if (requestedId != null && scope.accessibleSchoolIds().contains(requestedId)) return requestedId;
            return scope.accessibleSchoolIds().isEmpty() ? null : scope.accessibleSchoolIds().get(0);
        }
        return scope.schoolId();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static AppUserDetails resolveDetails(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return details;
    }
}

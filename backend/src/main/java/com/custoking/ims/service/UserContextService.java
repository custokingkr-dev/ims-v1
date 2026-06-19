package com.custoking.ims.service;

import com.custoking.ims.context.TenantContext;
import com.custoking.ims.context.TenantScope;
import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.entity.UserRoleAssignmentEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.security.AppUserDetails;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserContextService {

    private final RbacService rbacService;

    public UserContextService(@Lazy RbacService rbacService) {
        this.rbacService = rbacService;
    }

    /**
     * Returns an identity-only AuthUser (fast — no RBAC lookup).
     * Use for service calls that only need userId/email for audit trail purposes.
     */
    public AuthUser requireUser(String ignored) {
        AppUserDetails details = resolveDetails();
        AppUserEntity user = details.getUser();
        return AuthUser.identity(user.getId(), user.getFullName(), user.getEmail(),
                user.getRole(), user.getBranchId(), user.getBranchName());
    }

    /**
     * Returns a fully enriched AuthUser with RBAC scope context.
     * Derives permissions from the in-memory set loaded at authentication time (fast path).
     * Derives role names, school IDs, and zone IDs from TenantScope (set by TenantResolverFilter).
     */
    public AuthUser requireEnrichedUser(String ignored) {
        AppUserDetails details = resolveDetails();
        AppUserEntity user = details.getUser();

        Set<String> permissions = new HashSet<>(details.getPermissions());
        boolean platformAdmin = permissions.contains("platform:admin");

        TenantScope scope = TenantContext.getScope();
        List<String> roleNames = scope != null ? scope.roles() : List.of();
        List<Long> accessibleSchoolIds = scope != null ? scope.accessibleSchoolIds() : List.of();

        // Accessible zone IDs: collect from effective zone-scoped assignments.
        Set<Long> accessibleZoneIds = rbacService.getUserRoleAssignments(user.getId()).stream()
                .filter(UserRoleAssignmentEntity::isEffective)
                .filter(ura -> ura.getZoneId() != null)
                .map(UserRoleAssignmentEntity::getZoneId)
                .collect(Collectors.toSet());

        return new AuthUser(user.getId(), user.getFullName(), user.getEmail(),
                user.getRole(), user.getBranchId(), user.getBranchName(), null,
                roleNames, permissions, new HashSet<>(accessibleSchoolIds), accessibleZoneIds, platformAdmin);
    }

    /** Throws 403 unless the authenticated user holds the platform:admin permission. */
    public AuthUser requireSuperAdmin(String ignored) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        if (!rbacService.hasPermission(auth, "platform:admin")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin access required");
        }
        AppUserEntity user = details.getUser();
        return AuthUser.identity(user.getId(), user.getFullName(), user.getEmail(),
                user.getRole(), user.getBranchId(), user.getBranchName());
    }

    /** Returns true if the current user is a platform admin (holds platform:admin permission). */
    public boolean isPlatformAdmin() {
        var scope = TenantContext.getScope();
        return scope != null && scope.isSuperadmin();
    }

    private AppUserDetails resolveDetails() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return details;
    }
}

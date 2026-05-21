package com.custoking.ims.model;

import java.util.List;
import java.util.Set;

/**
 * Represents the authenticated user context passed to service methods.
 *
 * displayRole:         legacy app_users.role — for display/logging ONLY.
 * branchId:            legacy app_users.branch_id — for display/logging ONLY.
 * roleNames:           effective role names from RBAC assignments.
 * permissions:         effective permission codes from RBAC assignments.
 * accessibleSchoolIds: schools accessible via direct or zone-derived RBAC assignments.
 * accessibleZoneIds:   zones accessible via zone-scoped RBAC assignments.
 * platformAdmin:       true when the user holds the platform:admin permission.
 *
 * Use roleNames/permissions/accessibleSchoolIds for authorization, not displayRole/branchId.
 */
public record AuthUser(
        long userId,
        String fullName,
        String email,
        String displayRole,
        Long branchId,
        String branchName,
        String password,
        List<String> roleNames,
        Set<String> permissions,
        Set<Long> accessibleSchoolIds,
        Set<Long> accessibleZoneIds,
        boolean platformAdmin
) {
    /** Compact constructor — null-safe defaults for enriched fields. */
    public AuthUser {
        if (roleNames == null) roleNames = List.of();
        if (permissions == null) permissions = Set.of();
        if (accessibleSchoolIds == null) accessibleSchoolIds = Set.of();
        if (accessibleZoneIds == null) accessibleZoneIds = Set.of();
    }

    /** Convenience factory for callers that only need identity fields (no RBAC enrichment). */
    public static AuthUser identity(long userId, String fullName, String email,
                                    String displayRole, Long branchId, String branchName) {
        return new AuthUser(userId, fullName, email, displayRole, branchId, branchName,
                null, List.of(), Set.of(), Set.of(), Set.of(), false);
    }
}

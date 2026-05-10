package com.custoking.ims.context;

import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of the current user's tenant scope, built once per request
 * and stored in TenantContext for the duration of that request.
 */
public record TenantScope(
        Long userId,
        String primaryRole,
        List<String> roles,
        Set<String> permissions,
        Long schoolId,
        String schoolName,
        Long zoneId,
        String zoneName,
        List<Long> accessibleSchoolIds,
        boolean isSuperadmin
) {}

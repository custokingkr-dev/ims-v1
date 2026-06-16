package com.custoking.ims.context;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

public final class TenantAccess {

    private TenantAccess() {}

    public static Long resolveSchoolId(Long requestedSchoolId) {
        TenantScope scope = TenantContext.getScope();
        if (scope == null) {
            Long tenantSchoolId = TenantContext.get();
            return tenantSchoolId != null ? tenantSchoolId : requestedSchoolId;
        }

        if (scope.isSuperadmin()) {
            return requestedSchoolId;
        }

        if (scope.schoolId() != null) {
            if (requestedSchoolId != null && !scope.schoolId().equals(requestedSchoolId)) {
                denySchoolAccess();
            }
            return scope.schoolId();
        }

        List<Long> accessibleSchoolIds = scope.accessibleSchoolIds();
        if (!accessibleSchoolIds.isEmpty()) {
            if (requestedSchoolId == null) {
                return accessibleSchoolIds.get(0);
            }
            if (accessibleSchoolIds.contains(requestedSchoolId)) {
                return requestedSchoolId;
            }
            denySchoolAccess();
        }

        if (requestedSchoolId != null) {
            denySchoolAccess();
        }
        return null;
    }

    public static void assertSchoolAccess(String entityLabel, Long entitySchoolId, Long requestedSchoolId) {
        Long resolvedSchoolId = resolveSchoolId(requestedSchoolId);
        if (resolvedSchoolId != null && entitySchoolId != null && !resolvedSchoolId.equals(entitySchoolId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You do not have access to this " + entityLabel);
        }
    }

    private static void denySchoolAccess() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have access to this school");
    }
}

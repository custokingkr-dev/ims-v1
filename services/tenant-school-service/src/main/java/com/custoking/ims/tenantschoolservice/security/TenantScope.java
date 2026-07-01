package com.custoking.ims.tenantschoolservice.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class TenantScope {

    private TenantScope() {}

    /**
     * Resolve the effective school id for the current request.
     * Superadmin may widen (returns {@code requested}, possibly null = all schools).
     * Otherwise the request is locked to the authenticated school; a cross-tenant
     * request (or absence of an authenticated school) is rejected with 403.
     */
    public static Long resolveSchoolId(Long requested) {
        TenantContext ctx = TenantContext.get();
        if (ctx.isSuperAdmin()) {
            return requested;
        }
        Long authed = ctx.schoolId();
        if (authed == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no tenant scope for request");
        }
        if (requested != null && !requested.equals(authed)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cross-tenant access denied");
        }
        return authed;
    }

    public static void requireSuperAdmin() {
        if (!TenantContext.get().isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "superadmin required");
        }
    }
}

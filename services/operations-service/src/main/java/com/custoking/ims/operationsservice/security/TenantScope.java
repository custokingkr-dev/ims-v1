package com.custoking.ims.operationsservice.security;

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

    public static void requirePermission(String code) {
        TenantContext ctx = TenantContext.get();
        if (ctx.isSuperAdmin()) return;                       // superadmin bypass
        // Fail closed: a non-superadmin without the permission is denied, whether the
        // permission set is empty (no/absent header, or a genuinely permission-less user)
        // or simply lacks the code. Note: non-superadmin approvers holding a pre-ver-3 token
        // (no perms claim) are denied until their access token refreshes (<=15 min post-deploy).
        if (!ctx.hasPermission(code)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You do not have permission to approve firefighting requests");
        }
    }
}

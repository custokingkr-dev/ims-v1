package com.custoking.ims.schoolcoreservice.security;

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

    /**
     * Read scope for platform-wide readers. Superadmin passes through unbounded (null = all
     * schools). Operations is bounded to its superadmin-assigned operator school set: an
     * explicit request outside that set is rejected; no request (null) is left to RLS
     * (app.operator_schools) to bound at the query layer. Everyone else is locked to their own
     * school via {@link #resolveSchoolId}.
     */
    public static Long resolvePlatformReadScope(Long requested) {
        TenantContext ctx = TenantContext.get();
        if (ctx.isSuperAdmin()) return requested;
        if (ctx.isOperations()) {
            if (requested != null && !ctx.operatorSchools().contains(requested)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "school not in operator scope");
            }
            return requested;
        }
        return resolveSchoolId(requested);
    }

    public static void requireSuperAdmin() {
        if (!TenantContext.get().isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "superadmin required");
        }
    }

    public static void requireSchoolAdmin() {
        TenantContext ctx = TenantContext.get();
        if (ctx.isSuperAdmin()) {
            return;
        }
        String role = ctx.role();
        // Both ADMIN and SCHOOL_ADMIN are school-level administrator roles in this system.
        if (role == null || !(role.equalsIgnoreCase("ADMIN") || role.equalsIgnoreCase("SCHOOL_ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "school admin role required");
        }
    }

    public static void requirePermission(String code) {
        TenantContext ctx = TenantContext.get();
        if (ctx.isSuperAdmin()) return;
        if (!ctx.hasPermission(code)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "permission required: " + code);
        }
    }

    public static void requireAnyPermission(String... codes) {
        TenantContext ctx = TenantContext.get();
        if (ctx.isSuperAdmin()) return;
        for (String code : codes) {
            if (ctx.hasPermission(code)) return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "required permission missing");
    }

    public static void requirePermissionIfAuthenticated(String code) {
        TenantContext ctx = TenantContext.get();
        if (!ctx.isAuthenticated() || ctx.isSuperAdmin()) return;
        requirePermission(code);
    }

    public static void requireAnyPermissionIfAuthenticated(String... codes) {
        TenantContext ctx = TenantContext.get();
        if (!ctx.isAuthenticated() || ctx.isSuperAdmin()) return;
        requireAnyPermission(codes);
    }
}

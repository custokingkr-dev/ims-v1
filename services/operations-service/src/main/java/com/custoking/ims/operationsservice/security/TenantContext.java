package com.custoking.ims.operationsservice.security;

import java.util.Set;

public final class TenantContext {

    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

    private final Long userId;
    private final String email;
    private final String role;
    private final Long schoolId;
    private final Long zoneId;
    private final Set<String> permissions;
    private final Set<Long> operatorSchools;
    private final Set<String> operatorScopes;

    public TenantContext(Long userId, String email, String role, Long schoolId, Long zoneId) {
        this(userId, email, role, schoolId, zoneId, Set.of());
    }

    public TenantContext(Long userId, String email, String role, Long schoolId, Long zoneId, Set<String> permissions) {
        this(userId, email, role, schoolId, zoneId, permissions, Set.of());
    }

    public TenantContext(Long userId, String email, String role, Long schoolId, Long zoneId,
                         Set<String> permissions, Set<Long> operatorSchools) {
        this(userId, email, role, schoolId, zoneId, permissions, operatorSchools, Set.of());
    }

    public TenantContext(Long userId, String email, String role, Long schoolId, Long zoneId,
                         Set<String> permissions, Set<Long> operatorSchools, Set<String> operatorScopes) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.schoolId = schoolId;
        this.zoneId = zoneId;
        this.permissions = (permissions == null) ? Set.of() : Set.copyOf(permissions);
        this.operatorSchools = (operatorSchools == null) ? Set.of() : Set.copyOf(operatorSchools);
        this.operatorScopes = (operatorScopes == null) ? Set.of() : Set.copyOf(operatorScopes);
    }

    public static void set(TenantContext ctx) { HOLDER.set(ctx); }

    public static TenantContext get() {
        TenantContext ctx = HOLDER.get();
        return ctx != null ? ctx : new TenantContext(null, null, null, null, null);
    }

    public static void clear() { HOLDER.remove(); }

    public Long userId() { return userId; }
    public String email() { return email; }
    public String role() { return role; }
    public Long schoolId() { return schoolId; }
    public Long zoneId() { return zoneId; }

    public Set<String> permissions() { return permissions; }
    public boolean hasPermission(String code) { return permissions.contains(code); }

    /** Schools this operator is assigned to; operators have no home school. Empty for everyone else. */
    public Set<Long> operatorSchools() { return operatorSchools; }

    /** Item-specific operator scopes as {@code schoolId:CATEGORY}. */
    public Set<String> operatorScopes() { return operatorScopes; }

    /**
     * Schools where this operator may work orders of a catalogue item: every school-wide assignment plus the schools
     * where they are scoped to exactly this item. A scope for another item grants nothing here.
     */
    public Set<Long> operatorSchoolsFor(String catalogCategory) {
        java.util.Set<Long> schools = new java.util.LinkedHashSet<>(operatorSchools);
        String suffix = ":" + catalogCategory;
        for (String scope : operatorScopes) {
            if (!scope.endsWith(suffix)) continue;
            try { schools.add(Long.parseLong(scope.substring(0, scope.length() - suffix.length()))); }
            catch (NumberFormatException malformed) { /* a malformed scope grants nothing */ }
        }
        return schools;
    }

    public boolean isSuperAdmin() { return role != null && role.equalsIgnoreCase("SUPERADMIN"); }

    public boolean isOperations() { return role != null && role.equalsIgnoreCase("OPERATIONS"); }

    public boolean isAuthenticated() { return userId != null || (role != null && !role.isBlank()); }
}

package com.custoking.ims.schoolcoreservice.security;

import java.util.Set;

public final class TenantContext {

    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

    private final Long userId;
    private final String email;
    private final String role;
    private final Long schoolId;
    private final Long zoneId;
    private final Set<Long> operatorSchools;
    private final Set<String> permissions;

    public TenantContext(Long userId, String email, String role, Long schoolId, Long zoneId) {
        this(userId, email, role, schoolId, zoneId, Set.of(), Set.of());
    }

    public TenantContext(Long userId, String email, String role, Long schoolId, Long zoneId, Set<Long> operatorSchools) {
        this(userId, email, role, schoolId, zoneId, operatorSchools, Set.of());
    }

    public TenantContext(Long userId, String email, String role, Long schoolId, Long zoneId,
                         Set<Long> operatorSchools, Set<String> permissions) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.schoolId = schoolId;
        this.zoneId = zoneId;
        this.operatorSchools = operatorSchools == null ? Set.of() : Set.copyOf(operatorSchools);
        this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
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
    public Set<Long> operatorSchools() { return operatorSchools; }
    public Set<String> permissions() { return permissions; }
    public boolean hasPermission(String code) { return permissions.contains(code); }

    public boolean isSuperAdmin() { return role != null && role.equalsIgnoreCase("SUPERADMIN"); }

    public boolean isOperations() { return role != null && role.equalsIgnoreCase("OPERATIONS"); }

    public boolean isAuthenticated() { return userId != null || (role != null && !role.isBlank()); }
}

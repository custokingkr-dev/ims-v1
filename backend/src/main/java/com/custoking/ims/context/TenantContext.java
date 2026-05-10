package com.custoking.ims.context;

public final class TenantContext {

    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<TenantScope> SCOPE = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long schoolId) {
        TENANT.set(schoolId);
    }

    public static Long get() {
        return TENANT.get();
    }

    public static void setScope(TenantScope scope) {
        SCOPE.set(scope);
    }

    public static TenantScope getScope() {
        return SCOPE.get();
    }

    public static void clear() {
        TENANT.remove();
        SCOPE.remove();
    }
}

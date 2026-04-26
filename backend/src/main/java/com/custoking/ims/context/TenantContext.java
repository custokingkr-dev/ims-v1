package com.custoking.ims.context;

public final class TenantContext {

    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long schoolId) {
        TENANT.set(schoolId);
    }

    public static Long get() {
        return TENANT.get();
    }

    public static void clear() {
        TENANT.remove();
    }
}

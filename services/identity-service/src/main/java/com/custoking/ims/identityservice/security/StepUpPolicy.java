package com.custoking.ims.identityservice.security;

import java.util.Locale;

/** Shared identity policy; no request header or JWT claim constitutes verification. */
public final class StepUpPolicy {
    private StepUpPolicy() { }
    public static boolean required(String role,String method,String path) {
        if(method==null || path==null) return false;
        String verb=method.toUpperCase(Locale.ROOT);
        if(path.startsWith("/api/v1/auth/") && !path.contains("users")) return false;
        boolean mutation=!verb.equals("GET") && !verb.equals("HEAD") && !verb.equals("OPTIONS");
        if(mutation && "SUPERADMIN".equalsIgnoreCase(role)) return true;
        String lower=path.toLowerCase(Locale.ROOT);
        return lower.contains("export") || lower.contains("permanent-delete")
                || lower.contains("purge") || (mutation && (lower.contains("role") || lower.contains("permission")
                || lower.contains("payment") || lower.contains("refund") || lower.contains("invoice")));
    }
}

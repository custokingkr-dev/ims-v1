package com.custoking.ims.operationsservice.security;

import com.custoking.ims.operationsservice.infrastructure.ModuleEntitlementClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces the FIREFIGHTING module entitlement on firefighting endpoints. Superadmins and
 * requests with no resolved school (e.g. platform-level callers) bypass the check. If the
 * entitlement lookup itself fails (peer/config error), we fail OPEN and allow the request —
 * an entitlement-lookup outage must never take down the firefighting workflow.
 */
@Component
public class FirefightingModuleInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FirefightingModuleInterceptor.class);

    private final ModuleEntitlementClient client;

    public FirefightingModuleInterceptor(ModuleEntitlementClient client) {
        this.client = client;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        TenantContext ctx = TenantContext.get();
        if (ctx.isSuperAdmin() || ctx.schoolId() == null) {
            return true;
        }
        try {
            if (!client.activeModules(ctx.schoolId()).contains("FIREFIGHTING")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This school does not have the Urgent Procurement module enabled");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // fail-open: entitlement lookup failed — do not break firefighting availability
            log.warn("firefighting entitlement lookup failed for school {}, allowing", ctx.schoolId(), e);
        }
        return true;
    }
}

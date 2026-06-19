package com.custoking.ims.security;

import com.custoking.ims.context.TenantContext;
import com.custoking.ims.context.TenantScope;
import com.custoking.ims.service.TenantScopeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TenantResolverFilter extends OncePerRequestFilter {

    private final TenantScopeService tenantScopeService;

    public TenantResolverFilter(@Lazy TenantScopeService tenantScopeService) {
        this.tenantScopeService = tenantScopeService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && auth.getPrincipal() instanceof AppUserDetails details) {

                // Build full tenant scope for all authenticated users
                TenantScope scope = tenantScopeService.buildScope(details.getUser());
                TenantContext.setScope(scope);

                // Enrich MDC for structured logging (picked up by logback-spring.xml prod profile)
                MDC.put("userId", String.valueOf(details.getUser().getId()));
                MDC.put("userEmail", details.getUser().getEmail());
                if (scope.schoolId() != null) {
                    MDC.put("schoolId", String.valueOf(scope.schoolId()));
                }

                // Platform admins operate across all schools — no school pinning.
                // School-scoped users get their RBAC-derived primary school set for module entitlement checks.
                if (!scope.isSuperadmin()) {
                    Long schoolId = scope.schoolId();
                    if (schoolId != null) {
                        TenantContext.set(schoolId);
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
            MDC.remove("userEmail");
            MDC.remove("schoolId");
            TenantContext.clear();
        }
    }
}

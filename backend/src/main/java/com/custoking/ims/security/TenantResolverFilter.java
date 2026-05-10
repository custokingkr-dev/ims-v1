package com.custoking.ims.security;

import com.custoking.ims.context.TenantContext;
import com.custoking.ims.context.TenantScope;
import com.custoking.ims.service.TenantScopeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
                String role = details.getUser().getRole();

                // Build full tenant scope for all authenticated users
                TenantScope scope = tenantScopeService.buildScope(details.getUser());
                TenantContext.setScope(scope);

                // Set numeric branchId for school-level users (not SUPERADMIN or ZONE_ADMIN)
                if (!"SUPERADMIN".equals(role) && !"ZONE_ADMIN".equals(role)) {
                    Long branchId = details.getUser().getBranchId();
                    if (branchId != null) {
                        TenantContext.set(branchId);
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}

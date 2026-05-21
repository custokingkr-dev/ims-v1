package com.custoking.ims.config;

import com.custoking.ims.context.TenantContext;
import com.custoking.ims.security.AppUserDetails;
import com.custoking.ims.security.AppUserDetailsService;
import com.custoking.ims.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    public RequestLoggingFilter(JwtService jwtService, AppUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String userId = MDC.get("userId");
        // Resolve userId from JWT for early MDC population (before security filters run).
        if (userId == null) {
            String token = resolveBearerToken(request);
            if (token != null) {
                try {
                    String username = jwtService.extractUsername(token);
                    var details = userDetailsService.loadUserByUsername(username);
                    if (details instanceof AppUserDetails appUser) {
                        userId = String.valueOf(appUser.getUser().getId());
                        MDC.put("userId", userId);
                    }
                } catch (Exception ignored) {
                    // invalid token — no request-level identity attached
                }
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Prefer RBAC-derived school context set by TenantResolverFilter over any fallback.
            String schoolId = MDC.get("schoolId");
            if (schoolId == null) {
                Long tenantSchoolId = TenantContext.get();
                if (tenantSchoolId != null) {
                    schoolId = String.valueOf(tenantSchoolId);
                }
            }
            String requestPath = request.getMethod() + " " + request.getRequestURI();
            int status = response.getStatus();
            long duration = System.currentTimeMillis() - start;
            log.info("[{} schoolId={} userId={} status={} duration={}ms]", requestPath,
                    schoolId, userId, status, duration);
            MDC.remove("userId");
            MDC.remove("schoolId");
        }
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7).trim();
    }
}

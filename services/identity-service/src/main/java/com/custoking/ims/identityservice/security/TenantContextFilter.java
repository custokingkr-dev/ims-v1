package com.custoking.ims.identityservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantContextFilter extends OncePerRequestFilter {
    private final JdbcClient jdbc;
    public TenantContextFilter(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        TenantContext.set(new TenantContext(
                parseLong(request.getHeader("X-Authenticated-User-Id")),
                trimToNull(request.getHeader("X-Authenticated-Email")),
                trimToNull(request.getHeader("X-Authenticated-Role")),
                parseLong(request.getHeader("X-Authenticated-School-Id")),
                parseLong(request.getHeader("X-Authenticated-Zone-Id")),
                parseStringSet(request.getHeader("X-Authenticated-Permissions"))));
        try {
            TenantContext ctx=TenantContext.get();
            if (ctx.isAuthenticated() && StepUpPolicy.required(ctx.role(),request.getMethod(),request.getRequestURI())) {
                String session=request.getHeader("X-Authenticated-Session-Id");
                boolean verified=ctx.userId()!=null && session!=null && jdbc.sql("""
                    SELECT 1 FROM identity.auth_sessions s JOIN identity.app_users u ON u.id=s.user_id
                    JOIN identity.session_step_up p ON p.family_id=s.family_id AND p.user_id=s.user_id
                    WHERE s.id=:session AND s.user_id=:user AND s.status IN ('ACTIVE','ROTATED')
                    AND s.expires_at>clock_timestamp() AND u.deleted_at IS NULL
                    AND s.credential_version=u.credential_version AND p.credential_version=u.credential_version
                    AND p.expires_at>clock_timestamp()
                    """).param("session",session).param("user",ctx.userId()).query(Integer.class).optional().isPresent();
                if(!verified) { response.setStatus(403); response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"STEP_UP_REQUIRED\",\"message\":\"Passkey verification required\"}"); return; }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static Long parseLong(String value) {
        if (!StringUtils.hasText(value)) return null;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Set<String> parseStringSet(String value) {
        if (!StringUtils.hasText(value)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}

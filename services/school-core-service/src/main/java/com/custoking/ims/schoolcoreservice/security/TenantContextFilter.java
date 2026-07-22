package com.custoking.ims.schoolcoreservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        TenantContext.set(new TenantContext(
                parseLong(request.getHeader("X-Authenticated-User-Id")),
                trimToNull(request.getHeader("X-Authenticated-Email")),
                trimToNull(request.getHeader("X-Authenticated-Role")),
                parseLong(request.getHeader("X-Authenticated-School-Id")),
                parseLong(request.getHeader("X-Authenticated-Zone-Id")),
                parseLongSet(request.getHeader("X-Authenticated-Operator-Schools")),
                parseStringSet(request.getHeader("X-Authenticated-Permissions"))));
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static Long parseLong(String value) {
        if (!StringUtils.hasText(value)) return null;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Set<Long> parseLongSet(String value) {
        if (!StringUtils.hasText(value)) return Set.of();
        Set<Long> result = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                result.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
                // skip malformed entries
            }
        }
        return result;
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

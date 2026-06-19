package com.custoking.ims.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Allows at most 10 POST /api/auth/login attempts per IP per 15-minute window.
 * Returns HTTP 429 and a JSON error body when the limit is exceeded.
 */
@Component
public class LoginRateLimiter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final int MAX_ATTEMPTS = 10;
    private static final long WINDOW_MS = 15 * 60 * 1000L;

    private record WindowEntry(AtomicInteger count, long windowStart) {}

    private final ConcurrentHashMap<String, WindowEntry> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !LOGIN_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        String ip = resolveClientIp(request);
        long now = System.currentTimeMillis();
        WindowEntry entry = windows.compute(ip, (k, existing) -> {
            if (existing == null || now - existing.windowStart() >= WINDOW_MS) {
                return new WindowEntry(new AtomicInteger(0), now);
            }
            return existing;
        });
        int attempts = entry.count().incrementAndGet();
        if (attempts > MAX_ATTEMPTS) {
            log.warn("Login rate limit exceeded for IP {} ({} attempts in window)", ip, attempts);
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"message\":\"Too many login attempts. Try again in 15 minutes.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Clears all rate-limit windows. For test isolation and operational reset. */
    public void reset() {
        windows.clear();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

package com.custoking.ims.identityservice.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Cookie mutation provenance is checked before refresh rotation or logout can run. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+5)
public class CookieRequestProvenanceFilter extends OncePerRequestFilter {
    private final Set<String> allowed;
    public CookieRequestProvenanceFilter(@Value("${identity.cookie.allowed-origins:}") String origins) {
        allowed=new HashSet<>();
        for(String origin:origins.split(",")) if(!origin.isBlank()) allowed.add(origin.trim());
    }
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        String path=request.getRequestURI();
        if("POST".equals(request.getMethod()) && ("/api/v1/auth/refresh".equals(path) || "/api/v1/auth/logout".equals(path))) {
            String origin=request.getHeader("Origin");
            boolean valid=origin!=null && allowed.contains(origin);
            if(origin==null && "same-origin".equals(request.getHeader("Sec-Fetch-Site"))) {
                try {
                    URI referer=URI.create(Objects.requireNonNull(request.getHeader("Referer")));
                    String refererOrigin=referer.getScheme()+"://"+referer.getRawAuthority();
                    valid=referer.getUserInfo()==null && allowed.contains(refererOrigin);
                } catch(RuntimeException ignored) { valid=false; }
            }
            if(!valid) { response.setStatus(403); response.setContentType("application/json"); response.getWriter().write("{\"code\":\"COOKIE_PROVENANCE_REQUIRED\",\"message\":\"Request origin rejected\"}"); return; }
        }
        chain.doFilter(request,response);
    }
}

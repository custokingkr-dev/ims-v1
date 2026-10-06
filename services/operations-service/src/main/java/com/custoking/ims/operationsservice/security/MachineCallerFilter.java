package com.custoking.ims.operationsservice.security;

import com.google.auth.oauth2.TokenVerifier;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Machine authority is per route, and independent of forwarded user or shared-token headers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class MachineCallerFilter extends OncePerRequestFilter {
    private static final TokenVerifier GOOGLE=TokenVerifier.newBuilder().setIssuer("https://accounts.google.com").build();
    private final Environment env;
    private final Function<String,Optional<String>> verifier;
    @org.springframework.beans.factory.annotation.Autowired
    public MachineCallerFilter(Environment env) {
        this(env, token -> verify(token,env));
    }
    MachineCallerFilter(Environment env,Function<String,Optional<String>> verifier) { this.env=env; this.verifier=verifier; }
    private static Optional<String> verify(String token,Environment env) {
        try {
            var payload=GOOGLE.verify(token).getPayload();
            Set<String> audiences=list(env.getProperty("oidc.audiences",env.getProperty("SERVICE_OIDC_AUDIENCES","")));
            Object aud=payload.getAudience();
            boolean audience=aud instanceof Iterable<?> values ? java.util.stream.StreamSupport.stream(values.spliterator(),false).anyMatch(audiences::contains) : audiences.contains(aud);
            if(!audience || !Boolean.TRUE.equals(payload.get("email_verified"))) return Optional.empty();
            return Optional.ofNullable((String)payload.get("email"));
        } catch(Exception rejected) { return Optional.empty(); }
    }
    static Set<String> list(String csv) {
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(v->!v.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    static String callerProperty(String path) {
        if(path.equals("/api/v1/internal/async/drain")) return "ASYNC_DRAIN_CALLER_SERVICE_ACCOUNTS";
        if(path.equals("/api/v1/internal/notifications/deliveries")) return "NOTIFICATION_DELIVERY_CALLER_SERVICE_ACCOUNTS";
        if(path.equals("/api/v1/internal/outbox/relay")) return "OUTBOX_RELAY_CALLER_SERVICE_ACCOUNTS";
        if(path.equals("/api/v1/internal/notifications/broadcast-recipients") || path.equals("/api/v1/internal/notifications/broadcast-recipients/fee-reminders")) return "BROADCAST_POLICY_CALLER_SERVICE_ACCOUNTS";
        if(path.equals("/api/v1/internal/password-reset/drain")) return "PASSWORD_RESET_DRAIN_CALLER_SERVICE_ACCOUNTS";
        if(path.matches("/api/v1/internal/identity-directory/(schools|zones)/[0-9]+")) return "IDENTITY_DIRECTORY_CALLER_SERVICE_ACCOUNTS";
        return null;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        boolean deployed=env.matchesProfiles("prod","dev") || env.getProperty("K_SERVICE")!=null;
        boolean principal=request.getHeader("X-Authenticated-User-Id")!=null || request.getHeader("X-Authenticated-Role")!=null;
        if(deployed && principal) {
            String carrier=request.getHeader("X-IMS-Principal-Carrier-Token");
            String peer=carrier!=null && carrier.startsWith("Bearer ") ? verifier.apply(carrier.substring(7)).orElse(null):null;
            if(peer==null || !list(env.getProperty("USER_CONTEXT_CALLER_SERVICE_ACCOUNTS","")).contains(peer)) {
                response.setStatus(403);response.setContentType("application/json");response.getWriter().write("{\"code\":\"PRINCIPAL_CARRIER_REJECTED\",\"message\":\"Caller not authorized\"}");return;
            }
        }
        String path;
        try { path=java.net.URI.create(request.getRequestURI()).normalize().getPath(); }
        catch(IllegalArgumentException malformed) { response.setStatus(403);return; }
        String property=callerProperty(path);
        if(property==null) {
            // Unknown/noncanonical internal paths cannot bypass the caller check through path-variable decoding.
            if(path.startsWith("/api/v1/internal/")) { response.setStatus(403);return; }
            chain.doFilter(request,response); return;
        }
        // Never decode Cloud Run's signature-stripped X-Serverless-Authorization as application authority.
        String authorization=request.getHeader("Authorization");
        String email=authorization!=null && authorization.startsWith("Bearer ") ? verifier.apply(authorization.substring(7)).orElse(null) : null;
        if(email==null || !list(env.getProperty(property,"")).contains(email)) {
            response.setStatus(403); response.setContentType("application/json"); response.getWriter().write("{\"code\":\"MACHINE_CALLER_REJECTED\",\"message\":\"Caller not authorized\"}"); return;
        }
        chain.doFilter(request,response);
    }
}

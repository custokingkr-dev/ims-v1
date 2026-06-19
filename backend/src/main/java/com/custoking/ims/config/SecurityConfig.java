package com.custoking.ims.config;

import com.custoking.ims.security.JwtAuthFilter;
import com.custoking.ims.security.LoginRateLimiter;
import com.custoking.ims.security.RequestCorrelationFilter;
import com.custoking.ims.security.TenantResolverFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.DefaultHttpSecurityExpressionHandler;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final TenantResolverFilter tenantResolverFilter;
    private final LoginRateLimiter loginRateLimiter;
    private final RequestCorrelationFilter requestCorrelationFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          TenantResolverFilter tenantResolverFilter,
                          LoginRateLimiter loginRateLimiter,
                          RequestCorrelationFilter requestCorrelationFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.tenantResolverFilter = tenantResolverFilter;
        this.loginRateLimiter = loginRateLimiter;
        this.requestCorrelationFilter = requestCorrelationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // The expression handler must carry the ApplicationContext, otherwise the
        // '@rbacService' bean reference in the SpEL below cannot be resolved
        // ("EL1057E: No bean resolver registered") and every actuator/swagger request
        // fails authorization with a 401 — even for SUPERADMIN.
        final var expressionHandler = new DefaultHttpSecurityExpressionHandler();
        expressionHandler.setApplicationContext(http.getSharedObject(ApplicationContext.class));

        final var actuatorAccess = new WebExpressionAuthorizationManager(
                "@rbacService.hasPermission(authentication, 'system:actuator')");
        actuatorAccess.setExpressionHandler(expressionHandler);
        final var swaggerAccess = new WebExpressionAuthorizationManager(
                "@rbacService.hasPermission(authentication, 'system:swagger')");
        swaggerAccess.setExpressionHandler(expressionHandler);

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public: auth endpoints + Cloud Run health probes only.
                        // Actuator paths use explicit ant matchers: the default String
                        // matcher resolves to an MvcRequestMatcher, which only matches
                        // *exposed* actuator endpoints. An unexposed sensitive endpoint
                        // (e.g. /actuator/env) would then slip past the rules below, be
                        // allowed for any authenticated user, 404, and surface as a 401
                        // on the ensuing /error dispatch instead of the intended 403.
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // The error dispatch must be reachable: Spring Security re-runs its
                        // chain on ERROR dispatches (CVE-2022-31692 hardening) but JwtAuthFilter,
                        // a OncePerRequestFilter, is skipped there — so the error request is
                        // anonymous. Without permitting /error, a legitimate 403 from a denied
                        // request gets overwritten with a 401 when the error page is rendered.
                        .requestMatchers(AntPathRequestMatcher.antMatcher("/error")).permitAll()
                        .requestMatchers(
                                AntPathRequestMatcher.antMatcher("/actuator/health"),
                                AntPathRequestMatcher.antMatcher("/actuator/health/liveness"),
                                AntPathRequestMatcher.antMatcher("/actuator/health/readiness")
                        ).permitAll()
                        // Swagger: require system:swagger permission (SUPERADMIN only).
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).access(swaggerAccess)
                        // Prometheus scraping: any authenticated service-account JWT.
                        .requestMatchers(AntPathRequestMatcher.antMatcher("/actuator/prometheus")).authenticated()
                        // All other actuator endpoints (exposed or not): require system:actuator.
                        .requestMatchers(AntPathRequestMatcher.antMatcher("/actuator/**")).access(actuatorAccess)
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                .addFilterBefore(requestCorrelationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(loginRateLimiter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantResolverFilter, JwtAuthFilter.class)
                .build();
    }
}

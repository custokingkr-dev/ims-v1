package com.custoking.ims.config;

import com.custoking.ims.security.JwtAuthFilter;
import com.custoking.ims.security.LoginRateLimiter;
import com.custoking.ims.security.RequestCorrelationFilter;
import com.custoking.ims.security.TenantResolverFilter;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
        final var actuatorAccess = new WebExpressionAuthorizationManager(
                "@rbacService.hasPermission(authentication, 'system:actuator')");
        final var swaggerAccess = new WebExpressionAuthorizationManager(
                "@rbacService.hasPermission(authentication, 'system:swagger')");

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public: auth endpoints + Cloud Run health probes only.
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/actuator/health",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness"
                        ).permitAll()
                        // Swagger: require system:swagger permission (SUPERADMIN only).
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).access(swaggerAccess)
                        // Prometheus scraping: any authenticated service-account JWT.
                        .requestMatchers("/actuator/prometheus").authenticated()
                        // All other actuator endpoints: require system:actuator permission.
                        .requestMatchers("/actuator/**").access(actuatorAccess)
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

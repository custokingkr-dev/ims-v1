package com.custoking.ims.config;

import com.custoking.ims.security.JwtAuthFilter;
import com.custoking.ims.security.LoginRateLimiter;
import com.custoking.ims.security.RequestCorrelationFilter;
import com.custoking.ims.security.TenantResolverFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
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
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public: auth + liveness/readiness probes + Swagger UI
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/actuator/health",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        // Metrics scraping — authenticated (service-account token or SUPERADMIN)
                        .requestMatchers("/actuator/prometheus").authenticated()
                        // All other actuator endpoints require SUPERADMIN
                        .requestMatchers("/actuator/**").hasRole("SUPERADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(requestCorrelationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(loginRateLimiter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantResolverFilter, JwtAuthFilter.class)
                .build();
    }
}

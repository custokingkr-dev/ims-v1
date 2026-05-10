package com.custoking.ims.config;

import com.custoking.ims.security.RbacPermissionEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Registers the custom PermissionEvaluator so that
 * @PreAuthorize("hasPermission(null, 'permission:code')") works via RbacService.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    private final RbacPermissionEvaluator rbacPermissionEvaluator;

    public MethodSecurityConfig(RbacPermissionEvaluator rbacPermissionEvaluator) {
        this.rbacPermissionEvaluator = rbacPermissionEvaluator;
    }

    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(rbacPermissionEvaluator);
        return handler;
    }
}

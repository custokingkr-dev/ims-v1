package com.custoking.ims.security;

import com.custoking.ims.service.RbacService;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * Wires RbacService into Spring's hasPermission() SpEL function.
 * After registration in MethodSecurityConfig, controllers can use:
 * @PreAuthorize("hasPermission(null, 'student:read')")
 */
@Component
public class RbacPermissionEvaluator implements PermissionEvaluator {

    private final RbacService rbacService;

    public RbacPermissionEvaluator(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @Override
    public boolean hasPermission(Authentication auth, Object targetDomainObject, Object permission) {
        return rbacService.hasPermission(auth, String.valueOf(permission));
    }

    @Override
    public boolean hasPermission(Authentication auth, Serializable targetId, String targetType, Object permission) {
        return rbacService.hasPermission(auth, String.valueOf(permission));
    }
}

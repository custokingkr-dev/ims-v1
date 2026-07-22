package com.custoking.ims.identityservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TenantScopeTest {

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void schoolUser_lockedToAuthenticatedSchool_whenNoneRequested() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(10L, TenantScope.resolveSchoolId(null));
    }

    @Test
    void schoolUser_matchingRequestedSchool_allowed() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(10L, TenantScope.resolveSchoolId(10L));
    }

    @Test
    void schoolUser_crossTenantRequest_forbidden() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> TenantScope.resolveSchoolId(99L));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void nonSuperadmin_withoutSchool_forbidden() {
        TenantContext.set(new TenantContext(2L, "z@x", "ZONE_ADMIN", null, 5L));
        assertThrows(ResponseStatusException.class, () -> TenantScope.resolveSchoolId(10L));
    }

    @Test
    void superadmin_widensToRequested_orAll() {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(77L, TenantScope.resolveSchoolId(77L));
        assertNull(TenantScope.resolveSchoolId(null));
    }

    @Test
    void emptyContext_isNotSuperadmin_andForbidsScopedAccess() {
        assertFalse(TenantContext.get().isSuperAdmin());
        assertThrows(ResponseStatusException.class, () -> TenantScope.resolveSchoolId(1L));
    }

    @Test
    void nonSuperadminWithPermission_isAllowed() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null, Set.of("user:read")));
        assertDoesNotThrow(() -> TenantScope.requirePermission("user:read"));
    }

    @Test
    void nonSuperadminWithoutPermission_isForbidden() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null, Set.of("user:read")));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> TenantScope.requirePermission("role:read"));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void superadminBypassesPermissionCheck() {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertDoesNotThrow(() -> TenantScope.requirePermission("role:assign"));
    }

    @Test
    void permissionIfAuthenticated_allowsInternalServiceTokenOnlyCalls() {
        assertDoesNotThrow(() -> TenantScope.requirePermissionIfAuthenticated("user:read"));
    }
}
